# Decisiones

Este documento recoge los supuestos tomados donde el enunciado o el contrato no eran explícitos, lo que quedó fuera del alcance y lo que cambiaría antes de producción.

## Arquitectura

```
com.store.inventory
├── api/            Contrato (sin cambios)
├── Inventory       Composition root: el único lugar que elige implementaciones
├── service/        ReservationInventoryService: coordina, no contiene reglas de negocio
├── domain/         Product (agregado), OrderHold, LowStockAlert, OrderConflictException
├── policy/         CategoryPolicy + CategoryPolicies: reglas por categoría en un único sitio
├── persistence/    Puertos ProductRepository / OrderIndex + implementaciones en memoria
└── alert/          Decoradores de StockAlertListener (tolerancia a fallos, multicanal)
```

- **`Product` es la frontera de consistencia.** Todas las reglas que evitan la sobreventa (stock, reservas activas, límite por pedido, idempotencia y estado del aviso) se evalúan sobre un único estado coherente. No conoce hilos ni relojes: el tiempo se le pasa como parámetro.
- **La concurrencia vive en el repositorio, no en el dominio.** `ProductRepository.compute(sku, action)` garantiza acceso exclusivo al producto. En memoria es un `ReentrantLock` por SKU: pedidos de productos distintos no se bloquean entre sí. En base de datos será una transacción, y el dominio no cambia.
- **Expiración perezosa.** Una reserva está activa mientras `now < expiresAt`, calculado con el `Clock` inyectado. No hay schedulers ni hilos de fondo: el comportamiento es determinista y los tests no duermen.

## Supuestos

### Reservas y reintentos
La app reenvía el pedido hasta recibir respuesta, así que `reserve` es **idempotente por `orderId`**:

| Situación del `orderId` | Resultado |
|---|---|
| Reserva activa, misma cantidad y producto | Devuelve la **misma** `Reservation` (mismo `expiresAt`: un reintento no alarga el plazo de pago) |
| Ya confirmado | Devuelve la reserva original, sin cambios |
| Reserva expirada | Se trata como un pedido nuevo: vuelve a validar el stock y crea otra reserva |
| Otro producto u otra cantidad | `OrderConflictException` (subclase de `IllegalStateException`): es un error del cliente que reintentar no arregla |
| Su intento anterior falló (sin stock, límite) | El id queda libre; no se retiene nada |

### Confirmación
- `confirm` exige una reserva **activa**, como dice el contrato. Un pedido desconocido, expirado o **ya confirmado** lanza `IllegalStateException`. Un pago aprobado después de que expire la reserva es un caso real: el servicio lo rechaza y quien llama debe resolverlo (reembolso, o reintentar `reserve` si aún hay stock).
- Una reserva expira **exactamente** en `expiresAt`. Confirmar en ese instante falla.

### Orden de validación de `reserve`
1. Cantidad ≤ 0 o identificadores vacíos → `IllegalArgumentException`, también para productos desconocidos.
2. Producto desconocido → `InsufficientStockException` con `available = 0`, como indica el contrato. No se aplica límite porque no tiene categoría.
3. Límite de la categoría → `OrderLimitExceededException`, antes que la falta de stock.
4. Stock insuficiente → `InsufficientStockException`.

### Productos y categorías
- Registrar de nuevo un SKU **cambia su categoría** y conserva el stock. Así Marketing puede pasar un producto a `FLASH_SALE` en temporada. Las reservas existentes conservan su `expiresAt`; las nuevas usan las reglas nuevas.
- **Añadir una categoría** = añadir la constante al enum (el contrato lo controla) + una línea en `CategoryPolicies.defaults()`. Si se olvida la segunda, `CategoryPolicies` se niega a construirse y `CategoryPoliciesTest` falla en CI, en lugar de fallar en pleno checkout.

### Avisos de stock bajo
- Se avisa cuando, tras una reserva, quedan **≤ 5** unidades disponibles. El valor enviado es el disponible en ese momento.
- **Un solo aviso** hasta que se reabastece (`addStock`). Las unidades liberadas por reservas expiradas **no** cuentan como reabastecimiento y no rearman el aviso.
- Tras reabastecer, si el producto vuelve a estar en ≤ 5 (incluso porque el reabastecimiento fue insuficiente), se avisa de nuevo con la siguiente reserva: compras necesita saber que sigue bajo.
- El aviso se evalúa al reservar, no al registrar ni al añadir stock. Así no se avisa por productos recién creados con 0 unidades.
- El listener se invoca **fuera del lock** del producto: un correo lento no frena a otros clientes. Además va envuelto en `FaultTolerantStockAlertListener`, de modo que un canal caído se registra en el log y **nunca hace fallar una venta**.
- Para más canales existe `CompositeStockAlertListener`: cada canal es una implementación de `StockAlertListener`, aislada de los fallos de los demás.

## Fuera del alcance

- **Liberación anticipada** (cliente que cancela antes de que expire): el contrato no la expone.
- **Límite por cliente**: el límite es por pedido. Un cliente puede crear varios pedidos `FLASH_SALE` de 2 unidades; no hay identidad de cliente en el contrato.
- **Persistencia real y varias instancias** (ver abajo).
- **Métricas y trazas**: solo hay log de los avisos fallidos (`System.Logger`, sin dependencias).

## Antes de producción

1. **Base de datos y varias instancias.** Los locks en memoria **no protegen entre instancias**. Hay que implementar `ProductRepository` con transacciones: `SELECT ... FOR UPDATE` por producto, o bien versionado optimista con reintento. Una alternativa es un `UPDATE` condicional sobre un contador de unidades disponibles. `OrderIndex` pasa a ser una restricción `UNIQUE` sobre el `order_id` de las reservas, y el estado "aviso enviado" se persiste con el producto.
2. **Avisos fiables y asíncronos.** Hoy se envían de forma síncrona y, si fallan, se pierden (solo quedan en el log). Lo adecuado es un *transactional outbox*: el aviso se guarda en la misma transacción que la reserva y un proceso aparte lo entrega con reintentos. Eso también saca la latencia del correo del camino del checkout.
3. **Crecimiento de memoria.** Las reservas expiradas se purgan al reservar, pero las confirmadas y el índice de pedidos crecen sin límite: se guardan para responder a reintentos tardíos. En base de datos es el histórico de pedidos; en memoria haría falta una política de retención.
4. **Pagos tardíos.** Definir con negocio qué ocurre si el pago se aprueba después de expirar la reserva: reembolso automático o reserva de rescate.
5. **Reglas configurables.** Mover la tabla de `CategoryPolicies.defaults()` y el umbral de stock bajo a configuración, para que Marketing no dependa de un despliegue. El constructor de `CategoryPolicies` ya acepta cualquier mapa y valida que esté completo.
6. **Observabilidad.** Métricas de reservas creadas, expiradas y confirmadas, rechazos por stock o límite, y contención de locks.
7. **Pruebas de carga** sobre la implementación con base de datos: el test de concurrencia actual solo cubre una JVM.

## Cómo verificarlo

```bash
mvn test
```

| Test | Qué demuestra |
|---|---|
| `ReservationLifecycleTest` | Plazos por categoría (con borde exacto), liberación, confirmación y expiración |
| `ReservationRulesTest` | Límites, orden de errores, productos desconocidos, validación de entradas |
| `RetriedOrderTest` | Idempotencia y conflictos de `orderId` |
| `LowStockAlertTest` | Un aviso por ciclo, rearmado solo al reabastecer, canal caído no rompe la venta |
| `ConcurrentReservationTest` | 200 clientes simultáneos: sin sobreventa, un solo aviso, reintentos concurrentes reservan una vez |
| `CategoryPoliciesTest` | Toda categoría tiene política; las reglas coinciden con el README |

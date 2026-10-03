package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Validation, per-category limits and the error each situation produces. */
class ReservationRulesTest {

    private final MutableClock clock = MutableClock.startingAt("2026-01-01T10:00:00Z");
    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(clock, (sku, available) -> { });
    }

    @Test
    void flashSaleAllowsAtMostTwoUnitsPerOrder() {
        service.registerProduct("FLASH", ProductCategory.FLASH_SALE);
        service.addStock("FLASH", 100);

        assertDoesNotThrow(() -> service.reserve("ORDER-1", "FLASH", 2));
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-2", "FLASH", 3));
        assertEquals(98, service.available("FLASH"));
    }

    @ParameterizedTest
    @EnumSource(value = ProductCategory.class, names = {"STANDARD", "PRE_ORDER"})
    void categoriesWithoutLimitAcceptAnyQuantityInStock(ProductCategory category) {
        service.registerProduct("SKU-1", category);
        service.addStock("SKU-1", 1_000);

        service.reserve("ORDER-1", "SKU-1", 1_000);

        assertEquals(0, service.available("SKU-1"));
    }

    @Test
    void orderLimitIsReportedBeforeLackOfStock() {
        service.registerProduct("FLASH", ProductCategory.FLASH_SALE);
        service.addStock("FLASH", 1);

        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "FLASH", 3));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void reservingANonPositiveQuantityIsRejected(int quantity) {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 5);

        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", quantity));
        assertEquals(5, service.available("SKU-1"));
    }

    @Test
    void invalidQuantityIsReportedEvenForUnknownProducts() {
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "UNKNOWN", 0));
    }

    @Test
    void unknownProductsHaveNoStock() {
        InsufficientStockException e = assertThrows(InsufficientStockException.class,
                () -> service.reserve("ORDER-1", "UNKNOWN", 1));

        assertEquals("Insufficient stock for UNKNOWN: requested 1, available 0", e.getMessage());
        assertEquals(0, service.available("UNKNOWN"));
    }

    @Test
    void registeredProductWithoutStockHasNothingToReserve() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);

        assertEquals(0, service.available("SKU-1"));
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 1));
    }

    @Test
    void failedReservationDoesNotHoldAnything() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 2);

        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 3));

        assertEquals(2, service.available("SKU-1"));
        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    void addingNonPositiveStockIsRejected(int quantity) {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);

        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", quantity));
    }

    @Test
    void addingStockToAnUnregisteredProductIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("UNKNOWN", 5));
    }

    @Test
    void missingIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct(null, ProductCategory.STANDARD));
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct("SKU-1", null));
        assertThrows(IllegalArgumentException.class, () -> service.reserve(" ", "SKU-1", 1));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", null, 1));
    }
}

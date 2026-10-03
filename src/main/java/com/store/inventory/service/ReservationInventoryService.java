package com.store.inventory.service;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.domain.LowStockAlert;
import com.store.inventory.domain.OrderConflictException;
import com.store.inventory.domain.OrderHold;
import com.store.inventory.domain.Product;
import com.store.inventory.persistence.OrderIndex;
import com.store.inventory.persistence.ProductRepository;
import com.store.inventory.policy.CategoryPolicies;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds units while customers pay, so the store never sells more units than it has.
 *
 * <p>This class coordinates; business rules live in {@link Product} (stock and reservations) and
 * {@link CategoryPolicies} (per-category rules). Low stock alerts are delivered after the product
 * is released, so a slow notification channel never blocks other customers.
 */
public final class ReservationInventoryService implements InventoryService {

    private final Clock clock;
    private final CategoryPolicies policies;
    private final ProductRepository products;
    private final OrderIndex orders;
    private final StockAlertListener alertListener;
    private final int lowStockThreshold;

    public ReservationInventoryService(Clock clock,
                                       CategoryPolicies policies,
                                       ProductRepository products,
                                       OrderIndex orders,
                                       StockAlertListener alertListener,
                                       int lowStockThreshold) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.products = Objects.requireNonNull(products, "products");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.alertListener = Objects.requireNonNull(alertListener, "alertListener");
        if (lowStockThreshold < 0) {
            throw new IllegalArgumentException("lowStockThreshold must not be negative: " + lowStockThreshold);
        }
        this.lowStockThreshold = lowStockThreshold;
    }

    /** Registering an existing product again updates its category; its stock is kept. */
    @Override
    public void registerProduct(String sku, ProductCategory category) {
        requireText(sku, "sku");
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (!products.addIfAbsent(new Product(sku, category))) {
            products.update(sku, product -> product.changeCategory(category));
        }
    }

    @Override
    public void addStock(String sku, int quantity) {
        requireText(sku, "sku");
        requirePositive(quantity);
        if (!products.update(sku, product -> product.restock(quantity))) {
            throw new IllegalArgumentException("Product " + sku + " is not registered");
        }
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        requireText(orderId, "orderId");
        requireText(sku, "sku");
        requirePositive(quantity);
        Instant now = clock.instant();

        ReserveOutcome outcome = products.compute(sku, product -> reserve(product, orderId, quantity, now))
                .orElseThrow(() -> new InsufficientStockException(sku, quantity, 0));

        outcome.alert().ifPresent(this::publish);
        return outcome.hold().toReservation();
    }

    @Override
    public void confirm(String orderId) {
        requireText(orderId, "orderId");
        Instant now = clock.instant();
        boolean found = orders.skuOf(orderId)
                .map(sku -> products.update(sku, product -> product.confirm(orderId, now)))
                .orElse(false);
        if (!found) {
            throw new IllegalStateException("Order " + orderId + " has no active reservation");
        }
    }

    @Override
    public int available(String sku) {
        requireText(sku, "sku");
        Instant now = clock.instant();
        return products.compute(sku, product -> product.available(now)).orElse(0);
    }

    /** Runs with exclusive access to {@code product}. */
    private ReserveOutcome reserve(Product product, String orderId, int quantity, Instant now) {
        Optional<String> boundSku = orders.bindIfAbsent(orderId, product.sku());
        if (boundSku.isPresent() && !boundSku.get().equals(product.sku())) {
            throw new OrderConflictException("Order " + orderId + " already reserved product "
                    + boundSku.get() + ", cannot reserve " + product.sku());
        }
        try {
            OrderHold hold = product.reserve(orderId, quantity, policies.forCategory(product.category()), now);
            return new ReserveOutcome(hold, product.pollLowStockAlert(lowStockThreshold, now));
        } catch (RuntimeException e) {
            if (boundSku.isEmpty()) {
                // The order never held units: free the id so it can be retried for any product.
                orders.unbind(orderId, product.sku());
            }
            throw e;
        }
    }

    private void publish(LowStockAlert alert) {
        alertListener.onLowStock(alert.sku(), alert.availableUnits());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, was " + quantity);
        }
    }

    private record ReserveOutcome(OrderHold hold, Optional<LowStockAlert> alert) {
    }
}

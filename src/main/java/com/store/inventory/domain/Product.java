package com.store.inventory.domain;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.policy.CategoryPolicy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Stock of one product and the orders holding it. This is the consistency boundary: every rule
 * that prevents overselling is checked here against a single, coherent state.
 *
 * <p>Not thread-safe by design. Callers must get exclusive access through
 * {@link com.store.inventory.persistence.ProductRepository}, which keeps concurrency (in-memory
 * locks today, database transactions tomorrow) out of the business rules.
 *
 * <p>Time is always passed in, never read here, so behaviour is deterministic and testable.
 */
public final class Product {

    private final String sku;
    private ProductCategory category;
    /** Physical units in the warehouse that have not been sold. Includes units held by orders. */
    private int unitsOnHand;
    private final Map<String, OrderHold> holdsByOrder = new HashMap<>();
    /** True until an alert is sent; re-armed only by restocking. */
    private boolean lowStockAlertArmed = true;

    public Product(String sku, ProductCategory category) {
        this.sku = Objects.requireNonNull(sku, "sku");
        this.category = Objects.requireNonNull(category, "category");
    }

    public String sku() {
        return sku;
    }

    public ProductCategory category() {
        return category;
    }

    /** Affects new reservations only; existing holds keep the expiry they were given. */
    public void changeCategory(ProductCategory newCategory) {
        this.category = Objects.requireNonNull(newCategory, "newCategory");
    }

    public void restock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, was " + quantity);
        }
        unitsOnHand = Math.addExact(unitsOnHand, quantity);
        lowStockAlertArmed = true;
    }

    /** Units that can still be reserved: units on hand minus units held by active reservations. */
    public int available(Instant now) {
        int held = holdsByOrder.values().stream()
                .filter(hold -> hold.isActiveAt(now))
                .mapToInt(OrderHold::quantity)
                .sum();
        return unitsOnHand - held;
    }

    /**
     * Holds {@code quantity} units for {@code orderId}.
     *
     * <p>Idempotent: if the order already holds units (still active, or already confirmed), the
     * same hold is returned and nothing changes. This makes client retries safe. If the earlier
     * hold expired, the units went back to stock and the order is treated as a new reservation.
     *
     * @throws OrderConflictException if the order already holds a different quantity
     * @throws com.store.inventory.api.OrderLimitExceededException if the category limit is exceeded
     * @throws InsufficientStockException if not enough units are available
     */
    public OrderHold reserve(String orderId, int quantity, CategoryPolicy policy, Instant now) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, was " + quantity);
        }
        releaseExpiredHolds(now);

        OrderHold existing = holdsByOrder.get(orderId);
        if (existing != null) {
            if (existing.quantity() != quantity) {
                throw new OrderConflictException("Order " + orderId + " already holds "
                        + existing.quantity() + " units of " + sku + ", requested " + quantity);
            }
            return existing;
        }

        policy.checkOrderQuantity(sku, quantity);
        int available = available(now);
        if (quantity > available) {
            throw new InsufficientStockException(sku, quantity, available);
        }

        OrderHold hold = OrderHold.reserved(orderId, sku, quantity, now.plus(policy.paymentWindow()));
        holdsByOrder.put(orderId, hold);
        return hold;
    }

    /**
     * Turns the order's active hold into a sale: the units leave the warehouse for good.
     *
     * @throws IllegalStateException if the order has no active hold (unknown, expired or already
     *                               confirmed)
     */
    public void confirm(String orderId, Instant now) {
        OrderHold hold = holdsByOrder.get(orderId);
        if (hold == null || !hold.isActiveAt(now)) {
            throw new IllegalStateException("Order " + orderId + " has no active reservation for " + sku);
        }
        unitsOnHand -= hold.quantity();
        holdsByOrder.put(orderId, hold.confirmed());
    }

    /**
     * Returns an alert the first time available units are at or below {@code threshold}, and
     * nothing afterwards until the product is restocked.
     */
    public Optional<LowStockAlert> pollLowStockAlert(int threshold, Instant now) {
        if (!lowStockAlertArmed) {
            return Optional.empty();
        }
        int available = available(now);
        if (available > threshold) {
            return Optional.empty();
        }
        lowStockAlertArmed = false;
        return Optional.of(new LowStockAlert(sku, available));
    }

    /** Expired holds no longer count; dropping them keeps memory bounded. */
    private void releaseExpiredHolds(Instant now) {
        holdsByOrder.values().removeIf(hold -> hold.isExpiredAt(now));
    }
}

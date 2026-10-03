package com.store.inventory.policy;

import com.store.inventory.api.OrderLimitExceededException;
import java.time.Duration;
import java.util.Objects;

/**
 * Business rules that depend on the product category: how long a reservation is held while the
 * customer pays, and how many units a single order may reserve.
 *
 * <p>Immutable value object. Build instances with {@link #unlimited(Duration)} or
 * {@link #limitedTo(Duration, int)} so the intent reads clearly in the catalog.
 */
public record CategoryPolicy(Duration paymentWindow, int maxUnitsPerOrder) {

    private static final int UNLIMITED = Integer.MAX_VALUE;

    public CategoryPolicy {
        Objects.requireNonNull(paymentWindow, "paymentWindow");
        if (paymentWindow.isZero() || paymentWindow.isNegative()) {
            throw new IllegalArgumentException("paymentWindow must be positive: " + paymentWindow);
        }
        if (maxUnitsPerOrder <= 0) {
            throw new IllegalArgumentException("maxUnitsPerOrder must be positive: " + maxUnitsPerOrder);
        }
    }

    public static CategoryPolicy unlimited(Duration paymentWindow) {
        return new CategoryPolicy(paymentWindow, UNLIMITED);
    }

    public static CategoryPolicy limitedTo(Duration paymentWindow, int maxUnitsPerOrder) {
        return new CategoryPolicy(paymentWindow, maxUnitsPerOrder);
    }

    public boolean hasOrderLimit() {
        return maxUnitsPerOrder != UNLIMITED;
    }

    /**
     * @throws OrderLimitExceededException if {@code quantity} exceeds the units allowed per order
     */
    public void checkOrderQuantity(String sku, int quantity) {
        if (quantity > maxUnitsPerOrder) {
            throw new OrderLimitExceededException(sku, quantity, maxUnitsPerOrder);
        }
    }
}

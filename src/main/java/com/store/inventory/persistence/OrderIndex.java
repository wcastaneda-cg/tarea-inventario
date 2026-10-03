package com.store.inventory.persistence;

import java.util.Optional;

/**
 * Remembers which product each order reserved, so {@code confirm(orderId)} can find it and an
 * order id can never hold two different products.
 *
 * <p>In a database this is a unique constraint on the reservation's order id.
 */
public interface OrderIndex {

    Optional<String> skuOf(String orderId);

    /**
     * Binds the order to the product unless it is already bound (atomic).
     *
     * @return the SKU the order was already bound to, or empty if this call created the binding
     */
    Optional<String> bindIfAbsent(String orderId, String sku);

    /** Removes the binding only if it still points to {@code sku}. */
    void unbind(String orderId, String sku);
}

package com.store.inventory.persistence;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe, single-instance {@link OrderIndex}. */
public final class InMemoryOrderIndex implements OrderIndex {

    private final ConcurrentMap<String, String> skuByOrder = new ConcurrentHashMap<>();

    @Override
    public Optional<String> skuOf(String orderId) {
        return Optional.ofNullable(skuByOrder.get(orderId));
    }

    @Override
    public Optional<String> bindIfAbsent(String orderId, String sku) {
        return Optional.ofNullable(skuByOrder.putIfAbsent(orderId, sku));
    }

    @Override
    public void unbind(String orderId, String sku) {
        skuByOrder.remove(orderId, sku);
    }
}

package com.store.inventory.persistence;

import com.store.inventory.domain.Product;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Thread-safe, single-instance repository. Each product has its own lock, so concurrent orders
 * for different products run in parallel while orders for the same product are serialized.
 *
 * <p>Locks live in this JVM only: this implementation does not protect against overselling when
 * the service runs on several instances. See DECISIONS.md.
 */
public final class InMemoryProductRepository implements ProductRepository {

    private final ConcurrentMap<String, GuardedProduct> products = new ConcurrentHashMap<>();

    @Override
    public boolean addIfAbsent(Product product) {
        Objects.requireNonNull(product, "product");
        return products.putIfAbsent(product.sku(), new GuardedProduct(product)) == null;
    }

    @Override
    public <T> Optional<T> compute(String sku, Function<Product, T> action) {
        Objects.requireNonNull(action, "action");
        GuardedProduct guarded = products.get(sku);
        if (guarded == null) {
            return Optional.empty();
        }
        guarded.lock.lock();
        try {
            return Optional.of(action.apply(guarded.product));
        } finally {
            guarded.lock.unlock();
        }
    }

    private static final class GuardedProduct {
        private final Product product;
        private final ReentrantLock lock = new ReentrantLock();

        private GuardedProduct(Product product) {
            this.product = product;
        }
    }
}

package com.store.inventory.persistence;

import com.store.inventory.domain.Product;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Storage for {@link Product} aggregates.
 *
 * <p>The contract that protects against overselling: {@link #compute} gives the action
 * <b>exclusive access</b> to one product for its whole duration, so read-check-write sequences
 * are atomic. Different products never block each other.
 *
 * <p>A database implementation would run the action inside a transaction (row lock with
 * {@code SELECT ... FOR UPDATE}, or optimistic versioning with retry), keeping the same contract.
 * Actions must therefore be short and free of side effects other than mutating the product.
 */
public interface ProductRepository {

    /** @return {@code false} if a product with the same SKU already exists (nothing is changed) */
    boolean addIfAbsent(Product product);

    /**
     * Runs {@code action} with exclusive access to the product.
     *
     * @return the action's (non-null) result, or empty if the product does not exist
     */
    <T> Optional<T> compute(String sku, Function<Product, T> action);

    /**
     * Same as {@link #compute} for actions without a result.
     *
     * @return {@code false} if the product does not exist
     */
    default boolean update(String sku, Consumer<Product> action) {
        return compute(sku, product -> {
            action.accept(product);
            return Boolean.TRUE;
        }).isPresent();
    }
}

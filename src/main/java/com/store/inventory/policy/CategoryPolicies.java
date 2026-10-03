package com.store.inventory.policy;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single place where each {@link ProductCategory} gets its {@link CategoryPolicy}.
 *
 * <p><b>Adding a new category</b> (e.g. a seasonal one created by Marketing) only requires a new
 * entry in {@link #defaults()}. The catalog refuses to build if any category lacks a policy, so a
 * forgotten entry fails at startup and in {@code CategoryPoliciesTest}, never mid-checkout.
 */
public final class CategoryPolicies {

    private final Map<ProductCategory, CategoryPolicy> policies;

    public CategoryPolicies(Map<ProductCategory, CategoryPolicy> policies) {
        Objects.requireNonNull(policies, "policies");
        List<ProductCategory> missing = Arrays.stream(ProductCategory.values())
                .filter(category -> policies.get(category) == null)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing policy for categories: " + missing);
        }
        this.policies = new EnumMap<>(policies);
    }

    /** Business rules agreed with the business (see README). */
    public static CategoryPolicies defaults() {
        Map<ProductCategory, CategoryPolicy> policies = new EnumMap<>(ProductCategory.class);
        policies.put(ProductCategory.STANDARD, CategoryPolicy.unlimited(Duration.ofMinutes(15)));
        // Paid by bank transfer, which takes longer to clear.
        policies.put(ProductCategory.PRE_ORDER, CategoryPolicy.unlimited(Duration.ofHours(24)));
        policies.put(ProductCategory.FLASH_SALE, CategoryPolicy.limitedTo(Duration.ofMinutes(5), 2));
        return new CategoryPolicies(policies);
    }

    public CategoryPolicy forCategory(ProductCategory category) {
        Objects.requireNonNull(category, "category");
        return policies.get(category);
    }
}

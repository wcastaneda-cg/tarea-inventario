package com.store.inventory.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CategoryPoliciesTest {

    /** Fails as soon as someone adds a category to the enum without defining its rules. */
    @ParameterizedTest
    @EnumSource(ProductCategory.class)
    void everyCategoryHasAPolicy(ProductCategory category) {
        assertNotNull(CategoryPolicies.defaults().forCategory(category));
    }

    @Test
    void defaultsMatchTheBusinessRules() {
        CategoryPolicies policies = CategoryPolicies.defaults();

        assertEquals(CategoryPolicy.unlimited(Duration.ofMinutes(15)), policies.forCategory(ProductCategory.STANDARD));
        assertEquals(CategoryPolicy.unlimited(Duration.ofHours(24)), policies.forCategory(ProductCategory.PRE_ORDER));
        assertEquals(CategoryPolicy.limitedTo(Duration.ofMinutes(5), 2), policies.forCategory(ProductCategory.FLASH_SALE));
    }

    @Test
    void refusesToBuildWithAMissingCategory() {
        Map<ProductCategory, CategoryPolicy> incomplete = new EnumMap<>(ProductCategory.class);
        incomplete.put(ProductCategory.STANDARD, CategoryPolicy.unlimited(Duration.ofMinutes(15)));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new CategoryPolicies(incomplete));
        assertTrue(e.getMessage().contains("PRE_ORDER"));
        assertTrue(e.getMessage().contains("FLASH_SALE"));
    }

    @Test
    void limitedPolicyRejectsOrdersAboveTheLimit() {
        CategoryPolicy policy = CategoryPolicy.limitedTo(Duration.ofMinutes(5), 2);

        policy.checkOrderQuantity("SKU", 2);
        assertThrows(OrderLimitExceededException.class, () -> policy.checkOrderQuantity("SKU", 3));
        assertTrue(policy.hasOrderLimit());
    }

    @Test
    void unlimitedPolicyAcceptsAnyQuantity() {
        CategoryPolicy policy = CategoryPolicy.unlimited(Duration.ofMinutes(15));

        policy.checkOrderQuantity("SKU", Integer.MAX_VALUE);
        assertFalse(policy.hasOrderLimit());
    }

    @Test
    void rejectsInvalidPolicies() {
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.unlimited(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.limitedTo(Duration.ofMinutes(5), 0));
    }
}

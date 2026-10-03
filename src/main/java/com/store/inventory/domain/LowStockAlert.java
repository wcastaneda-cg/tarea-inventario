package com.store.inventory.domain;

/** A product's available units dropped to the low stock threshold or below. */
public record LowStockAlert(String sku, int availableUnits) {
}

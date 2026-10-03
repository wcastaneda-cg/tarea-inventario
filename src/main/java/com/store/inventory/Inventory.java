package com.store.inventory;

import com.store.inventory.alert.FaultTolerantStockAlertListener;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.persistence.InMemoryOrderIndex;
import com.store.inventory.persistence.InMemoryProductRepository;
import com.store.inventory.policy.CategoryPolicies;
import com.store.inventory.service.ReservationInventoryService;
import java.time.Clock;
import java.util.Objects;

/**
 * Entry point used by our automated tests. Keep this signature exactly as it is,
 * and build your implementation here.
 *
 * <p>Composition root: the only place that decides which implementations are used. Moving to a
 * database means swapping the in-memory repositories here.
 */
public final class Inventory {

    /** Purchasing is alerted when a product has this many available units or fewer. */
    static final int LOW_STOCK_THRESHOLD = 5;

    private Inventory() {
    }

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(alertListener, "alertListener");
        return new ReservationInventoryService(
                clock,
                CategoryPolicies.defaults(),
                new InMemoryProductRepository(),
                new InMemoryOrderIndex(),
                new FaultTolerantStockAlertListener(alertListener),
                LOW_STOCK_THRESHOLD);
    }
}

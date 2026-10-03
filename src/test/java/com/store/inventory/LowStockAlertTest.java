package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.support.MutableClock;
import com.store.inventory.support.RecordingAlertListener;
import com.store.inventory.support.RecordingAlertListener.Alert;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Purchasing hears once when a product gets low, and again only after it was restocked. */
class LowStockAlertTest {

    private static final String SKU = "SKU-1";

    private final MutableClock clock = MutableClock.startingAt("2026-01-01T10:00:00Z");
    private final RecordingAlertListener listener = new RecordingAlertListener();
    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(clock, listener);
        service.registerProduct(SKU, ProductCategory.STANDARD);
    }

    @Test
    void noAlertWhileMoreThanFiveUnitsAreAvailable() {
        service.addStock(SKU, 10);

        service.reserve("ORDER-1", SKU, 4);

        assertEquals(List.of(), listener.alerts());
    }

    @Test
    void alertsWhenAvailableUnitsReachFive() {
        service.addStock(SKU, 10);

        service.reserve("ORDER-1", SKU, 5);

        assertEquals(List.of(new Alert(SKU, 5)), listener.alerts());
    }

    @Test
    void alertsWithTheUnitsLeftWhenDroppingBelowFive() {
        service.addStock(SKU, 10);

        service.reserve("ORDER-1", SKU, 8);

        assertEquals(List.of(new Alert(SKU, 2)), listener.alerts());
    }

    @Test
    void doesNotRepeatTheAlertUntilRestocked() {
        service.addStock(SKU, 10);
        service.reserve("ORDER-1", SKU, 5);
        service.reserve("ORDER-2", SKU, 1);
        service.confirm("ORDER-2");
        service.reserve("ORDER-3", SKU, 4);

        assertEquals(List.of(new Alert(SKU, 5)), listener.alerts());
    }

    @Test
    void unitsReleasedByExpiryAreNotARestock() {
        service.addStock(SKU, 10);
        service.reserve("ORDER-1", SKU, 6);
        clock.advance(Duration.ofMinutes(15));

        service.reserve("ORDER-2", SKU, 6);

        assertEquals(List.of(new Alert(SKU, 4)), listener.alerts());
    }

    @Test
    void alertsAgainAfterARestock() {
        service.addStock(SKU, 10);
        service.reserve("ORDER-1", SKU, 6);

        service.addStock(SKU, 10);
        service.reserve("ORDER-2", SKU, 10);

        assertEquals(List.of(new Alert(SKU, 4), new Alert(SKU, 4)), listener.alerts());
    }

    @Test
    void alertsAgainWhenARestockWasNotEnough() {
        service.addStock(SKU, 6);
        service.reserve("ORDER-1", SKU, 4);

        service.addStock(SKU, 1);
        service.reserve("ORDER-2", SKU, 1);

        assertEquals(List.of(new Alert(SKU, 2), new Alert(SKU, 2)), listener.alerts());
    }

    @Test
    void retriedOrderDoesNotRepeatTheAlert() {
        service.addStock(SKU, 10);
        service.reserve("ORDER-1", SKU, 6);

        service.reserve("ORDER-1", SKU, 6);

        assertEquals(List.of(new Alert(SKU, 4)), listener.alerts());
    }

    @Test
    void alertsAreTrackedPerProduct() {
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock(SKU, 6);
        service.addStock("SKU-2", 6);

        service.reserve("ORDER-1", SKU, 1);
        service.reserve("ORDER-2", "SKU-2", 2);

        assertEquals(List.of(new Alert(SKU, 5), new Alert("SKU-2", 4)), listener.alerts());
    }

    @Test
    void failingAlertChannelDoesNotFailTheReservation() {
        InventoryService withBrokenMail = Inventory.create(clock, (sku, available) -> {
            throw new IllegalStateException("mail server down");
        });
        withBrokenMail.registerProduct(SKU, ProductCategory.STANDARD);
        withBrokenMail.addStock(SKU, 6);

        assertDoesNotThrow(() -> withBrokenMail.reserve("ORDER-1", SKU, 3));
        assertEquals(3, withBrokenMail.available(SKU));
    }
}

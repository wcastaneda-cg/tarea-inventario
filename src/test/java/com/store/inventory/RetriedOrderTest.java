package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The mobile app resends an order until it gets an answer: retries must never reserve twice. */
class RetriedOrderTest {

    private static final String SKU = "SKU-1";

    private final MutableClock clock = MutableClock.startingAt("2026-01-01T10:00:00Z");
    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct(SKU, ProductCategory.STANDARD);
        service.addStock(SKU, 10);
    }

    @Test
    void retryingAnOrderReturnsTheOriginalReservation() {
        Reservation first = service.reserve("ORDER-1", SKU, 3);
        clock.advance(Duration.ofMinutes(1));

        Reservation retry = service.reserve("ORDER-1", SKU, 3);

        assertEquals(first, retry, "same expiry: a retry does not extend the payment window");
        assertEquals(7, service.available(SKU));
    }

    @Test
    void retryArrivingAfterPaymentReturnsTheConfirmedReservation() {
        Reservation first = service.reserve("ORDER-1", SKU, 3);
        service.confirm("ORDER-1");

        Reservation retry = service.reserve("ORDER-1", SKU, 3);

        assertEquals(first, retry);
        assertEquals(7, service.available(SKU));
    }

    @Test
    void orderWhoseReservationExpiredCanReserveAgain() {
        Reservation first = service.reserve("ORDER-1", SKU, 3);
        clock.advance(Duration.ofMinutes(15));

        Reservation second = service.reserve("ORDER-1", SKU, 3);

        assertNotEquals(first.expiresAt(), second.expiresAt());
        assertEquals(7, service.available(SKU));
        service.confirm("ORDER-1");
        assertEquals(7, service.available(SKU));
    }

    @Test
    void reusingAnOrderIdForADifferentQuantityIsAConflict() {
        service.reserve("ORDER-1", SKU, 3);

        assertThrows(IllegalStateException.class, () -> service.reserve("ORDER-1", SKU, 4));
        assertEquals(7, service.available(SKU));
    }

    @Test
    void reusingAnOrderIdForADifferentProductIsAConflict() {
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-2", 10);
        service.reserve("ORDER-1", SKU, 3);

        assertThrows(IllegalStateException.class, () -> service.reserve("ORDER-1", "SKU-2", 3));
        assertEquals(10, service.available("SKU-2"));
    }

    @Test
    void orderIdOfAFailedReservationCanBeUsedForAnotherProduct() {
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-2", 10);
        assertThrows(RuntimeException.class, () -> service.reserve("ORDER-1", SKU, 50));

        service.reserve("ORDER-1", "SKU-2", 3);
        service.confirm("ORDER-1");

        assertEquals(7, service.available("SKU-2"));
        assertEquals(10, service.available(SKU));
    }
}

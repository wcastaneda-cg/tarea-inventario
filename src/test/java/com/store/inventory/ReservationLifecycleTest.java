package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Reserve → pay (confirm) or run out of time (expire), driven by a controllable clock. */
class ReservationLifecycleTest {

    private static final String SKU = "SKU-1";

    private final MutableClock clock = MutableClock.startingAt("2026-01-01T10:00:00Z");
    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(clock, (sku, available) -> { });
    }

    static Stream<Arguments> paymentWindows() {
        return Stream.of(
                Arguments.of(ProductCategory.STANDARD, Duration.ofMinutes(15)),
                Arguments.of(ProductCategory.PRE_ORDER, Duration.ofHours(24)),
                Arguments.of(ProductCategory.FLASH_SALE, Duration.ofMinutes(5)));
    }

    @ParameterizedTest(name = "{0} reservations expire after {1}")
    @MethodSource("paymentWindows")
    void reservationExpiresAfterTheCategoryPaymentWindow(ProductCategory category, Duration window) {
        service.registerProduct(SKU, category);
        service.addStock(SKU, 10);
        Instant reservedAt = clock.instant();

        Reservation reservation = service.reserve("ORDER-1", SKU, 2);

        assertEquals(new Reservation("ORDER-1", SKU, 2, reservedAt.plus(window)), reservation);
        clock.advance(window.minusNanos(1));
        assertEquals(8, service.available(SKU), "still held just before expiry");
        clock.advance(Duration.ofNanos(1));
        assertEquals(10, service.available(SKU), "released exactly at expiresAt");
    }

    @Test
    void releasedUnitsCanBeBoughtByAnotherCustomer() {
        service.registerProduct(SKU, ProductCategory.STANDARD);
        service.addStock(SKU, 1);
        service.reserve("ORDER-1", SKU, 1);
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-2", SKU, 1));

        clock.advance(Duration.ofMinutes(15));

        service.reserve("ORDER-2", SKU, 1);
        service.confirm("ORDER-2");
        assertEquals(0, service.available(SKU));
    }

    @Test
    void confirmingWithinTheWindowSellsTheUnitsForGood() {
        service.registerProduct(SKU, ProductCategory.FLASH_SALE);
        service.addStock(SKU, 5);
        service.reserve("ORDER-1", SKU, 2);
        clock.advance(Duration.ofMinutes(4));

        service.confirm("ORDER-1");
        clock.advance(Duration.ofDays(365));

        assertEquals(3, service.available(SKU));
    }

    @Test
    void cannotConfirmAnExpiredReservation() {
        service.registerProduct(SKU, ProductCategory.STANDARD);
        service.addStock(SKU, 5);
        service.reserve("ORDER-1", SKU, 2);
        clock.advance(Duration.ofMinutes(15));

        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
        assertEquals(5, service.available(SKU));
    }

    @Test
    void cannotConfirmAnUnknownOrder() {
        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-404"));
    }

    @Test
    void cannotConfirmTheSameOrderTwice() {
        service.registerProduct(SKU, ProductCategory.STANDARD);
        service.addStock(SKU, 5);
        service.reserve("ORDER-1", SKU, 2);
        service.confirm("ORDER-1");

        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
        assertEquals(3, service.available(SKU), "units are not sold twice");
    }

    @Test
    void addingStockMakesMoreUnitsAvailableWithoutTouchingReservations() {
        service.registerProduct(SKU, ProductCategory.STANDARD);
        service.addStock(SKU, 3);
        service.reserve("ORDER-1", SKU, 3);

        service.addStock(SKU, 4);

        assertEquals(4, service.available(SKU));
    }

    @Test
    void reRegisteringAProductChangesItsCategoryAndKeepsItsStock() {
        service.registerProduct(SKU, ProductCategory.STANDARD);
        service.addStock(SKU, 10);
        Reservation before = service.reserve("ORDER-1", SKU, 3);

        service.registerProduct(SKU, ProductCategory.FLASH_SALE);
        Reservation after = service.reserve("ORDER-2", SKU, 2);

        assertEquals(clock.instant().plus(Duration.ofMinutes(15)), before.expiresAt(), "existing hold is kept");
        assertEquals(clock.instant().plus(Duration.ofMinutes(5)), after.expiresAt());
        assertEquals(5, service.available(SKU));
    }
}

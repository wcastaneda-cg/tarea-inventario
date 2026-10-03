package com.store.inventory.domain;

import com.store.inventory.api.Reservation;
import java.time.Instant;
import java.util.Objects;

/**
 * Units of a product held for one order.
 *
 * <p>Lifecycle: {@code RESERVED} → {@code CONFIRMED} (paid), or {@code RESERVED} → expired (not
 * paid in time). Expiry is not a stored state: it is derived from {@link #expiresAt()} and the
 * current time, so no background job is needed to release units.
 */
public record OrderHold(String orderId, String sku, int quantity, Instant expiresAt, Status status) {

    public enum Status { RESERVED, CONFIRMED }

    public OrderHold {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(status, "status");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
    }

    static OrderHold reserved(String orderId, String sku, int quantity, Instant expiresAt) {
        return new OrderHold(orderId, sku, quantity, expiresAt, Status.RESERVED);
    }

    OrderHold confirmed() {
        return new OrderHold(orderId, sku, quantity, expiresAt, Status.CONFIRMED);
    }

    /** Holds units that nobody else can reserve. A hold expires exactly at {@code expiresAt}. */
    public boolean isActiveAt(Instant now) {
        return status == Status.RESERVED && now.isBefore(expiresAt);
    }

    public boolean isExpiredAt(Instant now) {
        return status == Status.RESERVED && !now.isBefore(expiresAt);
    }

    public Reservation toReservation() {
        return new Reservation(orderId, sku, quantity, expiresAt);
    }
}

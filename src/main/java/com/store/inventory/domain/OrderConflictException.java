package com.store.inventory.domain;

/**
 * An order id was reused with a different request (another product or quantity).
 *
 * <p>Retries of the same request are accepted and answered with the original reservation; this
 * exception only signals a genuine conflict, which retrying will not fix.
 */
public class OrderConflictException extends IllegalStateException {

    public OrderConflictException(String message) {
        super(message);
    }
}

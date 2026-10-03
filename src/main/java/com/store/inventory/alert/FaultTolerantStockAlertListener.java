package com.store.inventory.alert;

import com.store.inventory.api.StockAlertListener;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * Decorator that keeps a failing notification channel (mail server down, etc.) from failing the
 * customer's reservation. Alerts are informative; selling must not depend on them.
 */
public final class FaultTolerantStockAlertListener implements StockAlertListener {

    private static final Logger LOG = System.getLogger(FaultTolerantStockAlertListener.class.getName());

    private final StockAlertListener delegate;

    public FaultTolerantStockAlertListener(StockAlertListener delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        try {
            delegate.onLowStock(sku, availableUnits);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Low stock alert for " + sku + " (" + availableUnits
                    + " available) could not be delivered by " + delegate.getClass().getName(), e);
        }
    }
}

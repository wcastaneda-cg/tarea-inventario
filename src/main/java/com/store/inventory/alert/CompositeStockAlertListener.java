package com.store.inventory.alert;

import com.store.inventory.api.StockAlertListener;
import java.util.List;
import java.util.Objects;

/**
 * Sends each alert to several channels (mail today; chat, SMS... later). A new channel is a new
 * {@link StockAlertListener} implementation plus one line where the service is wired; the
 * inventory logic does not change.
 *
 * <p>Channels are isolated: one failing does not prevent the others from being notified.
 */
public final class CompositeStockAlertListener implements StockAlertListener {

    private final List<StockAlertListener> channels;

    private CompositeStockAlertListener(List<StockAlertListener> channels) {
        this.channels = channels;
    }

    public static CompositeStockAlertListener of(List<? extends StockAlertListener> channels) {
        Objects.requireNonNull(channels, "channels");
        return new CompositeStockAlertListener(channels.stream()
                .map(channel -> (StockAlertListener) new FaultTolerantStockAlertListener(channel))
                .toList());
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        channels.forEach(channel -> channel.onLowStock(sku, availableUnits));
    }
}

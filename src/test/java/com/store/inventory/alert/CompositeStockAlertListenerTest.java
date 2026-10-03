package com.store.inventory.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.store.inventory.api.StockAlertListener;
import com.store.inventory.support.RecordingAlertListener;
import com.store.inventory.support.RecordingAlertListener.Alert;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompositeStockAlertListenerTest {

    @Test
    void notifiesEveryChannel() {
        RecordingAlertListener mail = new RecordingAlertListener();
        RecordingAlertListener chat = new RecordingAlertListener();

        CompositeStockAlertListener.of(List.of(mail, chat)).onLowStock("SKU-1", 3);

        assertEquals(List.of(new Alert("SKU-1", 3)), mail.alerts());
        assertEquals(List.of(new Alert("SKU-1", 3)), chat.alerts());
    }

    @Test
    void aFailingChannelDoesNotStopTheOthers() {
        StockAlertListener broken = (sku, available) -> {
            throw new IllegalStateException("down");
        };
        RecordingAlertListener chat = new RecordingAlertListener();

        CompositeStockAlertListener.of(List.of(broken, chat)).onLowStock("SKU-1", 3);

        assertEquals(List.of(new Alert("SKU-1", 3)), chat.alerts());
    }
}

package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.support.MutableClock;
import com.store.inventory.support.RecordingAlertListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/** High season: hundreds of customers ordering the same product at the same instant. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConcurrentReservationTest {

    private static final int CUSTOMERS = 200;
    private static final String SKU = "SKU-HOT";

    private final MutableClock clock = MutableClock.startingAt("2026-01-01T10:00:00Z");
    private final RecordingAlertListener listener = new RecordingAlertListener();
    private final InventoryService service = Inventory.create(clock, listener);
    private final ExecutorService pool = Executors.newFixedThreadPool(32);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @RepeatedTest(5)
    void neverSellsMoreUnitsThanInStock() throws Exception {
        service.registerProduct(SKU, ProductCategory.STANDARD);
        service.addStock(SKU, 50);

        List<Outcome> outcomes = runConcurrently(CUSTOMERS, i -> {
            try {
                service.reserve("ORDER-" + i, SKU, 1);
                return Outcome.RESERVED;
            } catch (InsufficientStockException e) {
                return Outcome.SOLD_OUT;
            }
        });

        assertEquals(50, outcomes.stream().filter(Outcome.RESERVED::equals).count());
        assertEquals(CUSTOMERS - 50, outcomes.stream().filter(Outcome.SOLD_OUT::equals).count());
        assertEquals(0, service.available(SKU));
        assertEquals(List.of(new RecordingAlertListener.Alert(SKU, 5)), listener.alerts(), "exactly one alert");
    }

    @RepeatedTest(5)
    void concurrentRetriesOfTheSameOrderReserveOnce() throws Exception {
        service.registerProduct(SKU, ProductCategory.STANDARD);
        service.addStock(SKU, 10);

        List<Reservation> reservations = runConcurrently(CUSTOMERS, i -> service.reserve("ORDER-1", SKU, 3));

        Set<Reservation> distinct = Set.copyOf(reservations);
        assertEquals(1, distinct.size(), "every retry gets the same reservation");
        assertEquals(7, service.available(SKU));
    }

    @RepeatedTest(5)
    void ordersForDifferentProductsDoNotInterfere() throws Exception {
        int products = 10;
        for (int p = 0; p < products; p++) {
            service.registerProduct("SKU-" + p, ProductCategory.FLASH_SALE);
            service.addStock("SKU-" + p, 20);
        }

        runConcurrently(CUSTOMERS, i -> service.reserve("ORDER-" + i, "SKU-" + (i % products), 1));

        Set<Integer> availability = IntStream.range(0, products)
                .mapToObj(p -> service.available("SKU-" + p))
                .collect(Collectors.toSet());
        assertEquals(Set.of(0), availability);
    }

    private enum Outcome { RESERVED, SOLD_OUT }

    @FunctionalInterface
    private interface Task<T> {
        T run(int customer) throws Exception;
    }

    /** Starts all tasks at the same moment to maximise contention. */
    private <T> List<T> runConcurrently(int count, Task<T> task) throws Exception {
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int customer = i;
            Callable<T> callable = () -> {
                startSignal.await();
                return task.run(customer);
            };
            futures.add(pool.submit(callable));
        }
        startSignal.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        return results;
    }
}

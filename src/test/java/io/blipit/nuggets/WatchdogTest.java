package io.blipit.nuggets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WatchdogTest {
    final List<Runnable> mainQueue = new ArrayList<>();
    final List<Long> reports = new ArrayList<>();
    final Watchdog dog = new Watchdog(mainQueue::add, 5000, reports::add);

    void runMain() {
        List<Runnable> now = new ArrayList<>(mainQueue);
        mainQueue.clear();
        now.forEach(Runnable::run);
    }

    @Test
    void aResponsiveMainThreadIsNeverReported() {
        for (long t = 0; t <= 60_000; t += 500) {
            dog.step(t);
            runMain();
        }
        assertEquals(List.of(), reports);
    }

    @Test
    void aFiveSecondBlockIsReportedOnce() {
        dog.step(0);
        for (long t = 500; t < 5000; t += 500) dog.step(t);
        assertEquals(List.of(), reports);
        for (long t = 5000; t <= 20_000; t += 500) dog.step(t);
        assertEquals(List.of(5000L), reports);
        runMain();
        dog.step(20_500);
        runMain();
        dog.step(21_000);
        assertEquals(1, reports.size());
    }

    @Test
    void aSecondBlockAfterRecoveryIsReportedAgain() {
        dog.step(0);
        dog.step(6000);
        runMain();
        dog.step(6500);
        for (long t = 7000; t <= 12_000; t += 500) dog.step(t);
        assertEquals(List.of(6000L, 5000L), reports);
    }

    @Test
    void justUnderTheLimitIsNotAnAnr() {
        dog.step(0);
        dog.step(4999);
        runMain();
        dog.step(5500);
        assertEquals(List.of(), reports);
    }
}

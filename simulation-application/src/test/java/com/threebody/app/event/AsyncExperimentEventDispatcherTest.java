package com.threebody.app.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class AsyncExperimentEventDispatcherTest {

    private static final String EXPERIMENT_ID = "experiment";

    @Test
    void healthUsesItsOwnLatestWinsSlot() {
        ExperimentMessage health = message(ExperimentMessageType.HEALTH, 1L);
        assertEquals("HEALTH", health.mergeKey());
        assertEquals("METRICS", message(ExperimentMessageType.METRICS, 2L).mergeKey());
    }

    @Test
    void reliableMessagesRemainInSequenceOrder() throws Exception {
        try (AsyncExperimentEventDispatcher dispatcher = new AsyncExperimentEventDispatcher(16)) {
            List<Long> sequences = new CopyOnWriteArrayList<>();
            CountDownLatch delivered = new CountDownLatch(3);
            dispatcher.addListener(message -> {
                sequences.add(message.sequence());
                delivered.countDown();
            });

            dispatcher.publish(message(ExperimentMessageType.STATUS, 1));
            dispatcher.publish(message(ExperimentMessageType.ERROR, 2));
            dispatcher.publish(message(ExperimentMessageType.NEAR_ENCOUNTER, 3));

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1L, 2L, 3L), sequences);
        }
    }

    @Test
    void latestDisplayMessageReplacesStalePendingMessage() throws Exception {
        try (AsyncExperimentEventDispatcher dispatcher = new AsyncExperimentEventDispatcher(16)) {
            List<Long> sequences = new CopyOnWriteArrayList<>();
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch allDelivered = new CountDownLatch(3);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            dispatcher.addListener(message -> {
                sequences.add(message.sequence());
                if (message.sequence() == 1L) {
                    firstStarted.countDown();
                    try {
                        releaseFirst.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                allDelivered.countDown();
            });

            dispatcher.publish(message(ExperimentMessageType.SNAPSHOT, 1));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            dispatcher.publish(message(ExperimentMessageType.SNAPSHOT, 2));
            dispatcher.publish(message(ExperimentMessageType.SNAPSHOT, 3));
            dispatcher.publish(message(ExperimentMessageType.STATUS, 4));
            releaseFirst.countDown();

            assertTrue(allDelivered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1L, 3L, 4L), sequences);
        }
    }

    @Test
    void slowListenerDoesNotBlockPublisher() throws Exception {
        try (AsyncExperimentEventDispatcher dispatcher = new AsyncExperimentEventDispatcher(16)) {
            CountDownLatch started = new CountDownLatch(1);
            dispatcher.addListener(message -> {
                started.countDown();
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            long start = System.nanoTime();
            dispatcher.publish(message(ExperimentMessageType.STATUS, 1));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(elapsedMillis < 100L, "publish elapsed " + elapsedMillis + " ms");
        }
    }

    @Test
    void mailboxCapacityIsGlobalAcrossExperiments() throws Exception {
        try (AsyncExperimentEventDispatcher dispatcher = new AsyncExperimentEventDispatcher(8)) {
            List<ExperimentMessage> delivered = new CopyOnWriteArrayList<>();
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            dispatcher.addListener(message -> {
                delivered.add(message);
                if (message.sequence() == 1L) {
                    firstStarted.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            dispatcher.publish(message("a", ExperimentMessageType.STATUS, 1));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 9; i++) {
                dispatcher.publish(message(i % 2 == 0 ? "a" : "b",
                        ExperimentMessageType.STATUS, i + 2L));
            }
            release.countDown();
            Thread.sleep(150L);
            assertEquals(1, delivered.size(), "overflow detaches one slow listener globally");
        }
    }

    @Test
    void experimentsAreServedFairlyWithoutCrossExperimentStarvation() throws Exception {
        try (AsyncExperimentEventDispatcher dispatcher = new AsyncExperimentEventDispatcher(32)) {
            List<String> delivered = new CopyOnWriteArrayList<>();
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch atLeastThree = new CountDownLatch(3);
            dispatcher.addListener(message -> {
                delivered.add(message.experimentId());
                if (delivered.size() == 1) {
                    firstStarted.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                atLeastThree.countDown();
            });
            dispatcher.publish(message("a", ExperimentMessageType.STATUS, 1));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            dispatcher.publish(message("b", ExperimentMessageType.STATUS, 1));
            dispatcher.publish(message("a", ExperimentMessageType.STATUS, 2));
            dispatcher.publish(message("a", ExperimentMessageType.STATUS, 3));
            release.countDown();
            assertTrue(atLeastThree.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("a", "b", "a"), delivered.subList(0, 3));
        }
    }

    private static ExperimentMessage message(ExperimentMessageType type, long sequence) {
        return message(EXPERIMENT_ID, type, sequence);
    }

    private static ExperimentMessage message(String experimentId, ExperimentMessageType type, long sequence) {
        return new ExperimentMessage(type, experimentId, sequence, Instant.EPOCH, new Object());
    }
}

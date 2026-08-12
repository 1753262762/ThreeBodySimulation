package com.threebody.app.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-blocking event fan-out used by the application layer.
 *
 * <p>Each listener owns a mailbox. Reliable event types are retained in FIFO
 * order while high-rate display types use a latest-wins slot. A fair
 * round-robin drain serves experiments independently; within one experiment
 * the lowest pending sequence is always delivered first. A slow listener
 * therefore consumes only its own bounded mailbox and never stalls the
 * simulation worker.</p>
 */
public final class AsyncExperimentEventDispatcher implements AutoCloseable {

    public static final int DEFAULT_MAILBOX_CAPACITY = 512;

    private final ExecutorService executor;
    private final int mailboxCapacity;
    private final List<Mailbox> mailboxes = new ArrayList<>();
    private final Object mailboxLock = new Object();
    private volatile boolean closed;

    public AsyncExperimentEventDispatcher() {
        this(DEFAULT_MAILBOX_CAPACITY);
    }

    public AsyncExperimentEventDispatcher(int mailboxCapacity) {
        if (mailboxCapacity < 8) {
            throw new IllegalArgumentException("mailboxCapacity must be at least 8");
        }
        this.mailboxCapacity = mailboxCapacity;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "experiment-event-dispatcher");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newCachedThreadPool(factory);
    }

    public void addListener(ExperimentEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (mailboxLock) {
            if (!closed) {
                mailboxes.add(new Mailbox(listener));
            }
        }
    }

    public void removeListener(ExperimentEventListener listener) {
        synchronized (mailboxLock) {
            mailboxes.removeIf(mailbox -> {
                if (mailbox.listener == listener) {
                    mailbox.close();
                    return true;
                }
                return false;
            });
        }
    }

    private void detach(Mailbox mailbox) {
        synchronized (mailboxLock) {
            mailboxes.remove(mailbox);
        }
        mailbox.close();
    }

    /** Enqueues without invoking user code. */
    public void publish(ExperimentMessage message) {
        if (message == null || closed) {
            return;
        }
        Mailbox[] snapshot;
        synchronized (mailboxLock) {
            snapshot = mailboxes.toArray(Mailbox[]::new);
        }
        for (Mailbox mailbox : snapshot) {
            mailbox.offer(message);
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (mailboxLock) {
            mailboxes.forEach(Mailbox::close);
            mailboxes.clear();
        }
        executor.shutdownNow();
    }

    private final class Mailbox {
        private final ExperimentEventListener listener;
        private final Object lock = new Object();
        private final Map<String, PendingExperiment> pending = new LinkedHashMap<>();
        private final ArrayDeque<String> readyExperiments = new ArrayDeque<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private int reliableCount;
        private boolean mailboxClosed;

        private Mailbox(ExperimentEventListener listener) {
            this.listener = listener;
        }

        private void offer(ExperimentMessage message) {
            boolean schedule = false;
            boolean detach = false;
            synchronized (lock) {
                if (mailboxClosed) {
                    return;
                }
                PendingExperiment experiment = pending.get(message.experimentId());
                if (experiment == null) {
                    experiment = new PendingExperiment();
                    pending.put(message.experimentId(), experiment);
                }
                boolean wasEmpty = experiment.isEmpty();
                if (message.mergeKey() != null) {
                    experiment.latest.put(message.mergeKey(), message);
                } else {
                    if (reliableCount >= mailboxCapacity) {
                        // A listener that cannot keep up is isolated and removed;
                        // reliable events are never silently discarded.
                        mailboxClosed = true;
                        pending.clear();
                        readyExperiments.clear();
                        reliableCount = 0;
                        detach = true;
                    } else {
                        experiment.reliable.addLast(message);
                        reliableCount++;
                    }
                }
                if (!detach && wasEmpty) {
                    readyExperiments.addLast(message.experimentId());
                }
                if (!detach && scheduled.compareAndSet(false, true)) {
                    schedule = true;
                }
            }
            if (detach) {
                detach(this);
                return;
            }
            if (schedule) {
                try {
                    executor.execute(this::drain);
                } catch (RuntimeException ignored) {
                    close();
                }
            }
        }

        private void drain() {
            while (true) {
                ExperimentMessage next;
                synchronized (lock) {
                    if (mailboxClosed) {
                        scheduled.set(false);
                        return;
                    }
                    next = takeNextFair();
                    if (next == null) {
                        scheduled.set(false);
                        // Close the offer/drain race by checking once more after
                        // clearing the scheduled bit.
                        if (hasPending() && scheduled.compareAndSet(false, true)) {
                            continue;
                        }
                        return;
                    }
                }
                try {
                    listener.onMessage(next);
                } catch (RuntimeException ignored) {
                    // A listener failure must not kill the dispatcher or worker.
                }
            }
        }

        private boolean hasPending() {
            return pending.values().stream().anyMatch(item ->
                    !item.reliable.isEmpty() || !item.latest.isEmpty());
        }

        private ExperimentMessage takeNextFair() {
            while (!readyExperiments.isEmpty()) {
                String experimentId = readyExperiments.removeFirst();
                PendingExperiment pendingExperiment = pending.get(experimentId);
                if (pendingExperiment == null || pendingExperiment.isEmpty()) {
                    pending.remove(experimentId);
                    continue;
                }
                ExperimentMessage candidate = pendingExperiment.lowest();
                ExperimentMessage latest = pendingExperiment.latestMessage(candidate);
                if (latest != null) {
                    pendingExperiment.latest.remove(latest.mergeKey());
                } else {
                    pendingExperiment.reliable.removeFirst();
                    reliableCount--;
                }
                if (!pendingExperiment.isEmpty()) {
                    readyExperiments.addLast(experimentId);
                } else {
                    pending.remove(experimentId);
                }
                return candidate;
            }
            return null;
        }

        private void close() {
            synchronized (lock) {
                mailboxClosed = true;
                pending.clear();
                readyExperiments.clear();
                reliableCount = 0;
            }
        }
    }

    private static final class PendingExperiment {
        private final ArrayDeque<ExperimentMessage> reliable = new ArrayDeque<>();
        private final Map<String, ExperimentMessage> latest = new LinkedHashMap<>();

        private ExperimentMessage lowest() {
            ExperimentMessage result = reliable.peekFirst();
            for (ExperimentMessage message : latest.values()) {
                if (result == null || message.sequence() < result.sequence()) {
                    result = message;
                }
            }
            return result;
        }

        private ExperimentMessage latestMessage(ExperimentMessage candidate) {
            ExperimentMessage latestMessage = candidate.mergeKey() == null
                    ? null : latest.get(candidate.mergeKey());
            return latestMessage == candidate ? candidate : null;
        }

        private boolean isEmpty() {
            return reliable.isEmpty() && latest.isEmpty();
        }
    }
}

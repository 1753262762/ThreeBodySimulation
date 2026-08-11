package com.threebody.web.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.threebody.app.event.ExperimentEventListener;
import com.threebody.app.event.ExperimentMessage;
import com.threebody.app.event.ExperimentMessageType;
import com.threebody.app.service.ExperimentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket endpoint for {@code /ws/v1/experiments/{id}}.
 *
 * <p>The application event outlet calls {@link #onMessage(ExperimentMessage)}
 * asynchronously.  This handler only offers the immutable message to each
 * session mailbox; JSON serialization and the potentially blocking
 * {@code sendMessage} call happen on a dedicated sender executor.</p>
 */
@Component
public class ExperimentWebSocketHandler extends TextWebSocketHandler implements ExperimentEventListener,
        AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExperimentWebSocketHandler.class);

    static final int MAX_PENDING_MESSAGES = 256;
    static final long SEND_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);

    /** sessionId -> session */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** experimentId -> sessionId set */
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    private final Map<String, SessionSender> senders = new ConcurrentHashMap<>();
    private final ExecutorService senderExecutor;
    private final ScheduledExecutorService timeoutExecutor;
    private final ExperimentService service;
    private final ObjectMapper mapper;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ExperimentWebSocketHandler(ExperimentService service) {
        this.service = service;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        ThreadFactory senderFactory = runnable -> {
            Thread thread = new Thread(runnable, "websocket-sender");
            thread.setDaemon(true);
            return thread;
        };
        this.senderExecutor = Executors.newCachedThreadPool(senderFactory);
        this.timeoutExecutor = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "websocket-send-timeout");
            thread.setDaemon(true);
            return thread;
        });
        service.addEventListener(this);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (closed.get()) {
            closeQuietly(session, CloseStatus.SERVICE_RESTARTED);
            return;
        }
        String experimentId = extractExperimentId(session);
        if (experimentId == null) {
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }

        SessionSender sender = new SessionSender(session);
        sessions.put(session.getId(), session);
        senders.put(session.getId(), sender);
        subscriptions.computeIfAbsent(experimentId, ignored -> new CopyOnWriteArraySet<>())
                .add(session.getId());
        log.info("WebSocket 已连接：experiment={} session={}", experimentId, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session);
        log.info("WebSocket 已断开：experiment={} session={}", extractExperimentId(session), session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket transport error: session={}", session.getId(), exception);
        removeSession(session);
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    /** Called by the asynchronous application dispatcher; never blocks on a client. */
    @Override
    public void onMessage(ExperimentMessage message) {
        if (closed.get()) {
            return;
        }
        Set<String> subscribed = subscriptions.get(message.experimentId());
        if (subscribed == null || subscribed.isEmpty()) {
            return;
        }
        for (String sessionId : subscribed.toArray(String[]::new)) {
            SessionSender sender = senders.get(sessionId);
            if (sender != null) {
                sender.offer(message);
            }
        }
    }

    private void removeSession(WebSocketSession session) {
        sessions.remove(session.getId(), session);
        SessionSender sender = senders.remove(session.getId());
        if (sender != null) {
            sender.close(false, null);
        }
        String experimentId = extractExperimentId(session);
        if (experimentId != null) {
            Set<String> subscribed = subscriptions.get(experimentId);
            if (subscribed != null) {
                subscribed.remove(session.getId());
                if (subscribed.isEmpty()) {
                    subscriptions.remove(experimentId, subscribed);
                }
            }
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
            // Session is already closed or transport failed.
        }
    }

    private String extractExperimentId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }
        String path = uri.getPath();
        if (path == null) {
            return null;
        }
        String[] segments = path.split("/");
        if (segments.length >= 4 && "experiments".equals(segments[segments.length - 2])) {
            return segments[segments.length - 1];
        }
        return null;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        service.removeEventListener(this);
        for (SessionSender sender : senders.values()) {
            sender.close(true, CloseStatus.SERVICE_RESTARTED);
        }
        senders.clear();
        sessions.clear();
        subscriptions.clear();
        timeoutExecutor.shutdownNow();
        senderExecutor.shutdownNow();
    }

    private final class SessionSender {
        private final WebSocketSession session;
        private final Object lock = new Object();
        private final ArrayDeque<ExperimentMessage> reliable = new ArrayDeque<>();
        private final EnumMap<ExperimentMessageType, ExperimentMessage> latest =
                new EnumMap<>(ExperimentMessageType.class);
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean senderClosed = new AtomicBoolean();
        private final AtomicLong sendGeneration = new AtomicLong();

        private SessionSender(WebSocketSession session) {
            this.session = session;
        }

        private void offer(ExperimentMessage message) {
            boolean schedule = false;
            synchronized (lock) {
                if (senderClosed.get() || !session.isOpen()) {
                    return;
                }
                if (isLatestType(message.type())) {
                    latest.put(message.type(), message);
                } else {
                    if (pendingCount() >= MAX_PENDING_MESSAGES) {
                        close(true, CloseStatus.POLICY_VIOLATION);
                        return;
                    }
                    reliable.addLast(message);
                }
                if (scheduled.compareAndSet(false, true)) {
                    schedule = true;
                }
            }
            if (schedule) {
                try {
                    senderExecutor.execute(this::drain);
                } catch (RuntimeException ex) {
                    close(true, CloseStatus.SERVER_ERROR);
                }
            }
        }

        private void drain() {
            while (true) {
                ExperimentMessage message;
                synchronized (lock) {
                    if (senderClosed.get()) {
                        scheduled.set(false);
                        return;
                    }
                    message = takeLowestSequence();
                    if (message == null) {
                        scheduled.set(false);
                        if (hasPending() && scheduled.compareAndSet(false, true)) {
                            continue;
                        }
                        return;
                    }
                }
                send(message);
            }
        }

        private void send(ExperimentMessage message) {
            if (senderClosed.get() || !session.isOpen()) {
                return;
            }
            final long generation = sendGeneration.incrementAndGet();
            timeoutExecutor.schedule(() -> {
                if (!senderClosed.get() && sendGeneration.get() == generation
                        && System.nanoTime() - sendStartedAt >= SEND_TIMEOUT_NANOS) {
                    log.warn("Closing slow WebSocket client: session={}", session.getId());
                    close(true, CloseStatus.SESSION_NOT_RELIABLE);
                }
            }, SEND_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);

            sendStartedAt = System.nanoTime();
            try {
                Map<String, Object> envelope = new java.util.LinkedHashMap<>();
                envelope.put("schemaVersion", "1.0");
                envelope.put("type", message.type().name());
                envelope.put("experimentId", message.experimentId());
                envelope.put("sequence", message.sequence());
                envelope.put("timestamp", message.timestamp().toString());
                envelope.put("payload", message.payload());
                String json = mapper.writeValueAsString(envelope);
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(json));
                    }
                }
            } catch (Exception ex) {
                log.warn("WebSocket send failed: session={}", session.getId(), ex);
                close(true, CloseStatus.SERVER_ERROR);
            } finally {
                sendGeneration.compareAndSet(generation, 0L);
            }
        }

        private volatile long sendStartedAt;

        private ExperimentMessage takeLowestSequence() {
            ExperimentMessage result = reliable.peekFirst();
            ExperimentMessage latestResult = null;
            for (ExperimentMessage candidate : latest.values()) {
                if (result == null || candidate.sequence() < result.sequence()) {
                    result = candidate;
                    latestResult = candidate;
                }
            }
            if (result == null) {
                return null;
            }
            if (latestResult != null) {
                latest.remove(latestResult.type());
            } else {
                reliable.removeFirst();
            }
            return result;
        }

        private boolean hasPending() {
            synchronized (lock) {
                return !reliable.isEmpty() || !latest.isEmpty();
            }
        }

        private int pendingCount() {
            return reliable.size() + latest.size();
        }

        private boolean isLatestType(ExperimentMessageType type) {
            return type == ExperimentMessageType.SNAPSHOT
                    || type == ExperimentMessageType.METRICS
                    || type == ExperimentMessageType.TRAJECTORY;
        }

        private void close(boolean closeSession, CloseStatus status) {
            if (!senderClosed.compareAndSet(false, true)) {
                return;
            }
            synchronized (lock) {
                reliable.clear();
                latest.clear();
                scheduled.set(false);
            }
            // Remove our own routing entry before closing the transport. The
            // close callback is not guaranteed to run (notably on queue
            // overflow or a blocked send), so cleanup cannot depend on it.
            if (closeSession) {
                removeSession(session);
            }
            if (closeSession && status != null) {
                closeQuietly(session, status);
            }
        }
    }
}

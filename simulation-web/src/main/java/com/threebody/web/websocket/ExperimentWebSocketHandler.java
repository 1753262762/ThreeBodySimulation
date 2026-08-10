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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 原生 WebSocket 处理器，对应 /ws/v1/experiments/{id}。
 * 序列号单调递增，客户端必须丢弃 <= 已处理值的消息。
 */
@Component
public class ExperimentWebSocketHandler extends TextWebSocketHandler implements ExperimentEventListener {

    private static final Logger log = LoggerFactory.getLogger(ExperimentWebSocketHandler.class);

    /** sessionId -> session */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** experimentId -> sessionId 集合 */
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    private final ExperimentService service;
    private final ObjectMapper mapper;

    public ExperimentWebSocketHandler(ExperimentService service) {
        this.service = service;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        service.addEventListener(this);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String experimentId = extractExperimentId(session);
        if (experimentId == null) {
            try { session.close(CloseStatus.BAD_DATA); } catch (IOException ignored) {}
            return;
        }

        sessions.put(session.getId(), session);
        subscriptions.computeIfAbsent(experimentId, k -> new CopyOnWriteArraySet<>())
                .add(session.getId());
        log.info("WebSocket 已连接：experiment={} session={}", experimentId, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String experimentId = extractExperimentId(session);
        sessions.remove(session.getId());
        if (experimentId != null) {
            Set<String> subs = subscriptions.get(experimentId);
            if (subs != null) {
                subs.remove(session.getId());
                if (subs.isEmpty()) {
                    subscriptions.remove(experimentId);
                }
            }
        }
        log.info("WebSocket 已断开：experiment={} session={}", experimentId, session.getId());
    }

    @Override
    public void onMessage(ExperimentMessage message) {
        Set<String> subs = subscriptions.get(message.experimentId());
        if (subs == null || subs.isEmpty()) return;

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", "1.0");
        envelope.put("type", message.type().name());
        envelope.put("experimentId", message.experimentId());
        envelope.put("sequence", message.sequence());
        envelope.put("timestamp", message.timestamp().toString());
        envelope.put("payload", message.payload());

        try {
            String json = mapper.writeValueAsString(envelope);
            TextMessage textMessage = new TextMessage(json);

            for (String sessionId : subs) {
                WebSocketSession session = sessions.get(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        synchronized (session) {
                            session.sendMessage(textMessage);
                        }
                    } catch (IOException e) {
                        log.warn("WebSocket 发送失败：session={}", sessionId, e);
                        try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            log.error("WebSocket 消息序列化失败", e);
        }
    }

    private String extractExperimentId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        String path = uri.getPath();
        // /ws/v1/experiments/{id}
        String[] segments = path.split("/");
        if (segments.length >= 4 && "experiments".equals(segments[segments.length - 2])) {
            return segments[segments.length - 1];
        }
        return null;
    }
}

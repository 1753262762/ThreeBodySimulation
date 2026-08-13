package com.threebody.web.websocket;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threebody.app.domain.EventPhase;
import com.threebody.app.domain.SimulationEvent;
import com.threebody.app.domain.SimulationEventType;
import com.threebody.app.event.ExperimentMessage;
import com.threebody.app.event.ExperimentMessageType;
import com.threebody.app.service.ExperimentService;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class ExperimentWebSocketHandlerTest {

    @Test
    void slowClientIsBoundedAndClosedWhenReliableQueueFills() throws Exception {
        ExperimentService service = mock(ExperimentService.class);
        ExperimentWebSocketHandler handler = new ExperimentWebSocketHandler(service);
        WebSocketSession session = mock(WebSocketSession.class);
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        when(session.getId()).thenReturn("session-1");
        when(session.getUri()).thenReturn(URI.create("/ws/v1/experiments/experiment"));
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            sendStarted.countDown();
            releaseSend.await(2, TimeUnit.SECONDS);
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        try {
            handler.afterConnectionEstablished(session);
            handler.onMessage(message(1L));
            assertTrue(sendStarted.await(2, TimeUnit.SECONDS));
            for (long sequence = 2L;
                    sequence <= ExperimentWebSocketHandler.MAX_PENDING_MESSAGES + 2L;
                    sequence++) {
                handler.onMessage(message(sequence));
            }

            verify(session, timeout(2_000L)).close(any(CloseStatus.class));
        } finally {
            releaseSend.countDown();
            handler.close();
        }
    }

    @Test
    void nearEncounterIsWrappedAsEventWithSchemaVersion11() throws Exception {
        ExperimentService service = mock(ExperimentService.class);
        ExperimentWebSocketHandler handler = new ExperimentWebSocketHandler(service);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.getUri()).thenReturn(URI.create("/ws/v1/experiments/experiment"));
        when(session.isOpen()).thenReturn(true);
        AtomicReference<String> jsonRef = new AtomicReference<>();
        doAnswer(invocation -> {
            jsonRef.set(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        try {
            handler.afterConnectionEstablished(session);
            SimulationEvent event = new SimulationEvent(
                    1L, "event-1", SimulationEventType.NEAR_ENCOUNTER, EventPhase.ENTER,
                    10L, 100.0, Instant.EPOCH, "甲 与 乙 进入近距离。",
                    java.util.List.of("a", "b"), 1.0e6, 5.0e6, 1.0e6, 1.0e6, 10L, 100.0, null, null);
            handler.onMessage(new ExperimentMessage(
                    ExperimentMessageType.NEAR_ENCOUNTER, "experiment", 5L,
                    Instant.EPOCH, event, null));
            assertTrue(waitForJson(jsonRef));

            ObjectMapper mapper = new ObjectMapper();
            var envelope = mapper.readTree(jsonRef.get());
            org.junit.jupiter.api.Assertions.assertEquals("1.1", envelope.get("schemaVersion").asText());
            org.junit.jupiter.api.Assertions.assertEquals("NEAR_ENCOUNTER", envelope.get("type").asText());
            org.junit.jupiter.api.Assertions.assertEquals("event-1",
                    envelope.get("payload").get("event").get("eventId").asText());
            org.junit.jupiter.api.Assertions.assertEquals("ENTER",
                    envelope.get("payload").get("event").get("phase").asText());
        } finally {
            handler.close();
        }
    }

    @Test
    void healthIsSerializedAsIndependentVersion11Message() throws Exception {
        ExperimentService service = mock(ExperimentService.class);
        ExperimentWebSocketHandler handler = new ExperimentWebSocketHandler(service);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-health");
        when(session.getUri()).thenReturn(URI.create("/ws/v1/experiments/experiment"));
        when(session.isOpen()).thenReturn(true);
        AtomicReference<String> jsonRef = new AtomicReference<>();
        doAnswer(invocation -> {
            jsonRef.set(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        try {
            handler.afterConnectionEstablished(session);
            handler.onMessage(new ExperimentMessage(ExperimentMessageType.HEALTH,
                    "experiment", 8L, Instant.EPOCH, Map.of("status", "WARNING")));
            assertTrue(waitForJson(jsonRef));
            var envelope = new ObjectMapper().readTree(jsonRef.get());
            org.junit.jupiter.api.Assertions.assertEquals("1.1", envelope.get("schemaVersion").asText());
            org.junit.jupiter.api.Assertions.assertEquals("HEALTH", envelope.get("type").asText());
            org.junit.jupiter.api.Assertions.assertEquals("WARNING",
                    envelope.get("payload").get("status").asText());
        } finally {
            handler.close();
        }
    }

    private static boolean waitForJson(AtomicReference<String> ref) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (ref.get() != null) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static ExperimentMessage message(long sequence) {
        return new ExperimentMessage(
                ExperimentMessageType.STATUS,
                "experiment",
                sequence,
                Instant.EPOCH,
                Map.of("status", "RUNNING"));
    }
}

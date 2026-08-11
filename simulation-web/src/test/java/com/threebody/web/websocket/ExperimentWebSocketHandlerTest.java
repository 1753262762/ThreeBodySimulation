package com.threebody.web.websocket;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.threebody.app.event.ExperimentMessage;
import com.threebody.app.event.ExperimentMessageType;
import com.threebody.app.service.ExperimentService;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    private static ExperimentMessage message(long sequence) {
        return new ExperimentMessage(
                ExperimentMessageType.STATUS,
                "experiment",
                sequence,
                Instant.EPOCH,
                Map.of("status", "RUNNING"));
    }
}

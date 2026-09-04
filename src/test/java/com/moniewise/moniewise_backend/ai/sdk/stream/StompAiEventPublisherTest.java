package com.moniewise.moniewise_backend.ai.sdk.stream;

import com.moniewise.monnie.api.event.AiEventFrame;
import com.moniewise.moniewise_backend.ai.sdk.MonnieSdkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StompAiEventPublisherTest {

    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final StompAiEventPublisher publisher =
            new StompAiEventPublisher(messaging, new MonnieSdkProperties());

    @Test
    void framesGoToTheOwnersUserQueue() {
        AiEventFrame frame = frame();

        publisher.publish("ada@example.com", frame);

        verify(messaging).convertAndSendToUser("ada@example.com", "/queue/ai", frame);
    }

    @Test
    void theAdvertisedDestinationIsTheOneAClientSubscribesTo() {
        // The broker destination and the client's subscription differ by Spring's user
        // prefix. Advertising the raw destination would have every client subscribe to a
        // shared queue and see nothing.
        assertEquals("/user/queue/ai", publisher.userDestination());
    }

    @Test
    void aBrokerFailureNeverReachesTheCaller() {
        // The caller is either mid-turn on an SDK worker thread or on the confirm request
        // thread after money has moved. A dropped frame must not become a failed turn or
        // a 500 on a transfer that already succeeded.
        doThrow(new IllegalStateException("broker down"))
                .when(messaging).convertAndSendToUser(anyString(), anyString(), any(Object.class));

        assertDoesNotThrow(() -> publisher.publish("ada@example.com", frame()));
    }

    @Test
    void anUnknownRecipientIsDroppedRatherThanBroadcast() {
        publisher.publish(null, frame());
        publisher.publish("  ", frame());

        verify(messaging, never()).convertAndSendToUser(anyString(), eq("/queue/ai"),
                any(Object.class));
    }

    private static AiEventFrame frame() {
        return AiEventFrame.of(AiEventFrame.Type.TURN_END, "turn-1", "thread-1", 3,
                System.currentTimeMillis(),
                new AiEventFrame.TurnEnd("turn-1", "OK", 1200, java.util.Map.of()));
    }
}

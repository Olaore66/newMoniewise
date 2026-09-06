package com.moniewise.moniewise_backend.ai.sdk.stream;

import com.moniewise.monnie.api.event.AiEventFrame;
import com.moniewise.moniewise_backend.ai.sdk.MonnieSdkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Delivers turn frames to one user over the existing STOMP broker.
 *
 * <p>{@code WebSocketAuthChannelInterceptor} sets the STOMP principal from the JWT, and
 * its name is the user's email, so {@code convertAndSendToUser(email, ...)} routes to
 * exactly that user's sessions. The client subscribes to
 * {@code /user/queue/ai} - Spring's user destination prefix turns the per-user
 * {@code /queue/ai} into a session-specific queue.
 */
@Component
public class StompAiEventPublisher implements AiEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(StompAiEventPublisher.class);

    private final SimpMessagingTemplate messaging;
    private final String destination;

    public StompAiEventPublisher(SimpMessagingTemplate messaging, MonnieSdkProperties properties) {
        this.messaging = messaging;
        this.destination = properties.getPush().getDestination();
    }

    @Override
    public void publish(String email, AiEventFrame frame) {
        if (email == null || email.isBlank() || frame == null) {
            return;
        }
        try {
            messaging.convertAndSendToUser(email, destination, frame);
        } catch (RuntimeException e) {
            // Never propagate: the caller is either mid-turn on an SDK worker thread or
            // on the confirm request thread, and in both cases a lost frame is far less
            // bad than a failed turn or a transfer whose HTTP response 500s after the
            // money has already moved.
            log.warn("Dropped {} frame for a chat client: {}", frame.type(), e.toString());
        }
    }

    @Override
    public String userDestination() {
        return "/user" + destination;
    }
}

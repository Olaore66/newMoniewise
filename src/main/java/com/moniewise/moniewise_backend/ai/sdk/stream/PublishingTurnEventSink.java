package com.moniewise.moniewise_backend.ai.sdk.stream;

import com.moniewise.monnie.api.event.AiEventFrame;
import com.moniewise.monnie.api.event.TurnEventSink;

/**
 * Streams a turn's frames to one user as the SDK produces them.
 *
 * <p>Handed to {@code Monnie.submit}, so every frame from {@code THREAD_CREATED} through
 * the single terminal {@code TURN_END} is pushed live. The turn runs on an SDK worker
 * thread, so the user's identity is captured here at construction rather than read from
 * {@code SecurityContextHolder}, which is bound to the request thread and long gone by
 * the time most frames are emitted.
 */
public final class PublishingTurnEventSink implements TurnEventSink {

    private final String email;
    private final AiEventPublisher publisher;

    public PublishingTurnEventSink(String email, AiEventPublisher publisher) {
        this.email = email;
        this.publisher = publisher;
    }

    @Override
    public void emit(AiEventFrame frame) {
        publisher.publish(email, frame);
    }
}

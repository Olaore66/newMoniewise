package com.moniewise.moniewise_backend.ai.sdk.stream;

import com.moniewise.monnie.api.event.AiEventFrame;

/**
 * Where a turn's frames go on their way to one user.
 *
 * <p>An interface rather than a direct {@code SimpMessagingTemplate} call so the wire
 * transport is a single swappable bean. Today that is STOMP over the existing
 * {@code /ws} broker; an SSE or push-notification transport would implement this and
 * change nothing in {@code AiSdkService}.
 *
 * <p><b>Implementations must not throw.</b> A frame that cannot be delivered - a closed
 * socket, a client that never subscribed - must not abort the turn: the answer is still
 * being generated and still needs persisting. Log and drop. This mirrors the contract
 * the SDK states on {@code TurnEventSink}.
 */
public interface AiEventPublisher {

    void publish(String email, AiEventFrame frame);

    /** The client-facing destination frames arrive on, for {@code /ai/sdk/status}. */
    String userDestination();
}

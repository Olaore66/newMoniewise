package com.moniewise.moniewise_backend.dto.response.ai;

/**
 * A turn that was accepted and is now running; frames arrive over the socket.
 *
 * <p>Returned with 202. The two client ids are echoed on every frame of this turn, so a
 * client with several conversations open can attribute each frame without guessing.
 *
 * @param destination where to subscribe, already carrying the {@code /user} prefix
 */
public record AiSdkTurnAcceptedResponse(
        String threadId,
        String clientMessageId,
        String clientRequestId,
        String destination) {
}

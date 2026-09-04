package com.moniewise.moniewise_backend.dto.response.ai;

import java.util.List;

/**
 * Whether the agent is available, and the numbers a client needs before it calls anything.
 *
 * <p>Answers even when the agent is switched off, which is the point: a client checks
 * this to decide whether to show the assistant at all, rather than discovering a 503 at
 * the moment a user taps it.
 *
 * @param provider and {@code model} are null while disabled
 */
public record AiSdkStatusResponse(
        boolean enabled,
        String provider,
        String model,
        int protocolVersion,
        List<String> surfaces,
        Limits limits,
        Transports transports) {

    /**
     * @param maxInstructionLength characters, after normalisation
     * @param historyLimit prior messages replayed into each turn
     * @param turnBudgetSeconds wall clock before a turn is stopped and the user told so
     */
    public record Limits(
            int maxInstructionLength,
            int maxTurnTextLength,
            int maxThreadPageSize,
            int maxMessagePageSize,
            int maxActionPageSize,
            Integer historyLimit,
            Integer turnBudgetSeconds) {
    }

    /**
     * @param buffered waits for the whole answer and returns it
     * @param async returns 202 immediately; frames arrive on {@code userQueue}
     * @param stompEndpoint the SockJS/STOMP handshake path
     */
    public record Transports(
            String buffered,
            String async,
            String stompEndpoint,
            String userQueue) {
    }
}

package com.moniewise.moniewise_backend.dto.response.ai;

import java.time.Instant;

/**
 * One conversation.
 *
 * @param instructions the guidance in force. Echoed because the caller owns this thread
 *     and a developer building an onboarding flow needs to see what is actually applied,
 *     not merely that something is
 * @param lastMessage preview for an inbox listing; null when the thread is fetched alone
 */
public record AiSdkThreadResponse(
        String id,
        String surface,
        String title,
        String instructions,
        boolean hasInstructions,
        Instant createdAt,
        Instant updatedAt,
        String lastMessage) {
}

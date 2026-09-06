package com.moniewise.moniewise_backend.dto.response.ai;

import java.time.Instant;

/**
 * One persisted message.
 *
 * @param id monotonic; pass the highest you have seen as {@code ?after=} to page forward
 * @param role {@code USER} or {@code ASSISTANT}
 */
public record AiSdkMessageResponse(
        Long id,
        String turnId,
        String role,
        String text,
        Instant createdAt) {
}

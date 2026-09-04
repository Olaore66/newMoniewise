package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.Size;

/**
 * Opens a conversation up front, so a screen can set its guidance before the user types.
 *
 * <p>Optional: {@code POST /ai/sdk/turn} without a {@code threadId} creates one too.
 */
public record AiSdkCreateThreadRequest(
        @Size(max = 32) String surface,
        @Size(max = 120) String title,
        @Size(max = 2000) String instructions
) {
    public static AiSdkCreateThreadRequest empty() {
        return new AiSdkCreateThreadRequest(null, null, null);
    }
}

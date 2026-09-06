package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.Size;

/**
 * Renames a thread, or sets its guidance while it still has none.
 *
 * <p>Instructions are write-once. Sending them for a thread that already has some is a
 * 400: guidance is configuration set when a conversation opens, and a conversation whose
 * specification can change underneath it is not one a user can reason about.
 */
public record AiSdkPatchThreadRequest(
        @Size(max = 120) String title,
        @Size(max = 2000) String instructions
) {
    public static AiSdkPatchThreadRequest empty() {
        return new AiSdkPatchThreadRequest(null, null);
    }
}

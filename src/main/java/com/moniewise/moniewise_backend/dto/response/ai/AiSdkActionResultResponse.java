package com.moniewise.moniewise_backend.dto.response.ai;

import com.moniewise.monnie.api.event.AiEventFrame;

/**
 * The outcome of a confirm or cancel.
 *
 * @param resultFrame the same {@code ACTION_RESULT} frame that was pushed to the user's
 *     queue, repeated here so a client that never opened a socket still gets the receipt
 *     from the HTTP response alone
 */
public record AiSdkActionResultResponse(
        AiSdkActionResponse action,
        AiEventFrame resultFrame) {
}

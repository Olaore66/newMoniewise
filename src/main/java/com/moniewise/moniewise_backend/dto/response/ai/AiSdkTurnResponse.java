package com.moniewise.moniewise_backend.dto.response.ai;

import com.moniewise.monnie.api.event.AiEventFrame;

import java.util.List;

/**
 * A completed turn, for callers that waited for the whole answer.
 *
 * @param finishReason {@code OK}, {@code ABORTED}, {@code ERROR}, {@code RATE_LIMITED},
 *     {@code BUDGET_EXCEEDED} or {@code BUSY}. A turn that failed still returns a result,
 *     with {@code text} carrying what the user should be told
 * @param frames every frame the turn produced, in order, for a client that wants the
 *     tool and action detail without holding a socket open
 */
public record AiSdkTurnResponse(
        String threadId,
        String turnId,
        String text,
        String finishReason,
        int actionsProposed,
        List<AiEventFrame> frames) {
}

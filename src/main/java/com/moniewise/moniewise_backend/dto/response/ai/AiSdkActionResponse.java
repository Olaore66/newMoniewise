package com.moniewise.moniewise_backend.dto.response.ai;

import com.moniewise.monnie.api.action.RenderSpec;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A mutation the agent has proposed and the user has not yet authorised.
 *
 * <p>{@code render} is the whole confirmation card, described declaratively: the client
 * draws its rows rather than mapping the kind to a hand-written screen, so a new action
 * kind needs no client release. Every displayed figure in it was rendered server-side.
 *
 * @param label short human name for the kind, suitable for a confirm button
 * @param editableFields the only fields a confirm request may send edits for
 */
public record AiSdkActionResponse(
        String id,
        String threadId,
        String kind,
        String label,
        String riskTier,
        String status,
        boolean requiresPin,
        boolean movesMoney,
        String paramsHash,
        Map<String, Object> params,
        List<String> editableFields,
        RenderSpec render,
        Instant createdAt,
        Instant expiresAt,
        Instant resolvedAt,
        String failureCode,
        String executionRef) {
}

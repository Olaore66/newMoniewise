package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.action.ActionKind;

import java.util.Set;

/**
 * What the running agent was configured with, for {@code GET /ai/sdk/status}.
 *
 * <p>Present only when the agent started, so its absence is itself the signal that the
 * SDK is disabled or has no API key.
 */
public record SdkRuntimeInfo(
        String provider,
        String modelId,
        int historyLimit,
        int turnBudgetSeconds,
        Set<ActionKind> enabledKinds) {
}

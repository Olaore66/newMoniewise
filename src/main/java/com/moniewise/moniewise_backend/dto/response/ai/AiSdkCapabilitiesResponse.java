package com.moniewise.moniewise_backend.dto.response.ai;

import java.util.List;

/**
 * Everything the agent can do, so a frontend developer can write guidance against a real
 * list instead of guessing.
 *
 * <p>This is the counterpart to {@code instructions}: guidance <em>selects from</em> what
 * is here. Asking the agent for something absent from this response does not enable it,
 * however the request is phrased, because the tool catalog is fixed at startup and
 * {@code ActionKind} is a closed enum.
 */
public record AiSdkCapabilitiesResponse(
        List<ToolSummary> reads,
        List<ActionSummary> actions,
        List<SurfaceSummary> surfaces,
        InstructionContract instructions) {

    /** Something the agent can look up on its own, with no confirmation. */
    public record ToolSummary(
            String name,
            String domain,
            String description) {
    }

    /**
     * A mutation the agent may propose.
     *
     * <p>Proposing is all it can do. Every one of these becomes a card the user must
     * confirm in a separate request, and {@code requiresPin} ones need the transaction
     * PIN with it.
     *
     * @param riskTier {@code LOW}, {@code CONFIG}, {@code MONEY_MOVE} or {@code MONEY_OUT}
     */
    public record ActionSummary(
            String kind,
            String label,
            String riskTier,
            boolean requiresPin,
            boolean movesMoney,
            String toolName) {
    }

    /** @param serverGuidance the starter this surface always prepends; null for CHAT */
    public record SurfaceSummary(
            String name,
            String serverGuidance) {
    }

    /**
     * The rules a client's {@code instructions} are held to.
     *
     * @param cannot the specific things guidance is unable to change, each enforced in
     *     code rather than by the prompt
     */
    public record InstructionContract(
            int maxLength,
            boolean writeOnce,
            String placement,
            String preamble,
            List<String> cannot) {
    }
}

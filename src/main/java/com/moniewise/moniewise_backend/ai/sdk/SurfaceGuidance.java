package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.core.turn.TurnRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Short server-owned starters per surface. Client addenda append after these.
 *
 * <p>A surface says <em>where</em> the conversation is happening. It is the coarse half
 * of the specification; the client's {@code instructions} are the fine half. Both are
 * additive: neither can replace the SDK's plan prompt or reach a capability the tool
 * catalog does not already expose.
 */
final class SurfaceGuidance {

    /**
     * Every surface the API accepts.
     *
     * <p>An unknown surface is rejected rather than silently defaulted. The failure it
     * prevents is quiet and annoying: a client sending {@code "onboarding-v2"} would get
     * no starter, would not match its own list filter, and would look like the guidance
     * feature simply did not work.
     */
    static final Set<String> KNOWN = Set.of(
            TurnRequest.SURFACE_CHAT,
            TurnRequest.SURFACE_BUDGET_ASSISTANT,
            TurnRequest.SURFACE_DASHBOARD,
            TurnRequest.SURFACE_ONBOARDING);

    /**
     * Stable display order for {@code /ai/sdk/status} and {@code /ai/sdk/capabilities}.
     *
     * <p>A {@link List} rather than a {@code Set}: {@code Set.copyOf} does not preserve
     * insertion order, so an ordered set constant would quietly shuffle between JVM runs
     * and make the documented surface list look unstable.
     */
    static final List<String> ORDERED = List.of(
            TurnRequest.SURFACE_CHAT,
            TurnRequest.SURFACE_ONBOARDING,
            TurnRequest.SURFACE_DASHBOARD,
            TurnRequest.SURFACE_BUDGET_ASSISTANT);

    private SurfaceGuidance() {
    }

    /**
     * Canonicalises a client-supplied surface.
     *
     * @param raw null or blank means {@link TurnRequest#SURFACE_CHAT}
     * @throws ResponseStatusException 400 if the value is not a known surface
     */
    static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return TurnRequest.SURFACE_CHAT;
        }
        String candidate = raw.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN.contains(candidate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown surface '" + raw + "'. Expected one of " + ORDERED + ".");
        }
        return candidate;
    }

    /** The starter text for every known surface, in display order. */
    static Map<String, String> catalogue() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String surface : ORDERED) {
            out.put(surface, forSurface(surface));
        }
        return out;
    }

    /**
     * Canonicalises a surface used only to filter a listing.
     *
     * <p>Blank means "every surface" here, which is why this cannot just call
     * {@link #normalise(String)} - defaulting a filter to CHAT would hide most threads.
     */
    static String normaliseFilter(String raw) {
        return raw == null || raw.isBlank() ? null : normalise(raw);
    }

    static String forSurface(String surface) {
        if (surface == null || surface.isBlank()) {
            return null;
        }
        return switch (surface.trim().toUpperCase(Locale.ROOT)) {
            case TurnRequest.SURFACE_ONBOARDING ->
                    "The user is new. Help them create a first budget and envelopes. "
                            + "Ask one question at a time. Do not push withdrawals or transfers.";
            case TurnRequest.SURFACE_DASHBOARD ->
                    "The user is on the home screen. Be brief. Prefer a single next action.";
            case TurnRequest.SURFACE_BUDGET_ASSISTANT ->
                    "Help refine this budget. One question at a time. Do not invent envelope amounts.";
            default -> null;
        };
    }
}

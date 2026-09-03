package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.core.turn.TurnRequest;

import java.util.Locale;

/**
 * Short server-owned starters per surface. Client addenda append after these.
 */
final class SurfaceGuidance {

    private SurfaceGuidance() {
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

package com.moniewise.moniewise_backend.ai.sdk;

import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Client instruction addenda are capped and never replace the SDK plan prompt.
 */
final class InstructionPolicy {

    static final int MAX_LENGTH = 2000;

    private InstructionPolicy() {
    }

    /**
     * @return trimmed instructions, or {@code null} when absent
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.replace('\u0000', ' ').trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "instructions must be at most " + MAX_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * Server surface starter plus optional client addendum.
     */
    static String compose(String surface, String clientInstructions) {
        String starter = SurfaceGuidance.forSurface(surface);
        String client = sanitize(clientInstructions);
        if (starter == null || starter.isBlank()) {
            return client;
        }
        if (client == null) {
            return starter;
        }
        return starter + "\n\n" + client;
    }

    /**
     * First non-blank addendum wins. Later turn bodies cannot swap it.
     */
    static boolean acceptIncoming(String existing, String incoming) {
        return incoming != null && (existing == null || existing.isBlank());
    }
}

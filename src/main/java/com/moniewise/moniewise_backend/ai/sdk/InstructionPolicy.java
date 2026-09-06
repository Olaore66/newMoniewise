package com.moniewise.moniewise_backend.ai.sdk;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Client instruction addenda are capped and never replace the SDK plan prompt.
 *
 * <h2>What an addendum is, and what it deliberately is not</h2>
 *
 * A frontend developer can specialise the agent for a screen - "you are guiding a new
 * user through onboarding, ask one question at a time" - by supplying {@code
 * instructions}. That text is a <b>specification chosen from what the agent can already
 * do</b>, not a new capability and not a replacement personality.
 *
 * <p>Three separate mechanisms make that true, and only the third is in this class:
 *
 * <ol>
 *   <li>The SDK appends the addendum <em>after</em> the cacheable plan prompt
 *       ({@code AgentLoop.run}), so the base prompt is always present and always first.
 *   <li>The SDK wraps it in a preamble stating it cannot waive PIN, skip tools, invent
 *       balances or execute money ({@code Prompts.surfaceAddendum}).
 *   <li>This class bounds and cleans the text before it ever gets that far.
 * </ol>
 *
 * <p>There is deliberately <b>no keyword blocklist</b> here. Filtering for phrases like
 * "ignore previous instructions" is trivially bypassed by rewording and would imply a
 * guarantee this layer cannot make. The real guarantee is structural and lives in code
 * the prompt cannot reach: {@code ActionKind} is a closed enum, every mutation needs a
 * separate PIN-authenticated confirm request, the tool catalog is fixed at startup, and
 * {@code FigureFabricationGuard} fails closed. An addendum that asks for something
 * outside that set does not get it, however it is phrased.
 */
final class InstructionPolicy {

    static final int MAX_LENGTH = 2000;

    /**
     * Control characters other than newline and tab.
     *
     * <p>Stripped because they serve no purpose in guidance text and because a stray
     * carriage return or NUL in the middle of a system message is a cheap way to make a
     * prompt render differently than it reads in a code review.
     */
    private static final String CONTROL_CHARS = "[\\p{Cntrl}&&[^\\n\\t]]";

    private InstructionPolicy() {
    }

    /**
     * Normalises and bounds a client addendum.
     *
     * <p>The length check runs last, on the cleaned text, so a caller cannot be rejected
     * for characters that were about to be removed anyway.
     *
     * @return trimmed instructions, or {@code null} when absent
     * @throws ResponseStatusException 400 when the cleaned text exceeds {@link #MAX_LENGTH}
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll(CONTROL_CHARS, " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "instructions must be at most " + MAX_LENGTH + " characters");
        }
        return cleaned;
    }

    /**
     * Server surface starter plus optional client addendum.
     *
     * <p>Order matters: the server's own guidance goes first, so a client addendum reads
     * as a refinement of it rather than as a competing instruction.
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
     *
     * <p>A thread's guidance is configuration, set when the conversation is opened. If a
     * later message could rewrite it, any message in the thread would be able to
     * re-specify the assistant mid-conversation - which is the same capability an
     * injected instruction would want. Changing guidance means starting a new thread.
     */
    static boolean acceptIncoming(String existing, String incoming) {
        return incoming != null && (existing == null || existing.isBlank());
    }
}

package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.action.ActionExecutor;
import com.moniewise.monnie.api.action.ActionKind;
import com.moniewise.monnie.api.action.ActionStatus;
import com.moniewise.monnie.api.action.PreparedAction;
import com.moniewise.monnie.api.event.AiEventFrame;
import com.moniewise.monnie.api.event.TurnEventSink;
import com.moniewise.monnie.api.model.Secret;
import com.moniewise.monnie.api.model.UserRef;
import com.moniewise.monnie.api.port.RateLimiterPort;
import com.moniewise.monnie.core.Monnie;
import com.moniewise.monnie.core.action.ConfirmationService;
import com.moniewise.monnie.core.tool.MonnieTool;
import com.moniewise.monnie.core.tool.ToolCatalog;
import com.moniewise.monnie.core.turn.TurnRequest;
import com.moniewise.monnie.core.turn.TurnResult;
import com.moniewise.moniewise_backend.ai.sdk.stream.AiEventPublisher;
import com.moniewise.moniewise_backend.ai.sdk.stream.PublishingTurnEventSink;
import com.moniewise.moniewise_backend.dto.request.AiSdkTurnRequest;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkActionResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkActionResultResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkCapabilitiesResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkMessageResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkStatusResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkThreadResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkTurnAcceptedResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkTurnResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The whole Monnie agent API, so nothing outside this package touches {@link Monnie}.
 *
 * <p>Two rules hold across every method here:
 *
 * <ul>
 *   <li><b>Identity comes from the session</b>, never from a request body. Every lookup is
 *       scoped to the authenticated email, and another user's thread or action is a 404
 *       rather than a 403 - a 403 would confirm the id exists.
 *   <li><b>The agent never executes a mutation.</b> A turn can at most leave a prepared
 *       action; making it happen takes a separate PIN-authenticated confirm request that
 *       calls the host executor from a request thread.
 * </ul>
 */
@Service
public class AiSdkService {

    private static final int DEFAULT_THREAD_LIMIT = 20;
    private static final int MAX_THREAD_LIMIT = 50;
    private static final int DEFAULT_MESSAGE_LIMIT = 50;
    private static final int MAX_MESSAGE_LIMIT = 100;
    private static final int DEFAULT_ACTION_LIMIT = 20;
    private static final int MAX_ACTION_LIMIT = 50;
    private static final int MAX_TURN_TEXT_LENGTH = 4000;

    private static final String STOMP_ENDPOINT = "/ws";

    private final ObjectProvider<Monnie> monnie;
    private final ObjectProvider<SdkRuntimeInfo> runtimeInfo;
    private final ActionExecutor executor;
    private final JpaPreparedActionStore preparedActions;
    private final JpaConversationStore conversations;
    private final UserService users;
    private final AbuseProtectionService abuse;
    private final AiEventPublisher publisher;

    public AiSdkService(ObjectProvider<Monnie> monnie, ObjectProvider<SdkRuntimeInfo> runtimeInfo,
                        ActionExecutor executor, JpaPreparedActionStore preparedActions,
                        JpaConversationStore conversations, UserService users,
                        AbuseProtectionService abuse, AiEventPublisher publisher) {
        this.monnie = monnie;
        this.runtimeInfo = runtimeInfo;
        this.executor = executor;
        this.preparedActions = preparedActions;
        this.conversations = conversations;
        this.users = users;
        this.abuse = abuse;
        this.publisher = publisher;
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    /**
     * Whether the assistant is available, and the limits a client should respect.
     *
     * <p>Answers while the agent is switched off, so a client can hide the assistant
     * rather than discover a 503 when a user taps it.
     */
    public AiSdkStatusResponse status() {
        SdkRuntimeInfo info = runtimeInfo.getIfAvailable();
        boolean enabled = monnie.getIfAvailable() != null && info != null;
        return new AiSdkStatusResponse(
                enabled,
                info == null ? null : info.provider(),
                info == null ? null : info.modelId(),
                AiEventFrame.PROTOCOL_VERSION,
                SurfaceGuidance.ORDERED,
                new AiSdkStatusResponse.Limits(
                        InstructionPolicy.MAX_LENGTH,
                        MAX_TURN_TEXT_LENGTH,
                        MAX_THREAD_LIMIT,
                        MAX_MESSAGE_LIMIT,
                        MAX_ACTION_LIMIT,
                        info == null ? null : info.historyLimit(),
                        info == null ? null : info.turnBudgetSeconds()),
                new AiSdkStatusResponse.Transports(
                        "/ai/sdk/turn",
                        "/ai/sdk/turn/async",
                        STOMP_ENDPOINT,
                        publisher.userDestination()));
    }

    /**
     * Every read the agent can perform and every mutation it may propose.
     *
     * <p>Derived from the live tool catalog and the configured action allowlist, so it
     * cannot drift from what the agent will actually do.
     */
    public AiSdkCapabilitiesResponse capabilities() {
        Monnie agent = requireAgent();
        ToolCatalog catalog = agent.catalog();
        Set<ActionKind> enabledKinds = agent.config().enabledKinds();

        List<AiSdkCapabilitiesResponse.ToolSummary> reads = new ArrayList<>();
        Map<String, AiSdkCapabilitiesResponse.ActionSummary> actions = new LinkedHashMap<>();

        for (String alias : catalog.agents()) {
            for (MonnieTool tool : catalog.forAgent(alias)) {
                if (tool.kind() != MonnieTool.Kind.PREPARE_WRITE) {
                    reads.add(new AiSdkCapabilitiesResponse.ToolSummary(
                            tool.name(), tool.domain().name(), tool.spec().description()));
                    continue;
                }
                ActionKind kind = tool.actionKind();
                // A kind switched off in config is unreachable, so listing it would
                // invite guidance that can never work.
                if (kind == null || !enabledKinds.contains(kind)) {
                    continue;
                }
                actions.putIfAbsent(kind.name(), new AiSdkCapabilitiesResponse.ActionSummary(
                        kind.name(), kind.label(), kind.riskTier().name(),
                        kind.requiresPin(), kind.movesMoney(), tool.name()));
            }
        }

        List<AiSdkCapabilitiesResponse.SurfaceSummary> surfaces = new ArrayList<>();
        SurfaceGuidance.catalogue().forEach((name, guidance) ->
                surfaces.add(new AiSdkCapabilitiesResponse.SurfaceSummary(name, guidance)));

        return new AiSdkCapabilitiesResponse(
                List.copyOf(reads),
                List.copyOf(actions.values()),
                List.copyOf(surfaces),
                instructionContract());
    }

    private static AiSdkCapabilitiesResponse.InstructionContract instructionContract() {
        return new AiSdkCapabilitiesResponse.InstructionContract(
                InstructionPolicy.MAX_LENGTH,
                true,
                "Appended after the agent's own system prompt, which always stays in force.",
                "Surface guidance for this conversation. It cannot waive PIN, skip tools, "
                        + "invent balances, or execute money.",
                List.of("waive the transaction PIN on any action that requires one",
                        "reach a tool or action kind that is not in this response",
                        "execute a mutation - a turn can only propose one for the user to confirm",
                        "state a figure the server did not compute",
                        "replace or disable the agent's own instructions"));
    }

    // ── Threads ──────────────────────────────────────────────────────────────

    public AiSdkThreadResponse createThread(String email, String surface, String title,
                                            String instructions) {
        AiConversationThread thread = conversations.createOwned(email, surface, title,
                InstructionPolicy.sanitize(instructions));
        return SdkApiMappers.thread(thread, null);
    }

    public List<AiSdkThreadResponse> listThreads(String email, String surface, Instant before,
                                                 Integer limit, String remoteIp) {
        abuse.checkAllowed(AbuseProtectionService.AI_CHAT_READ, abuse.buildKey(email, remoteIp));
        int size = clamp(limit, DEFAULT_THREAD_LIMIT, MAX_THREAD_LIMIT);
        List<AiConversationThread> rows = conversations.listOwned(
                email, SurfaceGuidance.normaliseFilter(surface), before, size);
        Map<String, String> previews = conversations.lastVisibleBodies(
                rows.stream().map(AiConversationThread::getId).toList());
        List<AiSdkThreadResponse> out = new ArrayList<>();
        for (AiConversationThread row : rows) {
            out.add(SdkApiMappers.thread(row, previews.get(row.getId())));
        }
        abuse.recordRequest(AbuseProtectionService.AI_CHAT_READ, abuse.buildKey(email, remoteIp));
        return out;
    }

    public AiSdkThreadResponse getThread(String email, String threadId) {
        return SdkApiMappers.thread(conversations.requireOwned(threadId, email), null);
    }

    public AiSdkThreadResponse patchThread(String email, String threadId, String title,
                                           String instructions) {
        String sanitized = instructions == null ? null : InstructionPolicy.sanitize(instructions);
        return SdkApiMappers.thread(
                conversations.patchOwned(threadId, email, title, sanitized), null);
    }

    public void deleteThread(String email, String threadId) {
        conversations.softDeleteOwned(threadId, email);
    }

    public List<AiSdkMessageResponse> messages(String email, String threadId, Long after,
                                               Integer limit, String remoteIp) {
        abuse.checkAllowed(AbuseProtectionService.AI_CHAT_READ, abuse.buildKey(email, remoteIp));
        conversations.requireOwned(threadId, email);
        int size = clamp(limit, DEFAULT_MESSAGE_LIMIT, MAX_MESSAGE_LIMIT);
        List<AiSdkMessageResponse> out = new ArrayList<>();
        for (AiConversationMessage row : conversations.visibleMessages(threadId, after, size)) {
            out.add(SdkApiMappers.message(row));
        }
        abuse.recordRequest(AbuseProtectionService.AI_CHAT_READ, abuse.buildKey(email, remoteIp));
        return out;
    }

    // ── Turns ────────────────────────────────────────────────────────────────

    /**
     * Runs a turn and waits for the whole answer.
     *
     * <p>Simplest to call, and the right choice for a screen with no socket. The cost is
     * latency: nothing reaches the user until the agent has finished, which for a turn
     * that calls several tools is a few seconds of silence. Prefer {@link #turnAsync}
     * where there is a socket to push to.
     */
    public AiSdkTurnResponse turn(String email, AiSdkTurnRequest body, String remoteIp) {
        Prepared prepared = prepareTurn(email, body, remoteIp);
        TurnEventSink.Collecting sink = new TurnEventSink.Collecting();
        TurnResult result = prepared.agent().run(prepared.request(), sink);
        return new AiSdkTurnResponse(result.threadId(), result.turnId(), result.text(),
                result.finishReason(), result.actionsProposed(), sink.frames());
    }

    /**
     * Starts a turn and returns immediately; frames are pushed to the user's queue.
     *
     * <p>Everything that can fail - rate limit, unknown surface, oversize guidance, agent
     * disabled - is checked before the turn is handed off, so those stay ordinary HTTP
     * errors instead of becoming an error frame the client must special-case.
     *
     * <p>The {@code Future} is deliberately dropped. The SDK persists the answer whatever
     * happens to the socket, so a client that disconnects mid-turn recovers by refetching
     * {@code GET /ai/sdk/threads/{id}/messages} rather than by asking again.
     */
    public AiSdkTurnAcceptedResponse turnAsync(String email, AiSdkTurnRequest body,
                                               String remoteIp) {
        Prepared prepared = prepareTurn(email, body, remoteIp);
        prepared.agent().submit(prepared.request(), new PublishingTurnEventSink(email, publisher));
        return new AiSdkTurnAcceptedResponse(
                prepared.request().threadId(),
                prepared.request().clientMessageId(),
                prepared.request().clientRequestId(),
                publisher.userDestination());
    }

    /** The shared, fail-fast half of both turn paths. */
    private Prepared prepareTurn(String email, AiSdkTurnRequest body, String remoteIp) {
        abuse.checkAllowed(RateLimiterPort.Buckets.AGENT_TURN, abuse.buildKey(email, remoteIp));

        String text = body.text() == null ? "" : body.text().trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
        }
        String surface = SurfaceGuidance.normalise(body.surface());
        String sanitized = InstructionPolicy.sanitize(body.instructions());

        AiConversationThread thread =
                conversations.ensureForTurn(email, body.threadId(), surface, sanitized);
        String composed = InstructionPolicy.compose(thread.getSurface(), thread.getInstructions());

        Monnie agent = requireAgent();
        TurnRequest request = TurnRequest.of(UserRef.of(email), thread.getId(), text)
                .withClientIds(orRandom(body.clientMessageId()), orRandom(body.clientRequestId()))
                .onSurface(thread.getSurface())
                .withInstructions(composed);
        return new Prepared(agent, request);
    }

    private record Prepared(Monnie agent, TurnRequest request) {
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    /** Pending proposals in one thread. */
    public List<AiSdkActionResponse> pendingActions(String email, String threadId) {
        conversations.requireOwned(threadId, email);
        return actions(email, ActionStatus.PENDING.name(), threadId, null);
    }

    /**
     * A user's prepared actions across every thread.
     *
     * @param status defaults to {@code PENDING}. An unrecognised value is a 400 rather
     *     than an empty list, so a typo is visible instead of looking like "nothing here"
     */
    public List<AiSdkActionResponse> actions(String email, String status, String threadId,
                                             Integer limit) {
        ActionStatus parsed = parseStatus(status);
        int size = clamp(limit, DEFAULT_ACTION_LIMIT, MAX_ACTION_LIMIT);
        return preparedActions.findByUserAndStatus(email, parsed, threadId, size).stream()
                .map(SdkApiMappers::action)
                .toList();
    }

    public AiSdkActionResponse action(String email, String actionId) {
        return SdkApiMappers.action(requireAction(email, actionId));
    }

    /**
     * Authorises and performs a prepared action.
     *
     * <p>The handshake is deliberately three steps - {@code beginConfirm}, host executor,
     * {@code completeConfirm} - because the SDK must never be the thing that moves money.
     * {@code beginConfirm} re-validates against current state and claims the row with a
     * compare-and-set, so two taps on the same card cannot both execute.
     */
    public AiSdkActionResultResponse confirm(String email, String actionId, String pin,
                                             String clientParamsHash, Map<String, Object> edits,
                                             String remoteIp) {
        UserRef user = UserRef.of(email);
        PreparedAction shown = requireAction(email, actionId);

        String confirmKey = abuse.buildKey(email, remoteIp);
        abuse.checkAllowed(RateLimiterPort.Buckets.ACTION_CONFIRM, confirmKey);

        Secret secret = Secret.empty();
        try {
            if (shown.requiresPin()) {
                String pinKey = abuse.buildKey(email, remoteIp);
                abuse.checkAllowed(AbuseProtectionService.PIN_VERIFY, pinKey);
                User account = users.findByEmail(email);
                if (pin == null || pin.isBlank()
                        || !users.verifyTransactionPin(account, pin)) {
                    abuse.recordFailure(AbuseProtectionService.PIN_VERIFY, pinKey);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect PIN");
                }
                abuse.recordSuccess(AbuseProtectionService.PIN_VERIFY, pinKey);
                secret.close();
                secret = Secret.of(pin);
            }

            Monnie agent = requireAgent();
            PreparedAction armed;
            try {
                armed = agent.beginConfirm(user, actionId, clientParamsHash, edits);
            } catch (ConfirmationService.ConfirmException e) {
                throw mapConfirm(e);
            }

            ActionExecutor.ExecutionResult outcome;
            try {
                outcome = executor.execute(armed, new ActionExecutor.ExecutionContext(
                        user, secret, armed.idempotencyKey(), armed.id(), Instant.now()));
            } catch (ActionExecutor.ActionExecutionException e) {
                outcome = ActionExecutor.ExecutionResult.failed(e.failureCode(), e.userMessage());
            } catch (RuntimeException e) {
                outcome = ActionExecutor.ExecutionResult.failed("upstream_error",
                        e.getMessage() == null ? "That didn't work." : e.getMessage());
            }

            PreparedAction done = agent.completeConfirm(armed, outcome);
            abuse.recordRequest(RateLimiterPort.Buckets.ACTION_CONFIRM, confirmKey);
            return resolved(email, done, outcome);
        } finally {
            secret.close();
        }
    }

    public AiSdkActionResultResponse cancel(String email, String actionId) {
        Monnie agent = requireAgent();
        try {
            return resolved(email, agent.cancel(UserRef.of(email), actionId), null);
        } catch (ConfirmationService.ConfirmException e) {
            throw mapConfirm(e);
        }
    }

    /**
     * Builds the receipt and pushes it, so the outcome reaches a socket client and an
     * HTTP-only client by the same code path.
     */
    private AiSdkActionResultResponse resolved(String email, PreparedAction action,
                                               ActionExecutor.ExecutionResult outcome) {
        AiEventFrame frame = SdkApiMappers.actionResult(
                action,
                SdkApiMappers.outcomeMessage(action.status(), outcome),
                outcome == null ? Map.of() : outcome.receipt(),
                Instant.now());
        publisher.publish(email, frame);
        return new AiSdkActionResultResponse(SdkApiMappers.action(action), frame);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private PreparedAction requireAction(String email, String actionId) {
        return preparedActions.findByIdForUser(actionId, UserRef.of(email))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Action not found."));
    }

    private Monnie requireAgent() {
        Monnie agent = monnie.getIfAvailable();
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The assistant is not available right now.");
        }
        return agent;
    }

    private static ActionStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return ActionStatus.PENDING;
        }
        try {
            return ActionStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown action status '" + raw + "'.");
        }
    }

    private static ResponseStatusException mapConfirm(ConfirmationService.ConfirmException e) {
        if (e.isNotFound()) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        // CAS_LOST means another request claimed the same action first; IN_FLIGHT means
        // one is still running. Both are 409: repeating the identical request is exactly
        // what the client must not do.
        if ("CAS_LOST".equals(e.code()) || "IN_FLIGHT".equals(e.code())) {
            return new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static String orRandom(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
    }

    private static int clamp(Integer limit, int fallback, int max) {
        if (limit == null || limit < 1) {
            return fallback;
        }
        return Math.min(limit, max);
    }
}

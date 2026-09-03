package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.action.ActionExecutor;
import com.moniewise.monnie.api.action.PreparedAction;
import com.moniewise.monnie.api.event.AiEventFrame;
import com.moniewise.monnie.api.event.TurnEventSink;
import com.moniewise.monnie.api.model.Secret;
import com.moniewise.monnie.api.model.UserRef;
import com.moniewise.monnie.api.port.PreparedActionStore;
import com.moniewise.monnie.api.port.RateLimiterPort;
import com.moniewise.monnie.core.Monnie;
import com.moniewise.monnie.core.action.ConfirmationService;
import com.moniewise.monnie.core.turn.TurnRequest;
import com.moniewise.monnie.core.turn.TurnResult;
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
import java.util.Map;
import java.util.UUID;

@Service
public class AiSdkService {

    private static final int DEFAULT_THREAD_LIMIT = 20;
    private static final int MAX_THREAD_LIMIT = 50;
    private static final int DEFAULT_MESSAGE_LIMIT = 50;
    private static final int MAX_MESSAGE_LIMIT = 100;

    private final ObjectProvider<Monnie> monnie;
    private final ActionExecutor executor;
    private final PreparedActionStore preparedActions;
    private final JpaConversationStore conversations;
    private final UserService users;
    private final AbuseProtectionService abuse;

    public AiSdkService(ObjectProvider<Monnie> monnie, ActionExecutor executor,
                        PreparedActionStore preparedActions, JpaConversationStore conversations,
                        UserService users, AbuseProtectionService abuse) {
        this.monnie = monnie;
        this.executor = executor;
        this.preparedActions = preparedActions;
        this.conversations = conversations;
        this.users = users;
        this.abuse = abuse;
    }

    public TurnPayload turn(String email, String threadId, String text, String surface,
                            String instructions, String remoteIp) {
        abuse.checkAllowed(RateLimiterPort.Buckets.AGENT_TURN, abuse.buildKey(email, remoteIp));
        String sanitized = InstructionPolicy.sanitize(instructions);
        AiConversationThread thread = conversations.ensureForTurn(email, threadId, surface, sanitized);
        String composed = InstructionPolicy.compose(thread.getSurface(), thread.getInstructions());

        Monnie agent = requireAgent();
        UserRef user = UserRef.of(email);
        TurnEventSink.Collecting sink = new TurnEventSink.Collecting();
        TurnRequest request = TurnRequest.of(user, thread.getId(), text)
                .withClientIds(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                .onSurface(thread.getSurface())
                .withInstructions(composed);
        TurnResult result = agent.run(request, sink);
        return new TurnPayload(result.threadId(), result.turnId(), result.text(),
                result.finishReason(), result.actionsProposed(), sink.frames());
    }

    public Map<String, Object> createThread(String email, String surface, String title,
                                            String instructions) {
        AiConversationThread thread = conversations.createOwned(email, surface, title,
                InstructionPolicy.sanitize(instructions));
        return threadPayload(thread, null);
    }

    public List<Map<String, Object>> listThreads(String email, String surface, Instant before,
                                                 Integer limit, String remoteIp) {
        abuse.checkAllowed(AbuseProtectionService.AI_CHAT_READ, abuse.buildKey(email, remoteIp));
        int size = clamp(limit, DEFAULT_THREAD_LIMIT, MAX_THREAD_LIMIT);
        List<AiConversationThread> rows = conversations.listOwned(email, surface, before, size);
        Map<String, String> previews = conversations.lastVisibleBodies(
                rows.stream().map(AiConversationThread::getId).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiConversationThread row : rows) {
            out.add(threadPayload(row, previews.get(row.getId())));
        }
        abuse.recordRequest(AbuseProtectionService.AI_CHAT_READ, abuse.buildKey(email, remoteIp));
        return out;
    }

    public Map<String, Object> getThread(String email, String threadId) {
        return threadPayload(conversations.requireOwned(threadId, email), null);
    }

    public Map<String, Object> patchThread(String email, String threadId, String title,
                                           String instructions) {
        String sanitized = instructions == null ? null : InstructionPolicy.sanitize(instructions);
        return threadPayload(conversations.patchOwned(threadId, email, title, sanitized), null);
    }

    public void deleteThread(String email, String threadId) {
        conversations.softDeleteOwned(threadId, email);
    }

    public List<Map<String, Object>> messages(String email, String threadId, Long after,
                                              Integer limit, String remoteIp) {
        abuse.checkAllowed(AbuseProtectionService.AI_CHAT_READ, abuse.buildKey(email, remoteIp));
        conversations.requireOwned(threadId, email);
        int size = clamp(limit, DEFAULT_MESSAGE_LIMIT, MAX_MESSAGE_LIMIT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiConversationMessage row : conversations.visibleMessages(threadId, after, size)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", row.getId());
            payload.put("turnId", row.getTurnId());
            payload.put("role", row.getRole());
            payload.put("text", row.getBody());
            payload.put("createdAt", row.getCreatedAt());
            out.add(payload);
        }
        abuse.recordRequest(AbuseProtectionService.AI_CHAT_READ, abuse.buildKey(email, remoteIp));
        return out;
    }

    public List<Map<String, Object>> pendingActions(String email, String threadId) {
        conversations.requireOwned(threadId, email);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PreparedAction action : preparedActions.findPendingByThread(threadId)) {
            if (!action.user().principal().equals(email)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", action.id());
            payload.put("status", action.status().name());
            payload.put("kind", action.kind().name());
            payload.put("requiresPin", action.requiresPin());
            payload.put("paramsHash", action.paramsHash());
            payload.put("render", action.render());
            out.add(payload);
        }
        return out;
    }

    public PreparedAction confirm(String email, String actionId, String pin,
                                  String clientParamsHash, Map<String, Object> edits,
                                  String remoteIp) {
        UserRef user = UserRef.of(email);
        PreparedAction shown = preparedActions.findByIdForUser(actionId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Action not found."));

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
            return done;
        } finally {
            secret.close();
        }
    }

    public PreparedAction cancel(String email, String actionId) {
        Monnie agent = requireAgent();
        try {
            return agent.cancel(UserRef.of(email), actionId);
        } catch (ConfirmationService.ConfirmException e) {
            throw mapConfirm(e);
        }
    }

    private Monnie requireAgent() {
        Monnie agent = monnie.getIfAvailable();
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Monnie SDK is not enabled.");
        }
        return agent;
    }

    private static ResponseStatusException mapConfirm(ConfirmationService.ConfirmException e) {
        if (e.isNotFound()) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        if ("CAS_LOST".equals(e.code()) || "IN_FLIGHT".equals(e.code())) {
            return new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static Map<String, Object> threadPayload(AiConversationThread thread, String preview) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", thread.getId());
        payload.put("surface", thread.getSurface());
        payload.put("title", thread.getTitle());
        payload.put("hasInstructions", thread.getInstructions() != null
                && !thread.getInstructions().isBlank());
        payload.put("createdAt", thread.getCreatedAt());
        payload.put("updatedAt", thread.getUpdatedAt());
        if (preview != null) {
            payload.put("lastMessage", preview);
        }
        return payload;
    }

    private static int clamp(Integer limit, int fallback, int max) {
        if (limit == null || limit < 1) {
            return fallback;
        }
        return Math.min(limit, max);
    }

    public record TurnPayload(String threadId, String turnId, String text, String finishReason,
                              int actionsProposed, List<AiEventFrame> frames) {
    }
}

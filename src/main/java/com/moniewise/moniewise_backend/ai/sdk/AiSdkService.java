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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiSdkService {

    private final ObjectProvider<Monnie> monnie;
    private final ActionExecutor executor;
    private final PreparedActionStore preparedActions;
    private final UserService users;
    private final AbuseProtectionService abuse;

    public AiSdkService(ObjectProvider<Monnie> monnie, ActionExecutor executor,
                        PreparedActionStore preparedActions, UserService users,
                        AbuseProtectionService abuse) {
        this.monnie = monnie;
        this.executor = executor;
        this.preparedActions = preparedActions;
        this.users = users;
        this.abuse = abuse;
    }

    public TurnPayload turn(String email, String threadId, String text, String surface) {
        Monnie agent = requireAgent();
        UserRef user = UserRef.of(email);
        TurnEventSink.Collecting sink = new TurnEventSink.Collecting();
        TurnRequest request = TurnRequest.of(user, threadId, text)
                .withClientIds(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        if (surface != null && !surface.isBlank()) {
            request = request.onSurface(surface);
        }
        TurnResult result = agent.run(request, sink);
        return new TurnPayload(result.threadId(), result.turnId(), result.text(),
                result.finishReason(), result.actionsProposed(), sink.frames());
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

    public record TurnPayload(String threadId, String turnId, String text, String finishReason,
                              int actionsProposed, List<AiEventFrame> frames) {
    }
}

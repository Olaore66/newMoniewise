package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.action.ActionExecutor;
import com.moniewise.monnie.api.action.ActionKind;
import com.moniewise.monnie.api.action.ActionStatus;
import com.moniewise.monnie.api.action.PreparedAction;
import com.moniewise.monnie.api.event.AiEventFrame;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkActionResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkMessageResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkThreadResponse;

import java.time.Instant;
import java.util.Map;

/**
 * Entity and SDK types to wire shapes.
 *
 * <p>Kept out of the service so the wire contract is readable in one place: every field a
 * client sees is decided here.
 */
final class SdkApiMappers {

    private SdkApiMappers() {
    }

    static AiSdkThreadResponse thread(AiConversationThread thread, String preview) {
        String instructions = thread.getInstructions();
        return new AiSdkThreadResponse(
                thread.getId(),
                thread.getSurface(),
                thread.getTitle(),
                instructions,
                instructions != null && !instructions.isBlank(),
                thread.getCreatedAt(),
                thread.getUpdatedAt(),
                preview);
    }

    static AiSdkMessageResponse message(AiConversationMessage row) {
        return new AiSdkMessageResponse(
                row.getId(),
                row.getTurnId(),
                row.getRole(),
                row.getBody(),
                row.getCreatedAt());
    }

    static AiSdkActionResponse action(PreparedAction action) {
        ActionKind kind = action.kind();
        return new AiSdkActionResponse(
                action.id(),
                action.threadId(),
                kind.name(),
                kind.label(),
                kind.riskTier().name(),
                action.status().name(),
                action.requiresPin(),
                kind.movesMoney(),
                action.paramsHash(),
                action.params(),
                action.editableFields(),
                action.render(),
                action.createdAt(),
                action.expiresAt(),
                action.resolvedAt(),
                action.failureCode(),
                action.executionRef());
    }

    /**
     * Builds the {@code ACTION_RESULT} frame for a resolved action.
     *
     * <p>The SDK emits this frame itself during a turn, but its event scope is bound to a
     * running turn and drops anything emitted outside one. A confirm arrives on its own
     * HTTP request, long after that turn ended, so the frame has to be built here or the
     * client never learns the outcome over the socket.
     *
     * @param message server-templated prose. Never model output: a receipt the user reads
     *     must not be something a language model wrote
     */
    static AiEventFrame actionResult(PreparedAction action, String message,
                                     Map<String, Object> receipt, Instant at) {
        return AiEventFrame.of(
                AiEventFrame.Type.ACTION_RESULT,
                null,
                action.threadId(),
                0,
                at.toEpochMilli(),
                new AiEventFrame.ActionResult(
                        action.id(),
                        action.status(),
                        message,
                        receipt == null ? Map.of() : receipt));
    }

    /** The sentence a client shows when an action resolves, chosen from its final status. */
    static String outcomeMessage(ActionStatus status, ActionExecutor.ExecutionResult result) {
        if (result != null && result.userMessage() != null && !result.userMessage().isBlank()) {
            return result.userMessage();
        }
        return switch (status) {
            case EXECUTED -> "Done.";
            case PENDING_PROVIDER -> "Sent. We'll confirm once it settles.";
            case REJECTED -> "Cancelled.";
            case EXPIRED -> "That request expired. Ask again and I'll set it up afresh.";
            case SUPERSEDED -> "Replaced by a newer request.";
            case FAILED -> "That didn't go through.";
            default -> "Updated.";
        };
    }
}

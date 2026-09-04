package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * One message to the Monnie agent.
 *
 * @param threadId omit to start a new conversation
 * @param surface where the user is: {@code CHAT}, {@code ONBOARDING}, {@code DASHBOARD}
 *     or {@code BUDGET_ASSISTANT}. Defaults to {@code CHAT}
 * @param instructions guidance for this conversation, applied only if the thread does not
 *     already have some. Additive: it specialises the agent, it cannot re-define it
 * @param clientMessageId identifies the message the user composed; echoed on every frame
 * @param clientRequestId identifies this attempt at sending it, so a retry after a dropped
 *     socket is distinguishable from the original
 */
public record AiSdkTurnRequest(
        @Size(max = 64) String threadId,
        @NotBlank(message = "text is required") @Size(max = 4000) String text,
        @Size(max = 32) String surface,
        @Size(max = 2000) String instructions,
        @Size(max = 64) String clientMessageId,
        @Size(max = 64) String clientRequestId
) {}

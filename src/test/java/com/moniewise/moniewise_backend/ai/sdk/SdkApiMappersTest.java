package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.action.ActionExecutor;
import com.moniewise.monnie.api.action.ActionKind;
import com.moniewise.monnie.api.action.ActionStatus;
import com.moniewise.monnie.api.action.PreparedAction;
import com.moniewise.monnie.api.action.RenderSpec;
import com.moniewise.monnie.api.event.AiEventFrame;
import com.moniewise.monnie.api.model.UserRef;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkActionResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkApiMappersTest {

    @Test
    void actionResponseCarriesTheRiskFieldsAClientNeedsToRenderACard() {
        AiSdkActionResponse response = SdkApiMappers.action(
                action(ActionKind.WALLET_WITHDRAW, ActionStatus.PENDING));

        assertEquals("WALLET_WITHDRAW", response.kind());
        assertEquals("Withdraw to bank", response.label());
        assertEquals("MONEY_OUT", response.riskTier());
        assertTrue(response.requiresPin());
        assertTrue(response.movesMoney());
        assertEquals("hash", response.paramsHash());
    }

    @Test
    void confirmBuildsAnActionResultFrameTheSdkItselfCannotEmit() {
        // The SDK emits ACTION_RESULT through a turn-scoped event bus that drops frames
        // outside a running turn. A confirm arrives on its own request long after that
        // turn ended, so without this mapper a socket client never learns the outcome.
        PreparedAction done = action(ActionKind.WALLET_WITHDRAW, ActionStatus.EXECUTED);
        Instant at = Instant.parse("2026-09-04T10:15:30Z");

        AiEventFrame frame = SdkApiMappers.actionResult(done, "Sent.", Map.of("ref", "AI-1"), at);

        assertEquals(AiEventFrame.Type.ACTION_RESULT, frame.type());
        assertEquals("thread-1", frame.threadId());
        assertEquals(at.toEpochMilli(), frame.ts());
        AiEventFrame.ActionResult payload =
                assertInstanceOf(AiEventFrame.ActionResult.class, frame.payload());
        assertEquals("act-1", payload.actionId());
        assertEquals(ActionStatus.EXECUTED, payload.status());
        assertEquals("Sent.", payload.message());
        assertEquals("AI-1", payload.receipt().get("ref"));
    }

    @Test
    void executorProseWinsOverTheGenericSentence() {
        ActionExecutor.ExecutionResult result =
                ActionExecutor.ExecutionResult.executed("ref", "Sent 1,000 to GTBank.", Map.of());

        assertEquals("Sent 1,000 to GTBank.",
                SdkApiMappers.outcomeMessage(ActionStatus.EXECUTED, result));
    }

    @Test
    void everyResolvedStatusHasSomethingToShowTheUser() {
        // A cancel has no execution result at all, so the status alone has to produce a
        // sentence - an empty receipt message is what leaves a card looking stuck.
        assertEquals("Cancelled.", SdkApiMappers.outcomeMessage(ActionStatus.REJECTED, null));
        assertEquals("Done.", SdkApiMappers.outcomeMessage(ActionStatus.EXECUTED, null));
        for (ActionStatus status : ActionStatus.values()) {
            assertTrue(SdkApiMappers.outcomeMessage(status, null) != null
                    && !SdkApiMappers.outcomeMessage(status, null).isBlank());
        }
    }

    private static PreparedAction action(ActionKind kind, ActionStatus status) {
        return new PreparedAction(
                "act-1", "thread-1", UserRef.of("ada@example.com"), kind,
                Map.of("amount", "1000"), "hash", RenderSpec.builder("Withdraw").build(),
                List.of(), true, status, 0, "AI-1", "plan_agent", Map.of(),
                Instant.now(), Instant.now().plusSeconds(600), null, null, null);
    }
}

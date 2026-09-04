package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.action.ActionExecutor;
import com.moniewise.monnie.api.action.ActionKind;
import com.moniewise.monnie.api.action.ActionStatus;
import com.moniewise.monnie.api.action.PreparedAction;
import com.moniewise.monnie.api.action.RenderSpec;
import com.moniewise.monnie.api.model.UserRef;
import com.moniewise.monnie.core.Monnie;
import com.moniewise.monnie.core.turn.TurnRequest;
import com.moniewise.moniewise_backend.ai.sdk.stream.AiEventPublisher;
import com.moniewise.moniewise_backend.dto.request.AiSdkTurnRequest;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkStatusResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkThreadResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The agent bean is absent in every test here.
 *
 * <p>That is deliberate rather than a limitation: {@code Monnie} is final and this
 * project is on Mockito 4, so it cannot be mocked - and the interesting behaviour is all
 * on the near side of it anyway. Ownership checks, guidance limits and rate limiting must
 * reject a bad request <em>before</em> a turn is handed to the agent, so a test that
 * proves the failure happens with no agent present proves it happens first.
 */
class AiSdkServiceTest {

    private static final String EMAIL = "ada@example.com";
    private static final String IP = "1.1.1.1";

    private JpaPreparedActionStore store;
    private JpaConversationStore conversations;
    private UserService users;
    private AbuseProtectionService abuse;
    private ActionExecutor executor;
    private AiEventPublisher publisher;
    private AiSdkService service;

    @BeforeEach
    void setUp() {
        store = mock(JpaPreparedActionStore.class);
        conversations = mock(JpaConversationStore.class);
        users = mock(UserService.class);
        abuse = mock(AbuseProtectionService.class);
        executor = mock(ActionExecutor.class);
        publisher = mock(AiEventPublisher.class);
        when(abuse.buildKey(any(), any())).thenReturn("user|ip");
        when(publisher.userDestination()).thenReturn("/user/queue/ai");
        service = newService(noAgent(), noRuntimeInfo());
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    @Test
    void statusAnswersWhileTheAgentIsOff() {
        AiSdkStatusResponse status = service.status();

        assertFalse(status.enabled());
        assertNull(status.provider());
        assertNull(status.model());
        // Null rather than a made-up default: a client must not size its history on a
        // number no running agent agreed to.
        assertNull(status.limits().historyLimit());
        assertEquals(InstructionPolicy.MAX_LENGTH, status.limits().maxInstructionLength());
        assertTrue(status.surfaces().contains(TurnRequest.SURFACE_ONBOARDING));
        assertEquals("/user/queue/ai", status.transports().userQueue());
    }

    @Test
    void capabilitiesNeedsARunningAgent() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.capabilities());
        assertEquals(503, ex.getStatus().value());
    }

    // ── Turns ────────────────────────────────────────────────────────────────

    @Test
    void turnRejectsOversizeInstructions() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.turn(EMAIL, turnBody("hello", "CHAT",
                        "x".repeat(InstructionPolicy.MAX_LENGTH + 1)), IP));

        assertEquals(400, ex.getStatus().value());
        verifyNoInteractions(conversations);
    }

    @Test
    void turnRejectsAnUnknownSurface() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.turn(EMAIL, turnBody("hello", "ONBOARDING_V2", null), IP));

        assertEquals(400, ex.getStatus().value());
        verifyNoInteractions(conversations);
    }

    @Test
    void turnRejectsBlankText() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.turn(EMAIL, turnBody("   ", "CHAT", null), IP));

        assertEquals(400, ex.getStatus().value());
        verifyNoInteractions(conversations);
    }

    @Test
    void asyncTurnIsNotAcceptedWithoutAnAgent() {
        when(conversations.ensureForTurn(eq(EMAIL), isNull(), eq("ONBOARDING"), any()))
                .thenReturn(thread("thread-1", "ONBOARDING", null));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.turnAsync(EMAIL, turnBody("hi", "onboarding", null), IP));

        // 503 rather than a 202 the client would wait on frames for that never arrive.
        assertEquals(503, ex.getStatus().value());
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void turnNormalisesSurfaceCasing() {
        when(conversations.ensureForTurn(eq(EMAIL), isNull(), eq("ONBOARDING"), any()))
                .thenReturn(thread("thread-1", "ONBOARDING", null));

        assertThrows(ResponseStatusException.class,
                () -> service.turn(EMAIL, turnBody("hi", "onboarding", null), IP));

        // Stored upper-case, so the surface starter applies and the client's own
        // ?surface=ONBOARDING listing filter still matches the thread it just created.
        verify(conversations).ensureForTurn(EMAIL, null, "ONBOARDING", null);
    }

    // ── Threads ──────────────────────────────────────────────────────────────

    @Test
    void getThreadForAnotherUserLooksLikeNotFound() {
        when(conversations.requireOwned("thread-x", EMAIL))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found."));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getThread(EMAIL, "thread-x"));
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    void threadResponseEchoesItsGuidanceToTheOwner() {
        when(conversations.requireOwned("thread-1", EMAIL))
                .thenReturn(thread("thread-1", "ONBOARDING", "Ask one question at a time."));

        AiSdkThreadResponse response = service.getThread(EMAIL, "thread-1");

        assertTrue(response.hasInstructions());
        assertEquals("Ask one question at a time.", response.instructions());
    }

    @Test
    void listThreadsIsOwnerScoped() {
        when(conversations.listOwned(eq(EMAIL), isNull(), isNull(), eq(20))).thenReturn(List.of());
        when(conversations.lastVisibleBodies(any())).thenReturn(Map.of());

        assertTrue(service.listThreads(EMAIL, null, null, null, IP).isEmpty());
        verify(conversations).listOwned(EMAIL, null, null, 20);
    }

    @Test
    void listThreadsRejectsAnUnknownSurfaceFilter() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listThreads(EMAIL, "nonsense", null, null, IP));
        assertEquals(400, ex.getStatus().value());
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    @Test
    void actionsDefaultToPendingAcrossEveryThread() {
        when(store.findByUserAndStatus(eq(EMAIL), any(), any(), anyInt())).thenReturn(List.of());

        assertTrue(service.actions(EMAIL, null, null, null).isEmpty());
        verify(store).findByUserAndStatus(EMAIL, ActionStatus.PENDING, null, 20);
    }

    @Test
    void actionsClampAnOversizePageRequest() {
        when(store.findByUserAndStatus(eq(EMAIL), any(), any(), anyInt())).thenReturn(List.of());

        service.actions(EMAIL, "EXECUTED", null, 999);
        verify(store).findByUserAndStatus(EMAIL, ActionStatus.EXECUTED, null, 50);
    }

    @Test
    void actionsRejectAnUnknownStatus() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.actions(EMAIL, "ALMOST_DONE", null, null));

        // A 400 rather than an empty list: a typo must not read as "nothing pending".
        assertEquals(400, ex.getStatus().value());
        verifyNoInteractions(store);
    }

    @Test
    void actionForAnotherUserLooksLikeNotFound() {
        when(store.findByIdForUser(any(), any())).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.action(EMAIL, "act-1"));
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    void confirmForAnotherUserLooksLikeNotFound() {
        when(store.findByIdForUser(any(), any())).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.confirm(EMAIL, "act-1", "1234", null, Map.of(), IP));
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    void confirmWithTheWrongPinExecutesNothing() {
        UserRef user = UserRef.of(EMAIL);
        when(store.findByIdForUser("act-1", user))
                .thenReturn(Optional.of(action(user, ActionKind.WALLET_WITHDRAW, true)));
        User account = new User();
        when(users.findByEmail(EMAIL)).thenReturn(account);
        when(users.verifyTransactionPin(account, "0000")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.confirm(EMAIL, "act-1", "0000", null, Map.of(), IP));

        assertEquals(400, ex.getStatus().value());
        verifyNoInteractions(executor);
        verify(abuse).recordFailure(eq(AbuseProtectionService.PIN_VERIFY), any());
        verify(publisher, never()).publish(any(), any());
    }

    // ── Views ────────────────────────────────────────────────────────────────

    @Test
    void walletViewMapsSettlementFields() {
        Wallet wallet = new Wallet();
        wallet.setBalance(new BigDecimal("1500.00"));
        wallet.setCurrency("NGN");
        wallet.setStatus(WalletStatus.ACTIVE);
        wallet.setAccountNumber("1234567890");
        wallet.setBankName("Rubies");
        wallet.setProviderName("rubies");
        wallet.setSettlementAccountNumber("0123456789");
        wallet.setSettlementBankName("GTBank");
        wallet.setSettlementBankCode("058");
        wallet.setSettlementAccountName("ADA O");

        var view = SdkViews.wallet(wallet);

        assertEquals("1500.00", view.balance().raw());
        assertEquals("0123456789", view.settlementAccountNumber());
        assertEquals("058", view.settlementBankCode());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private AiSdkService newService(ObjectProvider<Monnie> agent,
                                    ObjectProvider<SdkRuntimeInfo> info) {
        return new AiSdkService(agent, info, executor, store, conversations, users, abuse,
                publisher);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Monnie> noAgent() {
        ObjectProvider<Monnie> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SdkRuntimeInfo> noRuntimeInfo() {
        ObjectProvider<SdkRuntimeInfo> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static AiSdkTurnRequest turnBody(String text, String surface, String instructions) {
        return new AiSdkTurnRequest(null, text, surface, instructions, null, null);
    }

    private static AiConversationThread thread(String id, String surface, String instructions) {
        AiConversationThread thread = new AiConversationThread();
        thread.setId(id);
        thread.setUserEmail(EMAIL);
        thread.setSurface(surface);
        thread.setTitle(surface);
        thread.setInstructions(instructions);
        return thread;
    }

    private static PreparedAction action(UserRef user, ActionKind kind, boolean pin) {
        return new PreparedAction(
                "act-1", "thread-1", user, kind, Map.of("amount", "1000"),
                "hash", RenderSpec.builder("Withdraw").build(), List.of(), pin,
                ActionStatus.PENDING, 0, "AI-1", "plan_agent", Map.of(),
                Instant.now(), Instant.now().plusSeconds(600), null, null, null);
    }
}

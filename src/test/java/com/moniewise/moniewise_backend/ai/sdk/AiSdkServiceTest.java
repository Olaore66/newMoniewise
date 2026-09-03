package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.action.ActionExecutor;
import com.moniewise.monnie.api.action.ActionKind;
import com.moniewise.monnie.api.action.ActionStatus;
import com.moniewise.monnie.api.action.PreparedAction;
import com.moniewise.monnie.api.action.RenderSpec;
import com.moniewise.monnie.api.model.UserRef;
import com.moniewise.monnie.api.port.PreparedActionStore;
import com.moniewise.monnie.core.Monnie;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiSdkServiceTest {

    private PreparedActionStore store;
    private UserService users;
    private AbuseProtectionService abuse;
    private AiSdkService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = mock(PreparedActionStore.class);
        users = mock(UserService.class);
        abuse = mock(AbuseProtectionService.class);
        ObjectProvider<Monnie> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        when(abuse.buildKey(any(), any())).thenReturn("user|ip");
        service = new AiSdkService(provider, mock(ActionExecutor.class), store, users, abuse);
    }

    @Test
    void confirmWrongUserLooksLikeNotFound() {
        when(store.findByIdForUser(any(), any())).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.confirm("ada@example.com", "act-1", "1234", null, Map.of(), "1.1.1.1"));
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    void confirmPinMismatchDoesNotExecute() {
        UserRef user = UserRef.of("ada@example.com");
        PreparedAction action = action(user, ActionKind.WALLET_WITHDRAW, true);
        when(store.findByIdForUser("act-1", user)).thenReturn(Optional.of(action));
        User account = new User();
        when(users.findByEmail("ada@example.com")).thenReturn(account);
        when(users.verifyTransactionPin(account, "0000")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.confirm("ada@example.com", "act-1", "0000", null, Map.of(), "1.1.1.1"));
        assertEquals(400, ex.getStatus().value());
    }

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

    private static PreparedAction action(UserRef user, ActionKind kind, boolean pin) {
        return new PreparedAction(
                "act-1", "thread-1", user, kind, Map.of("amount", "1000"),
                "hash", RenderSpec.builder("Withdraw").build(), List.of(), pin,
                ActionStatus.PENDING, 0, "AI-1", "plan_agent", Map.of(),
                Instant.now(), Instant.now().plusSeconds(600), null, null, null);
    }
}

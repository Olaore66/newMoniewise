package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.action.ActionExecutor;
import com.moniewise.monnie.api.action.ActionKind;
import com.moniewise.monnie.api.action.PreparedAction;
import com.moniewise.monnie.api.model.Secret;
import com.moniewise.moniewise_backend.controller.BudgetController;
import com.moniewise.moniewise_backend.dto.request.AirtimePurchaseRequest;
import com.moniewise.moniewise_backend.dto.request.BudgetRequest;
import com.moniewise.moniewise_backend.dto.request.DataPurchaseRequest;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.request.P2PTransferRequest;
import com.moniewise.moniewise_backend.dto.request.SavingsP2PTransferRequest;
import com.moniewise.moniewise_backend.dto.request.UpdateBankDetailsRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.entity.Notification;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.Status;
import com.moniewise.moniewise_backend.repository.NotificationRepository;
import com.moniewise.moniewise_backend.repository.PendingDisbursementRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.service.BeneficiaryService;
import com.moniewise.moniewise_backend.service.BudgetService;
import com.moniewise.moniewise_backend.service.EnvelopeService;
import com.moniewise.moniewise_backend.service.NotificationService;
import com.moniewise.moniewise_backend.service.PayeelordVasService;
import com.moniewise.moniewise_backend.service.SavingsService;
import com.moniewise.moniewise_backend.service.SubscriptionService;
import com.moniewise.moniewise_backend.service.UserService;
import com.moniewise.moniewise_backend.service.WalletService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MonnieActionExecutor implements ActionExecutor {

    private final UserService users;
    private final UserRepository userRepository;
    private final EnvelopeService envelopes;
    private final BudgetService budgets;
    private final WalletService wallets;
    private final SavingsService savings;
    private final BeneficiaryService beneficiaries;
    private final PayeelordVasService vas;
    private final NotificationService notifications;
    private final NotificationRepository notificationRepository;
    private final PendingDisbursementRepository disbursements;
    private final SubscriptionService subscriptions;

    public MonnieActionExecutor(UserService users, UserRepository userRepository,
                                EnvelopeService envelopes, BudgetService budgets,
                                WalletService wallets, SavingsService savings,
                                BeneficiaryService beneficiaries, PayeelordVasService vas,
                                NotificationService notifications,
                                NotificationRepository notificationRepository,
                                PendingDisbursementRepository disbursements,
                                SubscriptionService subscriptions) {
        this.users = users;
        this.userRepository = userRepository;
        this.envelopes = envelopes;
        this.budgets = budgets;
        this.wallets = wallets;
        this.savings = savings;
        this.beneficiaries = beneficiaries;
        this.vas = vas;
        this.notifications = notifications;
        this.notificationRepository = notificationRepository;
        this.disbursements = disbursements;
        this.subscriptions = subscriptions;
    }

    @Override
    public Set<ActionKind> supportedKinds() {
        return EnumSet.allOf(ActionKind.class);
    }

    @Override
    public ExecutionResult execute(PreparedAction action, ExecutionContext ctx) {
        try {
            return switch (action.kind()) {
                case CREATE_ENVELOPE -> createEnvelope(action, ctx);
                case UPDATE_ENVELOPE_CONDITIONS -> updateEnvelope(action, ctx);
                case DELETE_ENVELOPE -> {
                    envelopes.deleteEnvelope(longParam(action, "envelopeId"), ctx.user().principal());
                    yield ok(action, "Envelope deleted.");
                }
                case LOCK_ENVELOPE -> {
                    budgets.lockEnvelope(longParam(action, "envelopeId"), "SAFE", 30,
                            BigDecimal.ZERO, ctx.user().principal());
                    yield ok(action, "Envelope locked.");
                }
                case MOVE_BETWEEN_ENVELOPES -> {
                    envelopes.moveMoney(longParam(action, "sourceEnvelopeId"),
                            longParam(action, "targetEnvelopeId"),
                            money(action, "amount").doubleValue(),
                            ctx.user().principal(), "AI move");
                    yield ok(action, "Money moved.");
                }
                case CLAIM_DISBURSEMENT -> {
                    long envelopeId = longParam(action, "envelopeId");
                    long pendingId = disbursements
                            .findFirstByEnvelopeIdAndStatus(envelopeId, Status.PENDING)
                            .orElseThrow(() -> new ActionExecutionException("NOT_FOUND",
                                    "No pending disbursement for that envelope."))
                            .getId();
                    envelopes.claimDisbursement(pendingId, ctx.user().principal());
                    yield ok(action, "Disbursement claimed.");
                }
                case ENVELOPE_TRANSFER_EXTERNAL -> external(action, ctx);
                case ENVELOPE_TRANSFER_P2P -> p2p(action, ctx);
                case CREATE_BUDGET -> createBudget(action, ctx);
                case ACTIVATE_BUDGET -> {
                    budgets.activateBudget(longParam(action, "budgetId"), ctx.user().principal());
                    yield ok(action, "Budget activated.");
                }
                case TOPUP_BUDGET -> {
                    budgets.topUpBudget(longParam(action, "budgetId"),
                            money(action, "amount").doubleValue(), ctx.user().principal());
                    yield ok(action, "Budget topped up.");
                }
                case EXTEND_BUDGET -> {
                    String name = string(action, "newName");
                    budgets.extendBudget(longParam(action, "budgetId"), name,
                            LocalDate.parse(string(action, "newEndDate")), ctx.user().principal());
                    yield ok(action, "Budget extended.");
                }
                case DELETE_BUDGET -> {
                    budgets.deleteBudget(longParam(action, "budgetId"), ctx.user().principal());
                    yield ok(action, "Budget deleted.");
                }
                case WALLET_WITHDRAW -> withdraw(action, ctx);
                case UPDATE_SETTLEMENT_ACCOUNT -> settlement(action, ctx);
                case CREATE_SAVINGS_GOAL -> createSavings(action, ctx);
                case FUND_SAVINGS -> {
                    User user = users.findByEmail(ctx.user().principal());
                    savings.manualTopUp(user.getId(), longParam(action, "goalId"), money(action, "amount"));
                    yield ok(action, "Savings funded.");
                }
                case WITHDRAW_SAVINGS -> {
                    User user = users.findByEmail(ctx.user().principal());
                    savings.withdrawSavings(user.getId(), longParam(action, "goalId"));
                    yield ok(action, "Savings withdrawn.");
                }
                case BREAK_SAVINGS_TO_WALLET -> {
                    User user = users.findByEmail(ctx.user().principal());
                    savings.breakActiveSavingsToWallet(user.getId(), longParam(action, "goalId"));
                    yield ok(action, "Savings broken to wallet.");
                }
                case SAVINGS_TRANSFER_BANK -> savingsBank(action, ctx);
                case SAVINGS_TRANSFER_P2P -> savingsP2p(action, ctx);
                case SWEEP_ENVELOPE_TO_SAVINGS -> sweep(action, ctx);
                case ADD_BENEFICIARY -> {
                    User user = users.findByEmail(ctx.user().principal());
                    String identity = string(action, "moniewiseIdentity");
                    beneficiaries.addBeneficiary(user.getId(), identity, string(action, "nickname"));
                    yield ok(action, "Recipient saved.");
                }
                case BUY_AIRTIME -> airtime(action, ctx);
                case BUY_DATA -> data(action, ctx);
                case UPDATE_PROFILE -> profile(action, ctx);
                case SUBSCRIBE_PLAN -> {
                    User user = users.findByEmail(ctx.user().principal());
                    subscriptions.subscribe(user.getId(), string(action, "planCode"), action.idempotencyKey());
                    yield ok(action, "Subscribed.");
                }
                case CANCEL_SUBSCRIPTION -> {
                    User user = users.findByEmail(ctx.user().principal());
                    subscriptions.cancel(user.getId());
                    yield ok(action, "Subscription cancelled.");
                }
                case MARK_NOTIFICATION_READ -> {
                    User user = users.findByEmail(ctx.user().principal());
                    Notification n = notificationRepository
                            .findByIdAndUserId(longParam(action, "notificationId"), user.getId())
                            .orElseThrow(() -> new ActionExecutionException("NOT_FOUND", "Notification not found."));
                    n.setRead(true);
                    notificationRepository.save(n);
                    yield ok(action, "Marked read.");
                }
                case MARK_ALL_NOTIFICATIONS_READ -> {
                    User user = users.findByEmail(ctx.user().principal());
                    notifications.markAllNotificationsAsRead(user.getId());
                    yield ok(action, "All notifications marked read.");
                }
                case ACCEPT_LEGAL_DOCUMENT -> {
                    users.acceptTnc(ctx.user().principal(), true);
                    yield ok(action, "Accepted.");
                }
            };
        } catch (ActionExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ActionExecutionException("upstream_error",
                    e.getMessage() == null ? "That didn't work." : e.getMessage(), e);
        }
    }

    private ExecutionResult createEnvelope(PreparedAction action, ExecutionContext ctx) {
        EnvelopeRequest request = new EnvelopeRequest();
        request.setBudgetId(longParam(action, "budgetId"));
        request.setName(string(action, "name"));
        request.setExactAmount(money(action, "exactAmount"));
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("type", string(action, "conditionType"));
        if (action.params().get("limit") != null) {
            conditions.put("limit", money(action, "limit"));
        }
        if (action.params().get("disbursementTime") != null) {
            conditions.put("disbursementTime", string(action, "disbursementTime"));
        }
        request.setConditions(conditions);
        envelopes.createEnvelope(request, ctx.user().principal(), true);
        return ok(action, "Envelope created.");
    }

    private ExecutionResult updateEnvelope(PreparedAction action, ExecutionContext ctx) {
        EnvelopeRequest request = new EnvelopeRequest();
        Map<String, Object> conditions = new LinkedHashMap<>();
        if (action.params().get("conditionType") != null) {
            conditions.put("type", string(action, "conditionType"));
        }
        if (action.params().get("limit") != null) {
            conditions.put("limit", money(action, "limit"));
        }
        request.setConditions(conditions);
        envelopes.updateEnvelopeConditions(longParam(action, "envelopeId"), request, ctx.user().principal());
        return ok(action, "Envelope rules updated.");
    }

    private ExecutionResult external(PreparedAction action, ExecutionContext ctx) {
        BudgetController.ExternalAccount dest = new BudgetController.ExternalAccount();
        dest.setAccountNumber(string(action, "accountNumber"));
        dest.setBankCode(string(action, "bankCode"));
        dest.setBankName(stringOr(action, "bankName", ""));
        dest.setRecipientName(stringOr(action, "accountName", ""));
        envelopes.transferToExternal(
                longParam(action, "envelopeId"), dest,
                money(action, "amount").doubleValue(),
                ctx.user().principal(), "AI transfer", null, pin(ctx));
        return ExecutionResult.pendingProvider(action.idempotencyKey(),
                "Transfer submitted.", Map.of());
    }

    private ExecutionResult p2p(PreparedAction action, ExecutionContext ctx) {
        P2PTransferRequest request = new P2PTransferRequest();
        request.setSourceEnvelopeId(longParam(action, "envelopeId"));
        request.setAmount(money(action, "amount"));
        request.setRecipientIdentity(string(action, "recipientIdentity"));
        request.setNote(stringOr(action, "note", null));
        request.setTransactionPin(pin(ctx));
        envelopes.transferToMonieWiseUser(request, ctx.user().principal());
        return ok(action, "Sent to Moniewise user.");
    }

    private ExecutionResult createBudget(PreparedAction action, ExecutionContext ctx) {
        BudgetRequest request = new BudgetRequest();
        request.setName(string(action, "name"));
        request.setTotalAmount(money(action, "totalAmount"));
        request.setDurationDays(((Number) action.params().get("durationDays")).intValue());
        if (action.params().get("startDate") != null) {
            request.setStartDate(LocalDate.parse(string(action, "startDate")));
        }
        request.setTermsAccepted(true);
        BigDecimal share = money(action, "totalAmount").divide(BigDecimal.valueOf(3), 2, java.math.RoundingMode.HALF_UP);
        List<EnvelopeRequest> list = new ArrayList<>();
        for (String name : List.of("Needs", "Wants", "Savings")) {
            EnvelopeRequest envelope = new EnvelopeRequest();
            envelope.setName(name);
            envelope.setExactAmount(share);
            envelope.setConditions(Map.of("type", "monthly", "limit", share));
            list.add(envelope);
        }
        request.setEnvelopes(list);
        budgets.createBudget(request, ctx.user().principal());
        return ok(action, "Budget created.");
    }

    private ExecutionResult withdraw(PreparedAction action, ExecutionContext ctx) {
        User user = users.findByEmail(ctx.user().principal());
        WithdrawalRequest request = new WithdrawalRequest();
        request.setAmount(money(action, "amount"));
        request.setTransactionPin(pin(ctx));
        request.setNarration(stringOr(action, "narration", "AI withdrawal"));
        if (action.params().get("bankCode") != null) {
            request.setBankCode(string(action, "bankCode"));
        }
        if (action.params().get("accountNumber") != null) {
            request.setAccountNumber(string(action, "accountNumber"));
        }
        if (action.params().get("accountName") != null) {
            request.setAccountName(string(action, "accountName"));
        }
        if (action.params().get("bankName") != null) {
            request.setBankName(string(action, "bankName"));
        }
        wallets.processWithdrawal(user.getId(), request);
        return ExecutionResult.pendingProvider(action.idempotencyKey(),
                "Withdrawal submitted.", Map.of());
    }

    private ExecutionResult settlement(PreparedAction action, ExecutionContext ctx) {
        User user = users.findByEmail(ctx.user().principal());
        UpdateBankDetailsRequest request = new UpdateBankDetailsRequest();
        request.setAccountNumber(string(action, "accountNumber"));
        request.setBankCode(string(action, "bankCode"));
        request.setBankName(stringOr(action, "bankName", "Bank"));
        wallets.updateSettlementAccount(user.getId(), request);
        return ok(action, "Payout account updated.");
    }

    private ExecutionResult createSavings(PreparedAction action, ExecutionContext ctx) {
        User user = users.findByEmail(ctx.user().principal());
        BigDecimal initial = action.params().get("initialAmount") == null
                ? BigDecimal.ZERO : money(action, "initialAmount");
        LocalDate maturity = action.params().get("maturityDate") == null
                ? LocalDate.now().plusMonths(6) : LocalDate.parse(string(action, "maturityDate"));
        savings.createSavingsGoal(user.getId(), string(action, "name"),
                money(action, "targetAmount"), initial, maturity, new BigDecimal("0.12"));
        return ok(action, "Savings goal created.");
    }

    private ExecutionResult savingsBank(PreparedAction action, ExecutionContext ctx) {
        User user = users.findByEmail(ctx.user().principal());
        WithdrawalRequest request = new WithdrawalRequest();
        request.setAmount(action.params().get("amount") == null
                ? BigDecimal.ZERO : money(action, "amount"));
        request.setTransactionPin(pin(ctx));
        savings.transferSavingsToBank(user.getId(), longParam(action, "goalId"), request);
        return ExecutionResult.pendingProvider(action.idempotencyKey(),
                "Savings bank transfer submitted.", Map.of());
    }

    private ExecutionResult savingsP2p(PreparedAction action, ExecutionContext ctx) {
        User user = users.findByEmail(ctx.user().principal());
        SavingsP2PTransferRequest request = new SavingsP2PTransferRequest();
        request.setRecipientIdentity(string(action, "recipientIdentity"));
        request.setAmount(action.params().get("amount") == null
                ? null : money(action, "amount"));
        request.setTransactionPin(pin(ctx));
        savings.transferSavingsToUser(user.getId(), longParam(action, "goalId"), request);
        return ok(action, "Savings sent to user.");
    }

    private ExecutionResult sweep(PreparedAction action, ExecutionContext ctx) {
        User user = users.findByEmail(ctx.user().principal());
        long envelopeId = longParam(action, "envelopeId");
        BigDecimal amount = action.params().get("amount") == null
                ? BigDecimal.ZERO : money(action, "amount");
        savings.sweepEnvelopeToSavings(user.getId(), longParam(action, "goalId"),
                amount, "envelope", null, envelopeId);
        return ok(action, "Swept to savings.");
    }

    private ExecutionResult airtime(PreparedAction action, ExecutionContext ctx) {
        User user = users.findByEmail(ctx.user().principal());
        AirtimePurchaseRequest request = new AirtimePurchaseRequest();
        request.setNetwork(string(action, "network"));
        request.setMobileNumber(string(action, "mobileNumber"));
        request.setAmount(money(action, "amount"));
        request.setEnvelopeId(longParam(action, "envelopeId"));
        request.setTransactionPin(pin(ctx));
        vas.purchaseAirtime(user.getId(), request);
        return ok(action, "Airtime purchase submitted.");
    }

    private ExecutionResult data(PreparedAction action, ExecutionContext ctx) {
        User user = users.findByEmail(ctx.user().principal());
        DataPurchaseRequest request = new DataPurchaseRequest();
        request.setDataId(string(action, "planId"));
        request.setMobileNumber(string(action, "mobileNumber"));
        request.setEnvelopeId(longParam(action, "envelopeId"));
        request.setTransactionPin(pin(ctx));
        vas.purchaseData(user.getId(), request);
        return ok(action, "Data purchase submitted.");
    }

    private ExecutionResult profile(PreparedAction action, ExecutionContext ctx) {
        User user = users.findByEmail(ctx.user().principal());
        Map<String, Object> data = user.getProfileData() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(user.getProfileData());
        if (action.params().get("firstName") != null) {
            data.put("firstName", string(action, "firstName"));
        }
        if (action.params().get("lastName") != null) {
            data.put("lastName", string(action, "lastName"));
        }
        user.setProfileData(data);
        if (action.params().get("phone") != null) {
            user.setPhone(string(action, "phone"));
        }
        userRepository.save(user);
        return ok(action, "Profile updated.");
    }

    private static ExecutionResult ok(PreparedAction action, String message) {
        return ExecutionResult.executed(action.idempotencyKey(), message, Map.of("kind", action.kind().name()));
    }

    private static long longParam(PreparedAction action, String key) {
        Object value = action.params().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static BigDecimal money(PreparedAction action, String key) {
        return new BigDecimal(String.valueOf(action.params().get(key)).replace(",", ""));
    }

    private static String string(PreparedAction action, String key) {
        return String.valueOf(action.params().get(key));
    }

    private static String stringOr(PreparedAction action, String key, String fallback) {
        Object value = action.params().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String pin(ExecutionContext ctx) {
        Secret secret = ctx.txPin();
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        return new String(secret.reveal());
    }
}

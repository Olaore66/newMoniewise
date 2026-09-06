package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.model.BudgetView;
import com.moniewise.monnie.api.model.CadenceType;
import com.moniewise.monnie.api.model.EnvelopeConditions;
import com.moniewise.monnie.api.model.EnvelopeView;
import com.moniewise.monnie.api.model.LockState;
import com.moniewise.monnie.api.model.Money;
import com.moniewise.monnie.api.model.SavingsGoalView;
import com.moniewise.monnie.api.model.WalletView;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.SavingsGoal;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.WalletStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

final class SdkViews {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private SdkViews() {
    }

    static Money ngn(BigDecimal value) {
        return Money.ngn(value == null ? BigDecimal.ZERO : value);
    }

    static Instant instant(LocalDateTime value) {
        return value == null ? null : value.atZone(LAGOS).toInstant();
    }

    static BudgetView budget(Budget budget, List<EnvelopeView> envelopes) {
        return new BudgetView(
                budget.getId(),
                budget.getName(),
                ngn(budget.getOriginalAmount()),
                ngn(budget.getFeeAmount()),
                ngn(budget.getTotalAmount()),
                ngn(budget.getAllocatedAmount()),
                ngn(budget.getRemainingAmount()),
                budget.getDurationDays() == null ? 0 : budget.getDurationDays(),
                budget.getStartDate(),
                budget.getEndDate(),
                BudgetView.Status.valueOf(budget.getStatus().name()),
                instant(budget.getCreatedAt()),
                instant(budget.getLastTopupTime()),
                envelopes);
    }

    static EnvelopeView envelope(Envelope envelope) {
        Map<String, Object> raw = envelope.getConditions();
        String type = raw == null ? "daily" : String.valueOf(raw.getOrDefault("type", "daily"));
        CadenceType cadence = CadenceType.fromWire(type).orElse(CadenceType.DAILY);
        Money limit = null;
        if (raw != null && raw.get("limit") != null) {
            limit = ngn(new BigDecimal(String.valueOf(raw.get("limit"))));
        }
        return new EnvelopeView(
                envelope.getId(),
                envelope.getBudget().getId(),
                envelope.getName(),
                ngn(envelope.getAmount()),
                ngn(envelope.getRemainingAmount()),
                ngn(envelope.getTotalRemainingAmount()),
                ngn(envelope.getInitialAmount()),
                ngn(envelope.getHeldAmount()),
                EnvelopeConditions.of(cadence, limit),
                instant(envelope.getNextDisbursementAt()),
                instant(envelope.getLastDisbursedAt()),
                instant(envelope.getMaturedAt()),
                Boolean.TRUE.equals(envelope.getHasMatured()),
                envelope.getDeletedAt() != null);
    }

    static LockState lock(EnvelopeView view, Instant now) {
        if (view.heldAmount() != null && view.heldAmount().isPositive()) {
            return LockState.of(LockState.Kind.VAS_HOLD, null,
                    "Some of this envelope is held for a pending purchase.");
        }
        if (view.nextDisbursementAt() != null && view.nextDisbursementAt().isAfter(now)) {
            return LockState.of(LockState.Kind.AWAITING_DISBURSEMENT, view.nextDisbursementAt(),
                    "This envelope unlocks at the next release.");
        }
        if (!view.spendableToday().isPositive()) {
            return LockState.of(LockState.Kind.NO_PERIOD_FUNDS, view.nextDisbursementAt(),
                    "Nothing is released into this envelope yet.");
        }
        return LockState.unlocked();
    }

    static SavingsGoalView savings(SavingsGoal goal) {
        return new SavingsGoalView(
                goal.getId(),
                goal.getName(),
                ngn(goal.getTargetAmount()),
                ngn(goal.getCurrentBalance()),
                ngn(goal.getAccruedInterest()),
                goal.getInterestRate(),
                goal.getStartDate(),
                goal.getMaturityDate(),
                SavingsGoalView.Status.valueOf(goal.getStatus().name()),
                instant(goal.getCreatedAt()));
    }

    static WalletView wallet(Wallet wallet) {
        WalletView.Status status = wallet.getStatus() == WalletStatus.FROZEN
                ? WalletView.Status.FROZEN
                : WalletView.Status.ACTIVE;
        return new WalletView(
                ngn(wallet.getBalance()),
                ngn(wallet.getBalance()),
                wallet.getCurrency() == null ? "NGN" : wallet.getCurrency(),
                wallet.getAccountNumber(),
                wallet.getBankName(),
                status,
                wallet.getProviderName(),
                wallet.getSettlementAccountNumber(),
                wallet.getSettlementBankName(),
                wallet.getSettlementBankCode(),
                wallet.getSettlementAccountName(),
                instant(wallet.getUpdatedAt()));
    }
}

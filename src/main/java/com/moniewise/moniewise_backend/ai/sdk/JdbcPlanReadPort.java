package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.model.BudgetView;
import com.moniewise.monnie.api.model.CadenceType;
import com.moniewise.monnie.api.model.EnvelopeSpendability;
import com.moniewise.monnie.api.model.EnvelopeView;
import com.moniewise.monnie.api.model.Money;
import com.moniewise.monnie.api.model.PendingDisbursementView;
import com.moniewise.monnie.api.model.SavingsGoalView;
import com.moniewise.monnie.api.model.UserRef;
import com.moniewise.monnie.api.port.ClockPort;
import com.moniewise.monnie.api.port.PlanReadPort;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.PendingDisbursement;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.enums.Status;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.PendingDisbursementRepository;
import com.moniewise.moniewise_backend.repository.SavingsGoalRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.EnvelopeService;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Component
public class JdbcPlanReadPort implements PlanReadPort {

    private final UserService users;
    private final BudgetRepository budgets;
    private final EnvelopeRepository envelopes;
    private final SavingsGoalRepository savings;
    private final WalletRepository wallets;
    private final PendingDisbursementRepository disbursements;
    private final EnvelopeService envelopeService;
    private final SystemConfigService config;
    private final ClockPort clock;

    public JdbcPlanReadPort(UserService users, BudgetRepository budgets, EnvelopeRepository envelopes,
                            SavingsGoalRepository savings, WalletRepository wallets,
                            PendingDisbursementRepository disbursements, EnvelopeService envelopeService,
                            SystemConfigService config, ClockPort clock) {
        this.users = users;
        this.budgets = budgets;
        this.envelopes = envelopes;
        this.savings = savings;
        this.wallets = wallets;
        this.disbursements = disbursements;
        this.envelopeService = envelopeService;
        this.config = config;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BudgetView> budgets(UserRef user) {
        User account = users.findByEmail(user.principal());
        return budgets.findByUserIdWithEnvelopes(account.getId()).stream()
                .map(b -> SdkViews.budget(b, b.getEnvelopes().stream()
                        .filter(e -> e.getDeletedAt() == null)
                        .map(SdkViews::envelope).toList()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BudgetView> budgetsWithStatus(UserRef user, BudgetView.Status status) {
        return budgets(user).stream().filter(b -> b.status() == status).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BudgetView> budget(UserRef user, long budgetId) {
        return budgets(user).stream().filter(b -> b.id() == budgetId).findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public int activeBudgetCount(UserRef user) {
        User account = users.findByEmail(user.principal());
        return budgets.findByUserIdAndStatus(account.getId(), BudgetStatus.ACTIVE).size();
    }

    @Override
    @Transactional(readOnly = true)
    public BudgetCreationRules budgetCreationRules(UserRef user) {
        User account = users.findByEmail(user.principal());
        Money wallet = wallets.findByUserId(account.getId())
                .map(w -> SdkViews.ngn(w.getBalance()))
                .orElse(Money.zeroNgn());
        return new BudgetCreationRules(
                SdkViews.ngn(config.getBigDecimal(SystemConfigService.BUDGET_MIN_AMOUNT, new BigDecimal("5000"))),
                config.getInt(SystemConfigService.BUDGET_MAX_DURATION_DAYS, 730),
                config.getInt(SystemConfigService.BUDGET_MIN_ENVELOPES, 1),
                config.getInt(SystemConfigService.BUDGET_MAX_ENVELOPES, 15),
                SdkViews.ngn(config.getBigDecimal(SystemConfigService.BUDGET_CREATION_FEE, new BigDecimal("500"))),
                30,
                activeBudgetCount(user),
                10,
                wallet,
                List.of(CadenceType.DAILY, CadenceType.WEEKLY, CadenceType.MONTHLY,
                        CadenceType.DYNAMIC, CadenceType.EMERGENCY));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EnvelopeView> envelopes(UserRef user, long budgetId) {
        User account = users.findByEmail(user.principal());
        Budget budget = budgets.findById(budgetId).orElse(null);
        if (budget == null || !budget.getUser().getId().equals(account.getId())) {
            return List.of();
        }
        return envelopes.findByBudgetId(budgetId).stream()
                .filter(e -> e.getDeletedAt() == null)
                .map(SdkViews::envelope)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EnvelopeView> envelope(UserRef user, long envelopeId) {
        return envelopes.findByIdAndBudget_UserEmail(envelopeId, user.principal())
                .filter(e -> e.getDeletedAt() == null)
                .map(SdkViews::envelope);
    }

    @Override
    @Transactional(readOnly = true)
    public EnvelopeSpendability spendability(UserRef user, long envelopeId) {
        EnvelopeView view = envelope(user, envelopeId)
                .orElseThrow(() -> new IllegalArgumentException("no envelope " + envelopeId));
        Money remaining = remainingLimit(user, envelopeId).orElse(null);
        return EnvelopeSpendability.from(view, SdkViews.lock(view, clock.now()), remaining);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Money> remainingLimit(UserRef user, long envelopeId) {
        try {
            return Optional.of(SdkViews.ngn(envelopeService.getRemainingLimit(envelopeId, user.principal())));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<PendingDisbursementView> pendingDisbursements(UserRef user) {
        User account = users.findByEmail(user.principal());
        return disbursements.findPendingActive(java.time.LocalDateTime.now()).stream()
                .filter(p -> account.getId().equals(p.getUserId()))
                .map(this::pending)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PendingDisbursementView> pendingDisbursement(UserRef user, long envelopeId) {
        return disbursements.findFirstByEnvelopeIdAndStatus(envelopeId, Status.PENDING)
                .filter(p -> {
                    User account = users.findByEmail(user.principal());
                    return account.getId().equals(p.getUserId());
                })
                .map(this::pending);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SavingsGoalView> savingsGoals(UserRef user, boolean activeOnly) {
        User account = users.findByEmail(user.principal());
        var list = activeOnly
                ? savings.findByUserIdAndStatus(account.getId(), SavingsStatus.ACTIVE)
                : savings.findByUserIdOrderByCreatedAtDesc(account.getId());
        return list.stream().map(SdkViews::savings).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SavingsGoalView> savingsGoal(UserRef user, long goalId) {
        User account = users.findByEmail(user.principal());
        return savings.findById(goalId)
                .filter(g -> g.getUser().getId().equals(account.getId()))
                .map(SdkViews::savings);
    }

    private PendingDisbursementView pending(PendingDisbursement p) {
        return new PendingDisbursementView(
                p.getId(),
                p.getEnvelopeId(),
                p.getEnvelopeName(),
                SdkViews.ngn(p.getAmount()),
                SdkViews.instant(p.getMaturedAt()),
                SdkViews.instant(p.getExpiresAt()),
                PendingDisbursementView.Status.PENDING);
    }
}

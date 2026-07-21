package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.SavingsGoal;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.enums.WithdrawalStatus;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.SavingsGoalRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WithdrawalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates account deletion as a deliberate "I'm leaving" flow — the one
 * place a commitment can be unwound. It reuses the already-tested refund and
 * withdraw paths (so no money logic is reinvented) and never lets a user lose
 * money: leaving returns every locked naira (savings principal, budget balances)
 * to the wallet first, forfeiting only unvested savings bonus.
 */
@Service
public class AccountDeletionService {

    private static final Logger logger = LoggerFactory.getLogger(AccountDeletionService.class);

    /** ₦100 withdrawal minimum. Below this a wallet balance is un-withdrawable
     *  dust that must not permanently block deletion. */
    private static final BigDecimal MIN_WITHDRAWABLE_BALANCE = new BigDecimal("100");

    /** A withdrawal left INITIATED/PROCESSING longer than this is treated as stuck
     *  (e.g. a lost provider webhook) so it can never permanently block account
     *  closure. NIP transfers settle in seconds–minutes, so this is deliberately
     *  generous; account deletion is a soft delete and the withdrawal record
     *  survives it, so proceeding past a stale one loses nothing. */
    private static final Duration WITHDRAWAL_INFLIGHT_WINDOW = Duration.ofHours(2);

    public enum Outcome { DELETED, WITHDRAWAL_REQUIRED }

    public record Result(Outcome outcome, BigDecimal walletBalance) {}

    private final UserService userService;
    private final UserRepository userRepository;
    private final BudgetService budgetService;
    private final SavingsService savingsService;
    private final WalletService walletService;
    private final BudgetRepository budgetRepository;
    private final SavingsGoalRepository savingsGoalRepository;
    private final WithdrawalRepository withdrawalRepository;

    public AccountDeletionService(UserService userService, UserRepository userRepository,
                                  BudgetService budgetService, SavingsService savingsService,
                                  WalletService walletService, BudgetRepository budgetRepository,
                                  SavingsGoalRepository savingsGoalRepository,
                                  WithdrawalRepository withdrawalRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.budgetService = budgetService;
        this.savingsService = savingsService;
        this.walletService = walletService;
        this.budgetRepository = budgetRepository;
        this.savingsGoalRepository = savingsGoalRepository;
        this.withdrawalRepository = withdrawalRepository;
    }

    /**
     * Attempts to close the account. Blocks only while an async withdrawal is
     * still settling; otherwise it breaks active savings (principal to the wallet,
     * accrued bonus forfeited), dissolves active budgets, and rakes matured
     * savings into the wallet, then either closes the
     * account (nothing withdrawable left) or returns WITHDRAWAL_REQUIRED so the
     * client can send the consolidated balance to the user's bank first.
     *
     * <p>Idempotent and async-confirmed: the client calls it again after the
     * withdrawal clears — the in-flight guard means it will only finalize once
     * the transfer has actually COMPLETED (or FAILED, in which case the money is
     * back in the wallet and it asks for withdrawal again).
     */
    @Transactional
    public Result deleteAccount(String email, String reason, String transactionPin) {
        User user = userService.findByEmail(email);
        Long userId = user.getId();

        // Closing an account moves real money — it breaks savings, dissolves
        // budgets and then empties the wallet — so prove it is the account owner
        // first, with the same transaction PIN every other money movement needs.
        if (transactionPin == null || transactionPin.isBlank()) {
            throw new IllegalArgumentException("Transaction PIN is required to close your account.");
        }
        if (!userService.verifyTransactionPin(user, transactionPin)) {
            throw new IllegalArgumentException("Incorrect transaction PIN.");
        }

        // Async-confirmed close: never finalize (or dissolve further) while a
        // withdrawal is genuinely still settling — wait for it to COMPLETE or FAIL.
        // Bounded by a staleness window so a stuck INITIATED/PROCESSING withdrawal
        // (e.g. a lost provider webhook) can't trap the account closed forever.
        LocalDateTime inFlightCutoff = LocalDateTime.now().minus(WITHDRAWAL_INFLIGHT_WINDOW);
        boolean withdrawalInFlight = withdrawalRepository.existsByUserIdAndStatusInAndCreatedAtAfter(
                userId, List.of(WithdrawalStatus.INITIATED, WithdrawalStatus.PROCESSING), inFlightCutoff);
        if (withdrawalInFlight) {
            throw new IllegalStateException(
                    "A withdrawal is still processing. Please try again once it completes.");
        }

        // Break ACTIVE (still-maturing) savings on the way out: principal returns
        // to the wallet, any accrued bonus is forfeited. Account closure is the one
        // place the maturity lock is lifted, so a departing user can always recover
        // their principal.
        for (SavingsGoal goal : savingsGoalRepository.findByUserIdAndStatus(userId, SavingsStatus.ACTIVE)) {
            savingsService.breakActiveSavingsToWallet(userId, goal.getId());
        }

        // Dissolve active budgets → wallet (refund unused balance, then remove).
        for (Budget budget : budgetRepository.findByUserIdAndStatus(userId, BudgetStatus.ACTIVE)) {
            budgetService.deleteBudget(budget.getId(), email);
        }

        // Rake matured (unwithdrawn) savings → wallet.
        for (SavingsGoal goal : savingsGoalRepository.findByUserIdAndStatus(userId, SavingsStatus.MATURED)) {
            savingsService.withdrawSavings(userId, goal.getId(), null);
        }

        // Capture the reason for analytics/compliance in the profile blob (no
        // schema change). Kept whether we close now or after a withdrawal.
        if (reason != null && !reason.isBlank()) {
            Map<String, Object> profile = user.getProfileData() != null
                    ? user.getProfileData() : new HashMap<>();
            profile.put("deletionReason", reason.trim());
            user.setProfileData(profile);
            userRepository.save(user);
        }

        // Everything is now consolidated in the wallet. A withdrawable balance
        // (>= ₦100) must go to the user's bank via the normal withdraw flow
        // first — we don't close until it's gone. Sub-₦100 dust is
        // un-withdrawable and does not block; it stays in the soft-deleted wallet
        // and is recoverable if the user reactivates.
        BigDecimal balance = safeBalance(userId);
        if (balance.compareTo(MIN_WITHDRAWABLE_BALANCE) >= 0) {
            return new Result(Outcome.WITHDRAWAL_REQUIRED, balance);
        }

        user.setDeleted(true);
        userRepository.save(user);
        logger.info("Soft-deleted user account: {}", email);
        return new Result(Outcome.DELETED, balance);
    }

    private BigDecimal safeBalance(Long userId) {
        try {
            BigDecimal balance = walletService.checkBalance(userId);
            return balance == null ? BigDecimal.ZERO : balance;
        } catch (Exception ignored) {
            // No wallet / lookup issue — treat as no balance; never block on this.
            return BigDecimal.ZERO;
        }
    }
}

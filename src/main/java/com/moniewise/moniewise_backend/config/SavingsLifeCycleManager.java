package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.SavingsGoal;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.repository.SavingsGoalRepository;
import com.moniewise.moniewise_backend.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class SavingsLifeCycleManager {

    private static final Logger logger = LoggerFactory.getLogger(SavingsLifeCycleManager.class);

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final SavingsGoalRepository savingsGoalRepository;
    private final NotificationService notificationService;

    public SavingsLifeCycleManager(SavingsGoalRepository savingsGoalRepository,
                                   NotificationService notificationService) {
        this.savingsGoalRepository = savingsGoalRepository;
        this.notificationService = notificationService;
    }

    /**
     * Runs every 4 hours (not once at 1AM): if the instance was asleep or
     * restarting when a run was due — common on hosted platforms — the next
     * run a few hours later self-heals the day. Per-goal idempotency via
     * {@link SavingsGoal#getLastProcessedDate()} guarantees interest is still
     * applied at most once per calendar day (Africa/Lagos).
     */
    @Scheduled(cron = "0 0 */4 * * *")
    @Transactional
    public void processDailySavings() {
        logger.info("🌤️ Waking up Savings Engine: Calculating interest & checking maturities...");

        LocalDate today = LocalDate.now(LAGOS);
        int pageNumber = 0;
        int pageSize = 100; // Process 100 pots at a time to save RAM
        Page<SavingsGoal> page;

        do {
            Pageable pageable = PageRequest.of(pageNumber, pageSize);
            page = savingsGoalRepository.findByStatus(SavingsStatus.ACTIVE, pageable);
            List<SavingsGoal> activeGoals = page.getContent();

            for (SavingsGoal goal : activeGoals) {
                try {
                    // Idempotency guard — this goal already got its daily pass.
                    if (goal.getLastProcessedDate() != null
                            && !goal.getLastProcessedDate().isBefore(today)) {
                        continue;
                    }

                    // ==========================================
                    // 1. THE WEALTH BUILDER (Calculate Daily Interest)
                    // ==========================================
                    if (goal.getInterestRate().compareTo(BigDecimal.ZERO) > 0 && goal.getCurrentBalance().compareTo(BigDecimal.ZERO) > 0) {

                        // Formula: (Balance * (Rate / 100)) / 365
                        BigDecimal annualInterest = goal.getCurrentBalance()
                                .multiply(goal.getInterestRate())
                                .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);

                        BigDecimal dailyInterest = annualInterest.divide(new BigDecimal("365"), 6, RoundingMode.HALF_UP);

                        // Add it to their accrued interest pot
                        goal.setAccruedInterest(goal.getAccruedInterest().add(dailyInterest));
                    }

                    long daysUntilMaturity = ChronoUnit.DAYS.between(today, goal.getMaturityDate());

                    // ==========================================
                    // 1.5 THE HEADS-UP CALLS (7 days + 1 day to maturity)
                    // Email AND push for both — the push was missing entirely.
                    // ==========================================
                    if (!goal.isMaturityReminderSent()
                            && daysUntilMaturity > 1 && daysUntilMaturity <= 7) {
                        sendMaturingSoonEmail(goal, daysUntilMaturity);
                        sendMaturingSoonPush(goal, daysUntilMaturity);
                        goal.setMaturityReminderSent(true);
                    }

                    if (!goal.isMaturityEveReminderSent() && daysUntilMaturity == 1) {
                        sendMaturingSoonEmail(goal, 1);
                        sendMaturingSoonPush(goal, 1);
                        goal.setMaturityEveReminderSent(true);
                        // A goal created <2 days before maturity skips the 7-day window;
                        // close that flag too so nothing tries to send it after the fact.
                        goal.setMaturityReminderSent(true);
                    }

                    // ==========================================
                    // 2. THE ALARM CLOCK (Check for Maturity)
                    // ==========================================
                    if (!today.isBefore(goal.getMaturityDate())) { // If today is >= maturity date
                        matureGoal(goal);
                    }

                    goal.setLastProcessedDate(today);
                    savingsGoalRepository.save(goal);

                } catch (Exception e) {
                    // We catch inside the loop so one bad pot doesn't crash the whole batch
                    logger.error("❌ Failed to process Savings Goal ID: {}", goal.getId(), e);
                }
            }

            pageNumber++;
        } while (page.hasNext());

        logger.info("💤 Savings Engine finished daily processing.");
    }

    /**
     * Flips an ACTIVE goal to MATURED and fires the maturity comms (push + email),
     * exactly once — the status flip is the guard. Called from the scheduled job
     * above AND lazily from SavingsService when goals are fetched, so the withdraw
     * UI never depends on the cron having run.
     */
    @Transactional
    public void matureGoal(SavingsGoal goal) {
        if (goal.getStatus() != SavingsStatus.ACTIVE) {
            return; // already matured/withdrawn by another path
        }
        goal.setStatus(SavingsStatus.MATURED);
        savingsGoalRepository.save(goal);

        logger.info("🎉 Savings Goal '{}' (ID: {}) for User {} has MATURED!",
                goal.getName(), goal.getId(), goal.getUser().getId());

        sendMaturityNotification(goal);
        sendMaturedEmail(goal);
    }

    /**
     * Fires the "your savings is maturing soon" email — once at ~7 days and once
     * the day before maturity (each guarded by its own flag on the goal).
     */
    private void sendMaturingSoonEmail(SavingsGoal goal, long daysRemaining) {
        var user = goal.getUser();
        if (user.getEmail() == null || user.getEmail().isBlank()) return;

        String firstName = user.getName();
        if (firstName == null || firstName.isBlank()) {
            String username = user.getEmail().split("@")[0];
            firstName = username.isEmpty() ? "there" : username.substring(0, 1).toUpperCase() + username.substring(1);
        }

        BigDecimal projectedPayout = goal.getCurrentBalance().add(goal.getAccruedInterest());
        String maturityDateLabel = goal.getMaturityDate().format(DateTimeFormatter.ofPattern("d MMMM yyyy"));

        notificationService.sendSavingsMaturingSoonEmail(
                user.getEmail(),
                firstName,
                goal.getName(),
                goal.getCurrentBalance(),
                goal.getAccruedInterest(),
                projectedPayout,
                maturityDateLabel,
                (int) daysRemaining
        );

        logger.info("📧 Sent 'maturing soon' email for Savings Goal '{}' (ID: {}) to {} — {} day(s) left",
                goal.getName(), goal.getId(), user.getEmail(), daysRemaining);
    }

    /** Push twin of the maturing-soon email (7-day and 1-day heads-ups). */
    private void sendMaturingSoonPush(SavingsGoal goal, long daysRemaining) {
        BigDecimal projectedPayout = goal.getCurrentBalance().add(goal.getAccruedInterest());
        String maturityDateLabel = goal.getMaturityDate().format(DateTimeFormatter.ofPattern("d MMM yyyy"));

        String message = daysRemaining == 1
                ? String.format("⏳ '%s' matures tomorrow (%s). ₦%,.2f will be ready to withdraw.",
                        goal.getName(), maturityDateLabel, projectedPayout)
                : String.format("⏳ '%s' matures in %d days (%s). Projected payout: ₦%,.2f.",
                        goal.getName(), daysRemaining, maturityDateLabel, projectedPayout);

        notificationService.sendNotification(
                goal.getUser().getId().toString(),
                message,
                NotificationType.SAVINGS_MATURING_SOON,
                null,
                null,
                "VIEW_SAVINGS",
                "/savings/" + goal.getId()
        );
    }

    /**
     * Fires the "your savings has matured" email — distinct from
     * sendMaturityNotification() below, which only sends the push/in-app one.
     */
    private void sendMaturedEmail(SavingsGoal goal) {
        var user = goal.getUser();
        if (user.getEmail() == null || user.getEmail().isBlank()) return;

        String firstName = user.getName();
        if (firstName == null || firstName.isBlank()) {
            String username = user.getEmail().split("@")[0];
            firstName = username.isEmpty() ? "there" : username.substring(0, 1).toUpperCase() + username.substring(1);
        }

        BigDecimal totalPayout = goal.getCurrentBalance().add(goal.getAccruedInterest());

        notificationService.sendSavingsMaturedEmail(
                user.getEmail(),
                firstName,
                goal.getName(),
                goal.getCurrentBalance(),
                goal.getAccruedInterest(),
                totalPayout
        );
    }

    /**
     * Push + in-app inbox for maturity. Single canonical send — the old duplicate
     * GenericNotificationEvent publish was removed (it produced a second inbox row
     * and push for the same maturity).
     */
    private void sendMaturityNotification(SavingsGoal goal) {
        String userId = goal.getUser().getId().toString();
        BigDecimal totalPayout = goal.getCurrentBalance().add(goal.getAccruedInterest());

        String message = String.format(
                "🎉 Congratulations! Your '%s' savings plan has matured. ₦%,.2f is ready to be withdrawn!",
                goal.getName(), totalPayout
        );

        notificationService.sendNotification(
                userId,
                message,
                NotificationType.SAVINGS_MATURED,
                null,
                null,
                "VIEW_SAVINGS",
                "/savings/" + goal.getId()
        );
    }
}

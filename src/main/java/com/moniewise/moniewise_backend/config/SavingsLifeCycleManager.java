package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.SavingsGoal;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.repository.SavingsGoalRepository;
import com.moniewise.moniewise_backend.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class SavingsLifeCycleManager {

    private static final Logger logger = LoggerFactory.getLogger(SavingsLifeCycleManager.class);

    private final SavingsGoalRepository savingsGoalRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    public SavingsLifeCycleManager(SavingsGoalRepository savingsGoalRepository,
                                   NotificationService notificationService,
                                   ApplicationEventPublisher eventPublisher) {
        this.savingsGoalRepository = savingsGoalRepository;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Runs every day at 1:00 AM server time.
     * CRON syntax: Seconds Minutes Hours DayOfMonth Month DayOfWeek
     */
    @Scheduled(cron = "0 0 1 * * ?")
    @Transactional
    public void processDailySavings() {
        logger.info("🌤️ Waking up Savings Engine: Calculating interest & checking maturities...");

        LocalDate today = LocalDate.now();
        int pageNumber = 0;
        int pageSize = 100; // Process 100 pots at a time to save RAM
        Page<SavingsGoal> page;

        do {
            Pageable pageable = PageRequest.of(pageNumber, pageSize);
            page = savingsGoalRepository.findByStatus(SavingsStatus.ACTIVE, pageable);
            List<SavingsGoal> activeGoals = page.getContent();

            for (SavingsGoal goal : activeGoals) {
                try {
                    boolean isUpdated = false;

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
                        isUpdated = true;
                    }

                    // ==========================================
                    // 2. THE ALARM CLOCK (Check for Maturity)
                    // ==========================================
                    if (!today.isBefore(goal.getMaturityDate())) { // If today is >= maturity date
                        goal.setStatus(SavingsStatus.MATURED);
                        isUpdated = true;

                        logger.info("🎉 Savings Goal '{}' (ID: {}) for User {} has MATURED!", 
                                goal.getName(), goal.getId(), goal.getUser().getId());

                        // Send Push Notification
                        sendMaturityNotification(goal);
                    }

                    // Save if changes were made
                    if (isUpdated) {
                        savingsGoalRepository.save(goal);
                    }

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
     * Helper method to fire off the good news to the user.
     */
    private void sendMaturityNotification(SavingsGoal goal) {
        String userId = goal.getUser().getId().toString();
        BigDecimal totalPayout = goal.getCurrentBalance().add(goal.getAccruedInterest());
        
        String message = String.format(
                "🎉 Congratulations! Your '%s' savings plan has matured. ₦%,.2f is ready to be withdrawn!",
                goal.getName(), totalPayout
        );

        // Standard direct FCM Push
        notificationService.sendNotification(
                userId,
                message,
                NotificationType.SAVINGS_MATURED, // Add this to your Enum!
                goal.getId(),
                null,
                "VIEW_SAVINGS",
                "/savings/" + goal.getId()
        );

        // Broadcast Event (If your app listens for this to update UI in real-time)
        Map<String, Object> params = new HashMap<>();
        params.put("goalName", goal.getName());
        params.put("totalAmount", totalPayout);

        eventPublisher.publishEvent(new GenericNotificationEvent(
                this, userId, NotificationType.SAVINGS_MATURED,
                params, goal.getId(), null, "/savings/" + goal.getId()
        ));
    }
}
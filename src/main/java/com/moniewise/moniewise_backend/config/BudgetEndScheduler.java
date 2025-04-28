package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.service.NotificationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import java.util.Map;

@Component
public class BudgetEndScheduler {

    private static final Logger logger = LoggerFactory.getLogger(BudgetEndScheduler.class);

    private final BudgetRepository budgetRepository;
    private final EnvelopeRepository envelopeRepository;
    private final NotificationService notificationService;

    public BudgetEndScheduler(BudgetRepository budgetRepository, EnvelopeRepository envelopeRepository,
                              NotificationService notificationService) {
        this.budgetRepository = budgetRepository;
        this.envelopeRepository = envelopeRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    @Scheduled(cron = "0 0 0 * * ?") // Daily at midnight
    public void checkBudgetEndDates() {
        LocalDate today = LocalDate.now();
        budgetRepository.findByStatusAndEndDateLessThanEqual(BudgetStatus.ACTIVE, today)
                .forEach(budget -> {
                    budget.setStatus(BudgetStatus.COMPLETED);
                    budgetRepository.save(budget);
                    notificationService.sendNotification(budget.getUser().getId().toString(),
                            String.format("Budget '%s' has ended!", budget.getName()));
                    logger.info("Budget {} marked as completed", budget.getId());
                });

        // Notify envelope access
        List<Envelope> envelopes = envelopeRepository.findAll();
        for (Envelope envelope : envelopes) {
            Map<String, Object> conditions = envelope.getConditions();
            String type = (String) conditions.get("type");
            LocalDateTime lastAccessed = envelope.getLastAccessed() != null ? envelope.getLastAccessed() : LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
            String userId = envelope.getBudget().getUser().getId().toString();
            String message = null;

            switch (type) {
                case "daily":
                    if (!lastAccessed.toLocalDate().equals(today)) {
                        message = String.format("Hurray, your %s money is here!", envelope.getName().toLowerCase());
                    }
                    break;
                case "weekly":
                    LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1);
                    if (lastAccessed.isBefore(weekStart.atStartOfDay())) {
                        message = String.format("Your %s for this week is here!", envelope.getName().toLowerCase());
                    }
                    break;
                case "dynamic":
                    LocalDate dynamicStart = LocalDate.parse((String) conditions.get("startDate"));
                    int interval = Integer.parseInt(conditions.get("intervalDays").toString());
                    LocalTime disbursementTime = LocalTime.parse((String) conditions.get("disbursementTime"));

                    // Check if today matches interval day
                    long daysSinceStart = ChronoUnit.DAYS.between(dynamicStart, today);
                    if (daysSinceStart % interval == 0
                            && LocalTime.now().isAfter(disbursementTime.minusMinutes(5))) {
                        message = String.format("Dynamic disbursement for %s!", envelope.getName());
                    }
                    break;
                case "safe_lock":
                case "strict_lock":
                    LocalDate lockStart = LocalDate.parse((String) conditions.get("lockStartDate"));
                    int lockDays = Integer.parseInt(conditions.get("lockDurationDays").toString());
                    LocalDate unlockDate = lockStart.plusDays(lockDays);

                    if (today.equals(unlockDate)) {
                        BigDecimal interest = envelope.getAmount()
                                .multiply((BigDecimal) conditions.get("interestRate"))
                                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

                        // Credit interest to envelope
                        envelope.setRemainingAmount(envelope.getRemainingAmount().add(interest));
                        envelopeRepository.save(envelope);

                        message = String.format("Lock lifted on %s! ₦%.2f interest added",
                                envelope.getName(), interest);
                    }
                    break;
            }

            if (message != null) {
                notificationService.sendNotification(userId, message);
                logger.info("Sent notification for envelope {}: {}", envelope.getId(), message);
            }
        }
    }
}
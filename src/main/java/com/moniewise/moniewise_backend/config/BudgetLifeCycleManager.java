package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.ScheduledTask;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.repository.*;
import com.moniewise.moniewise_backend.service.NotificationService;
import com.moniewise.moniewise_backend.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class BudgetLifeCycleManager {

    private static final Logger logger = LoggerFactory.getLogger(BudgetLifeCycleManager.class);
    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    private final BudgetRepository budgetRepository;
    private final EnvelopeRepository envelopeRepository;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final WalletService walletService;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final NotificationRepository notificationRepository;

    public BudgetLifeCycleManager(
            BudgetRepository budgetRepository,
            EnvelopeRepository envelopeRepository,
            ScheduledTaskRepository scheduledTaskRepository,
            TransactionLogRepository transactionLogRepository,
            WalletService walletService,
            NotificationService notificationService,
            TransactionTemplate transactionTemplate,
            JdbcTemplate jdbcTemplate,
            NotificationRepository notificationRepository) {
        this.budgetRepository = budgetRepository;
        this.envelopeRepository = envelopeRepository;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.walletService = walletService;
        this.notificationService = notificationService;
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.notificationRepository = notificationRepository;
    }

    @PostConstruct
    public void init() {
        logger.info("Revenue Wallet User ID: {}", revenueWalletUserId);
    }

    private LocalDateTime fetchCurrentDateTimeFromDatabase() {
        String sql = "SELECT CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Lagos'";
        return jdbcTemplate.queryForObject(sql, LocalDateTime.class);
    }

    /**
     * Schedule tasks for dynamic envelopes.
     */
    private void scheduleDynamicTasks(Envelope envelope) {
        Map<String, Object> conditions = envelope.getConditions();
        if (conditions != null && "dynamic".equals(conditions.get("type"))) {
            scheduledTaskRepository.deleteByEnvelopeId(envelope.getId()); // Clear old tasks
            @SuppressWarnings("unchecked")
            List<String> days = (List<String>) conditions.get("days");
            String disbursementTime = (String) conditions.get("disbursementTime");
            if (days == null || days.isEmpty() || disbursementTime == null) {
                logger.warn("Invalid conditions for dynamic envelope {}: missing or empty days or disbursementTime", envelope.getId());
                return;
            }
            LocalTime time;
            try {
                time = LocalTime.parse(disbursementTime);
            } catch (DateTimeParseException e) {
                logger.error("Invalid disbursementTime format for envelope {}: {}", envelope.getId(), disbursementTime);
                return;
            }
            LocalDateTime now = fetchCurrentDateTimeFromDatabase();
            LocalDate today = now.toLocalDate();
            for (int i = 0; i < 8; i++) {
                LocalDateTime next = now.plusDays(i);
                if (days.contains(next.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US))) {
                    LocalDateTime triggerTime = next.toLocalDate().atTime(time);
                    // Schedule 15-minute pre-disbursement notification
                    if (triggerTime.isAfter(now)) {
                        ScheduledTask preTask = new ScheduledTask();
                        preTask.setEnvelopeId(envelope.getId());
                        preTask.setTaskType("PRE_DISBURSEMENT_NOTIFICATION");
                        preTask.setTriggerTime(triggerTime.minusMinutes(15));
                        preTask.setCreatedAt(now);
                        scheduledTaskRepository.save(preTask);
                    }
                    // Schedule actual disbursement
                    ScheduledTask task = new ScheduledTask();
                    task.setEnvelopeId(envelope.getId());
                    task.setTaskType("DISBURSEMENT");
                    task.setTriggerTime(triggerTime);
                    task.setCreatedAt(now);
                    scheduledTaskRepository.save(task);
                }
            }
        }
    }

    /**
     * Runs every 5 minutes to process budgets and send 3-day end notifications.
     */
    @Transactional(timeout = 120)
    @Scheduled(cron = "0 */5 * * * ?")
    public void processBudgets() {
        long startTime = System.nanoTime();
        LocalDate today = LocalDate.now();
        LocalDate threeDaysFromNow = today.plusDays(3);
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        logger.debug("Starting budget lifecycle processing at {}", now);

        // Step 1: Send notifications for budgets ending in 3 days
        List<Budget> budgetsNearingEnd = budgetRepository.findByStatusAndEndDate(BudgetStatus.ACTIVE, threeDaysFromNow);
        for (Budget budget : budgetsNearingEnd) {
            String message = String.format("Budget '%s' will end in 3 days on %s.", budget.getName(), budget.getEndDate());
            notificationService.sendNotification(budget.getUser().getId().toString(), message, "BUDGET_END");
            logger.debug("Sent 3-day end notification for budget {} to user {}", budget.getId(), budget.getUser().getId());
        }

        // Step 2: Process expired budgets
        List<Budget> expiredBudgets = budgetRepository.findByStatusAndEndDateLessThanEqual(BudgetStatus.ACTIVE, today);
        List<Budget> budgetsToUpdate = new ArrayList<>();
        List<Envelope> envelopesToUpdate = new ArrayList<>();
        List<TransactionLog> logsToSave = new ArrayList<>();

        for (Budget budget : expiredBudgets) {
            transactionTemplate.execute(status -> {
                try {
                    processBudgetExpiry(budget, budgetsToUpdate, envelopesToUpdate, logsToSave);
                    return null;
                } catch (Exception e) {
                    logger.error("Failed to process budget expiry for budget {}: {}", budget.getId(), e.getMessage());
                    throw new RuntimeException("Budget expiry processing failed", e);
                }
            });
        }

        // Step 3: Process non-time-critical envelopes (daily, weekly, safe/strict lock)
        try (Stream<Envelope> envelopeStream = envelopeRepository.findAllByStream()) {
            envelopeStream.forEach(envelope -> {
                Map<String, Object> conditions = envelope.getConditions();
                if (conditions != null && conditions.containsKey("type") && !"dynamic".equals(conditions.get("type"))) {
                    transactionTemplate.execute(status -> {
                        try {
                            processEnvelopeDisbursement(envelope, today, envelopesToUpdate, logsToSave);
                            return null;
                        } catch (Exception e) {
                            logger.error("Failed to process envelope {}: {}", envelope.getId(), e.getMessage());
                            throw new RuntimeException("Envelope disbursement processing failed", e);
                        }
                    });
                }
            });
        }

        // Step 4: Refresh tasks for active dynamic envelopes
        List<Envelope> dynamicEnvelopes = envelopeRepository.findByConditionsType("dynamic");
        for (Envelope envelope : dynamicEnvelopes) {
            Budget budget = envelope.getBudget();
            if (budget != null && budget.getStatus() == BudgetStatus.ACTIVE && !budget.getEndDate().isBefore(today)) {
                scheduleDynamicTasks(envelope);
            }
        }

        // Batch save updates
        if (!budgetsToUpdate.isEmpty()) {
            budgetRepository.saveAll(budgetsToUpdate);
        }
        if (!envelopesToUpdate.isEmpty()) {
            envelopeRepository.saveAll(envelopesToUpdate);
        }
        if (!logsToSave.isEmpty()) {
            transactionLogRepository.saveAll(logsToSave);
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        logger.debug("Budget lifecycle processing completed in {}ms. Processed {} budgets, {} envelopes, {} nearing-end notifications",
                durationMs, expiredBudgets.size(), envelopesToUpdate.size(), budgetsNearingEnd.size());
    }

    /**
     * Runs every minute to process time-critical tasks (e.g., dynamic envelopes and pre-disbursement notifications).
     */
    @Transactional(timeout = 120)
    @Scheduled(cron = "0 * * * * ?")
    public void processScheduledTasks() {
        long startTime = System.nanoTime();
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        logger.debug("Starting scheduled tasks processing at {}", now);

        List<ScheduledTask> dueTasks = scheduledTaskRepository.findTasksDueBy(now);
        List<Envelope> envelopesToUpdate = new ArrayList<>();
        List<TransactionLog> logsToSave = new ArrayList<>();
        List<Long> taskIdsToDelete = new ArrayList<>();

        for (ScheduledTask task : dueTasks) {
            transactionTemplate.execute(status -> {
                try {
                    Envelope envelope = envelopeRepository.findById(task.getEnvelopeId()).orElse(null);
                    if (envelope == null) {
                        logger.warn("Envelope {} not found for task {}", task.getEnvelopeId(), task.getId());
                        taskIdsToDelete.add(task.getId());
                        return null;
                    }
                    Budget budget = envelope.getBudget();
                    if (budget == null || budget.getStatus() != BudgetStatus.ACTIVE) {
                        logger.warn("Skipping task {} for envelope {}: budget is not active", task.getId(), task.getEnvelopeId());
                        taskIdsToDelete.add(task.getId());
                        return null;
                    }
                    String userId = budget.getUser().getId().toString();
                    if ("PRE_DISBURSEMENT_NOTIFICATION".equals(task.getTaskType())) {
                        String message = String.format("Your '%s' envelope disbursement of ₦%s is 15 minutes away!",
                                envelope.getName(), envelope.getConditions().get("limit"));
                        notificationService.sendNotification(userId, message, "PRE_DISBURSEMENT");
                        logger.debug("Sent 15-minute pre-disbursement notification for envelope {}: {}", envelope.getId(), message);
                        taskIdsToDelete.add(task.getId());
                    } else if ("DISBURSEMENT".equals(task.getTaskType())) {
                        processEnvelopeDisbursement(envelope, now.toLocalDate(), envelopesToUpdate, logsToSave);
                        taskIdsToDelete.add(task.getId());
                    }
                    return null;
                } catch (Exception e) {
                    logger.error("Failed to process task {} for envelope {}: {}", task.getId(), task.getEnvelopeId(), e.getMessage());
                    throw new RuntimeException("Task processing failed", e);
                }
            });
        }

        // Batch save and delete
        if (!envelopesToUpdate.isEmpty()) {
            envelopeRepository.saveAll(envelopesToUpdate);
        }
        if (!logsToSave.isEmpty()) {
            transactionLogRepository.saveAll(logsToSave);
        }
        if (!taskIdsToDelete.isEmpty()) {
            scheduledTaskRepository.deleteAllById(taskIdsToDelete);
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        logger.debug("Scheduled tasks processing completed in {}ms. Processed {} tasks", durationMs, dueTasks.size());
    }

    /**
     * Processes budget expiry:
     * - Refunds unused envelope balances
     * - Marks budget as completed
     */
    private void processBudgetExpiry(Budget budget, List<Budget> budgetsToUpdate, List<Envelope> envelopesToUpdate,
                                     List<TransactionLog> logsToSave) {
        User user = budget.getUser();
        BigDecimal unusedAmount = budget.getEnvelopes().stream()
                .map(Envelope::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        // Refund unused funds to user's wallet
        if (unusedAmount.compareTo(BigDecimal.ZERO) > 0) {
            walletService.fundWallet(user.getId(), unusedAmount,
                    "Unused balance refund from budget: " + budget.getName());

            // Log refund transaction
            TransactionLog refundLog = new TransactionLog();
            refundLog.setUserId(user.getId());
            refundLog.setBudgetId(budget.getId());
            refundLog.setAmount(unusedAmount);
            refundLog.setTransactionType("budget_expiry_refunded");
            refundLog.setCreatedAt(now);
            logsToSave.add(refundLog);

            // Reset envelope balances
            for (Envelope envelope : budget.getEnvelopes()) {
                if (envelope.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0) {
                    envelope.setRemainingAmount(BigDecimal.ZERO);
                    envelopesToUpdate.add(envelope);
                    scheduledTaskRepository.deleteByEnvelopeId(envelope.getId()); // Clean up tasks
                }
            }
        }

        // Mark budget as completed
        budget.setStatus(BudgetStatus.COMPLETED);
        budgetsToUpdate.add(budget);

        logger.info("Budget {} completed for user {}. Refunded ₦{}", budget.getId(), user.getId(), unusedAmount);
        notificationService.sendNotification(user.getId().toString(),
                String.format("Budget '%s' has ended. ₦%s returned to your wallet.", budget.getName(), unusedAmount),
                "BUDGET_COMPLETED");
    }

    /**
     * Processes envelope disbursements based on conditions (daily, weekly, dynamic, safe/strict lock).
     */
    private void processEnvelopeDisbursement(Envelope envelope, LocalDate today, List<Envelope> envelopesToUpdate,
                                             List<TransactionLog> logsToSave) {
        Map<String, Object> conditions = envelope.getConditions();
        if (conditions == null || !conditions.containsKey("type")) {
            logger.warn("Invalid conditions for envelope {}", envelope.getId());
            return;
        }

        String type = (String) conditions.get("type");
        LocalDateTime lastDisbursedAt = envelope.getLastDisbursedAt() != null
                ? envelope.getLastDisbursedAt()
                : LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
        String userId = envelope.getBudget().getUser().getId().toString();
        String message = null;
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        switch (type) {
            case "daily":
                if (!lastDisbursedAt.toLocalDate().equals(today)) {
                    disburseEnvelope(envelope, now, envelopesToUpdate, logsToSave);
                    message = String.format("Your daily allowance of ₦%s for '%s' is ready!", conditions.get("limit"), envelope.getName());
                }
                break;

            case "weekly":
                LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1);
                if (lastDisbursedAt.isBefore(weekStart.atStartOfDay())) {
                    disburseEnvelope(envelope, now, envelopesToUpdate, logsToSave);
                    message = String.format("Your weekly funds of ₦%s for '%s' are ready!", conditions.get("limit"), envelope.getName());
                }
                break;

            case "dynamic":
                // Handled by processScheduledTasks; no action needed here
                disburseEnvelope(envelope, now, envelopesToUpdate, logsToSave);
                message = String.format("Your '%s' envelope disbursement of ₦%s is ready!", envelope.getName(), conditions.get("limit"));
                break;

            case "safe_lock":
            case "strict_lock":
                if (!conditions.containsKey("lockStartDate") || !conditions.containsKey("lockDurationDays") || !conditions.containsKey("interestRate")) {
                    logger.warn("Missing lock conditions for envelope {}", envelope.getId());
                    break;
                }
                LocalDate lockStart = LocalDate.parse((String) conditions.get("lockStartDate"));
                int lockDays = Integer.parseInt(conditions.get("lockDurationDays").toString());
                LocalDate unlockDate = lockStart.plusDays(lockDays);

                if (today.equals(unlockDate)) {
                    BigDecimal interestRate = new BigDecimal(conditions.get("interestRate").toString());
                    BigDecimal interest = envelope.getAmount()
                            .multiply(interestRate)
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

                    // Credit interest to envelope
                    envelope.setRemainingAmount(envelope.getRemainingAmount().add(interest));
                    envelopesToUpdate.add(envelope);

                    disburseEnvelope(envelope, now, envelopesToUpdate, logsToSave);
                    message = String.format("Lock lifted on '%s'! ₦%.2f interest added and ₦%s disbursed.", envelope.getName(), interest, conditions.get("limit"));
                }
                break;

            case "emergency":
                // No automatic disbursement; handled by user action
                break;

            default:
                logger.warn("Unknown disbursement type {} for envelope {}", type, envelope.getId());
                break;
        }

        if (message != null) {
            envelope.setLastAccessed(now);
            envelopesToUpdate.add(envelope);
            notificationService.sendNotification(userId, message, "DISBURSEMENT");
            logger.debug("Sent disbursement notification for envelope {}: {}", envelope.getId(), message);
        }
    }

    /**
     * Disburses envelope funds to the user's wallet, respecting any limit condition, and updates the balance.
     */
    private void disburseEnvelope(Envelope envelope, LocalDateTime now, List<Envelope> envelopesToUpdate,
                                  List<TransactionLog> logsToSave) {
        BigDecimal amountToDisburse = envelope.getRemainingAmount();
        Map<String, Object> conditions = envelope.getConditions();

        // Check for limit condition
        if (conditions != null && conditions.containsKey("limit")) {
            Object limitObj = conditions.get("limit");
            if (!(limitObj instanceof Number)) {
                logger.warn("Invalid limit type for envelope {}: {}", envelope.getId(), limitObj);
                return;
            }
            BigDecimal limit = new BigDecimal(((Number) limitObj).doubleValue());
            if (limit.compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("Non-positive limit for envelope {}: {}", envelope.getId(), limit);
                return;
            }
            if (amountToDisburse.compareTo(limit) > 0) {
                amountToDisburse = limit;
                logger.info("Disbursement for envelope {} capped at limit ₦{}", envelope.getId(), limit);
            }
        }

        if (amountToDisburse.compareTo(BigDecimal.ZERO) > 0) {
            walletService.fundWallet(envelope.getBudget().getUser().getId(), amountToDisburse,
                    "Disbursement from envelope: " + envelope.getName());

            // Log disbursement transaction
            TransactionLog disbursementLog = new TransactionLog();
            disbursementLog.setUserId(envelope.getBudget().getUser().getId());
            disbursementLog.setBudgetId(envelope.getBudget().getId());
            disbursementLog.setAmount(amountToDisburse);
            disbursementLog.setTransactionType("envelope_disbursement");
            disbursementLog.setCreatedAt(now);
            logsToSave.add(disbursementLog);

            envelope.setRemainingAmount(envelope.getRemainingAmount().subtract(amountToDisburse));
            envelope.setLastDisbursedAt(now);
            envelopesToUpdate.add(envelope);

            logger.info("Disbursed ₦{} from envelope {} to user {}", amountToDisburse, envelope.getId(),
                    envelope.getBudget().getUser().getId());
        }
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanOldTasks() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);
        scheduledTaskRepository.deleteByTriggerTimeBefore(threshold);
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanOldNotifications() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);
        notificationRepository.deleteByCreatedAtBefore(threshold);
        logger.info("Cleaned notifications older than {}", threshold);
    }
}
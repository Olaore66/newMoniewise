package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.repository.*;
import com.moniewise.moniewise_backend.service.EnvelopeService;
import com.moniewise.moniewise_backend.service.NotificationService;
import com.moniewise.moniewise_backend.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Stream;

import static com.moniewise.moniewise_backend.enums.TransactionStatus.COMPLETED;
import static com.moniewise.moniewise_backend.enums.TransactionType.BUDGET_COMPLETION_REFUND;

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
    private final NotificationRepository notificationRepository;
    private final PendingDisbursementRepository pendingDisbursementRepository;
    private final EnvelopeService envelopeService;
    private final long GRACE_PERIOD_MINUTES = 10; // can be dynamic per envelope

    public BudgetLifeCycleManager(
            BudgetRepository budgetRepository,
            EnvelopeRepository envelopeRepository,
            ScheduledTaskRepository scheduledTaskRepository,
            TransactionLogRepository transactionLogRepository,
            WalletService walletService,
            NotificationService notificationService,
            TransactionTemplate transactionTemplate,
            NotificationRepository notificationRepository,
            PendingDisbursementRepository pendingDisbursementRepository,
            @Lazy EnvelopeService envelopeService
    ) {
        this.budgetRepository = budgetRepository;
        this.envelopeRepository = envelopeRepository;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.walletService = walletService;
        this.notificationService = notificationService;
        this.transactionTemplate = transactionTemplate;
        this.notificationRepository = notificationRepository;
        this.pendingDisbursementRepository = pendingDisbursementRepository;
        this.envelopeService = envelopeService;
    }

    @PostConstruct
    public void init() {
        logger.info("Revenue Wallet User ID: {}", revenueWalletUserId);
    }

    private LocalDateTime fetchCurrentDateTimeFromDatabase() {
        try {
            return budgetRepository.getCurrentLagosTime();
        } catch (Exception e) {
            logger.error("Failed to fetch DB time, using system: {}", e.getMessage());
            return LocalDateTime.now(ZoneId.of("Africa/Lagos"));
        }
    }

    public void scheduleDynamicTasks(Envelope envelope) {
        // 1. Clear old pending tasks (Clean slate)
//        scheduledTaskRepository.deleteByEnvelopeId(envelope.getId());

        scheduledTaskRepository.deleteByEnvelopeIdAndTaskType(envelope.getId(), "DISBURSEMENT");

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        // 2. Find ONLY the NEXT SINGLE disbursement time
        LocalDateTime nextTriggerTime = calculateNextDisbursementTime(envelope);

        if (nextTriggerTime != null) {
            scheduleDisbursementGroup(envelope, nextTriggerTime, now);
        }
    }

    // Helper to schedule the trio: Disbursement + Warnings
    private void scheduleDisbursementGroup(Envelope envelope, LocalDateTime triggerTime, LocalDateTime now) {
        List<ScheduledTask> tasks = new ArrayList<>();

        // 1. The Main Event
        ScheduledTask mainTask = new ScheduledTask();
        mainTask.setEnvelopeId(envelope.getId());
        mainTask.setTaskType("DISBURSEMENT");
        mainTask.setTriggerTime(triggerTime);
        mainTask.setCreatedAt(now);
        tasks.add(mainTask);

        // 2. The Warnings (Only if time permits)
        if (triggerTime.minusMinutes(15).isAfter(now)) {
            ScheduledTask warn15 = new ScheduledTask();
            warn15.setEnvelopeId(envelope.getId());
            warn15.setTaskType("PRE_DISBURSEMENT_NOTIFICATION_15MIN");
            warn15.setTriggerTime(triggerTime.minusMinutes(15));
            warn15.setCreatedAt(now);
            tasks.add(warn15);
        }

        if (triggerTime.minusMinutes(5).isAfter(now)) {
            ScheduledTask warn5 = new ScheduledTask();
            warn5.setEnvelopeId(envelope.getId());
            warn5.setTaskType("PRE_DISBURSEMENT_NOTIFICATION_5MIN");
            warn5.setTriggerTime(triggerTime.minusMinutes(5));
            warn5.setCreatedAt(now);
            tasks.add(warn5);
        }

        scheduledTaskRepository.saveAll(tasks);
        logger.info("Scheduled next disbursement for envelope {} at {}", envelope.getId(), triggerTime);
    }
//================================================================================
    @Transactional(timeout = 120)
    @Scheduled(cron = "0 */5 * * * ?", zone = "Africa/Lagos") // FIX: Added zone for consistency
    public void processBudgets() {
        long startTime = System.nanoTime();
        // FIX: Use Africa/Lagos for LocalDate
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        LocalDate threeDaysFromNow = today.plusDays(3);
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        logger.debug("Starting budget lifecycle processing at {}", now);

        // Step 1: Send notifications for budgets ending in 3 days
        List<Budget> budgetsNearingEnd = budgetRepository.findByStatusAndEndDate(BudgetStatus.ACTIVE, threeDaysFromNow);
        for (Budget budget : budgetsNearingEnd) {
            String message = String.format("Budget '%s' will end in 3 days on %s.", budget.getName(), budget.getEndDate());
            notificationService.sendNotification(
                    budget.getUser().getId().toString(),
                    message,
                    NotificationType.BUDGET_END
            );
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
        // FIX: Optimize query to filter envelopes
        try (Stream<Envelope> envelopeStream = envelopeRepository.findByBudgetStatusAndTypeNot(BudgetStatus.ACTIVE, "dynamic")) {
            envelopeStream.forEach(envelope -> {
                transactionTemplate.execute(status -> {
                    try {
                        processEnvelopeDisbursement(envelope, today, envelopesToUpdate, logsToSave);
                        return null;
                    } catch (Exception e) {
                        logger.error("Failed to process envelope {}: {}", envelope.getId(), e.getMessage());
                        throw new RuntimeException("Envelope disbursement processing failed", e);
                    }
                });
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

    //  PLEASE RETURN THIS BACK TO THE ORIGINAL ONCE YOU ARE DONE TESING.
//    @Scheduled(cron = "0 * * * * ?", zone = "Africa/Lagos") // FIX: Added zone for consistency
//    @Transactional(timeout = 120)

        // ✅ TEST MODE: Runs every 30 seconds
        @Scheduled(fixedRate = 30000)
        @Transactional(timeout = 120)
    public void processScheduledTasks() {
        long startTime = System.nanoTime();
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        logger.debug("Starting scheduled tasks processing at {}", now);

        List<ScheduledTask> dueTasks = scheduledTaskRepository.findTasksDueBy(now);
        List<Envelope> envelopesToUpdate = Collections.synchronizedList(new ArrayList<>());
        List<TransactionLog> logsToSave = Collections.synchronizedList(new ArrayList<>());
        List<Long> taskIdsToDelete = Collections.synchronizedList(new ArrayList<>());

        if (dueTasks.size() > 100) {
            logger.info("Processing {} tasks in batch using parallel stream", dueTasks.size());
            dueTasks.parallelStream().forEach(task -> transactionTemplate.execute(status -> {
                try {
                    processTask(task, now, envelopesToUpdate, logsToSave, taskIdsToDelete);
                    return null;
                } catch (Exception e) {
                    logger.error("Failed to process task {} for envelope {}: {}", task.getId(), task.getEnvelopeId(), e.getMessage());
                    throw new RuntimeException("Task processing failed", e);
                }
            }));
        } else {
            logger.debug("Processing {} tasks sequentially", dueTasks.size());
            for (ScheduledTask task : dueTasks) {
                transactionTemplate.execute(status -> {
                    try {
                        processTask(task, now, envelopesToUpdate, logsToSave, taskIdsToDelete);
                        return null;
                    } catch (Exception e) {
                        logger.error("Failed to process task {} for envelope {}: {}", task.getId(), task.getEnvelopeId(), e.getMessage());
                        throw new RuntimeException("Task processing failed", e);
                    }
                });
            }
        }

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

    // FIX: New method to process tasks, including LIMIT_RESET
    private void processTask(ScheduledTask task, LocalDateTime now, List<Envelope> envelopesToUpdate,
                             List<TransactionLog> logsToSave, List<Long> taskIdsToDelete) {
        Envelope envelope = envelopeRepository.findById(task.getEnvelopeId()).orElse(null);
        if (envelope == null) {
            logger.warn("Envelope {} not found for task {}", task.getEnvelopeId(), task.getId());
            taskIdsToDelete.add(task.getId());
            return;
        }
        Budget budget = envelope.getBudget();
        if (budget == null || budget.getStatus() != BudgetStatus.ACTIVE) {
            logger.warn("Skipping task {} for envelope {}: budget is not active", task.getId(), task.getEnvelopeId());
            taskIdsToDelete.add(task.getId());
            return;
        }
        String userId = budget.getUser().getId().toString();
        switch (task.getTaskType()) {
            case "LIMIT_RESET":
                envelopeService.resetEnvelopeLimits(envelope);
                logger.info("Reset limit for envelope {} at {}", envelope.getId(), now);
                taskIdsToDelete.add(task.getId());
                // CRITICAL: Schedule the NEXT reset
                scheduleNextTask(envelope, "LIMIT_RESET", now);
                break;
            case "PRE_DISBURSEMENT_NOTIFICATION_15MIN":
                // FIX: Include totalRemainingAmount in notification
                String message15 = String.format("Your '%s' envelope disbursement of ₦%s is 15 minutes away! (Total remaining: ₦%.2f)",
                        envelope.getName(), envelope.getConditions().get("limit"), envelope.getTotalRemainingAmount());
                notificationService.sendNotification(
                        userId,
                        message15,
                        NotificationType.PRE_DISBURSEMENT,
                        budget.getId(),                 // Context ID 1 (Budget)
                        envelope.getId(),               // Context ID 2 (Envelope)
                        "VIEW_ENVELOPE",                // Action Type
                        "/envelopes/" + envelope.getId() // Navigation URL
                );
                logger.debug("Sent 15-minute pre-disbursement notification for envelope {}: {}", envelope.getId(), message15);
                taskIdsToDelete.add(task.getId());
                break;
            case "PRE_DISBURSEMENT_NOTIFICATION_5MIN":
                // FIX: Include totalRemainingAmount in notification
                String message5 = String.format("Your '%s' envelope disbursement of ₦%s is 5 minutes away! (Total remaining: ₦%.2f)",
                        envelope.getName(), envelope.getConditions().get("limit"), envelope.getTotalRemainingAmount());
                notificationService.sendNotification(
                        userId,
                        message5,
                        NotificationType.PRE_DISBURSEMENT,
                        budget.getId(),                 // Context ID 1
                        envelope.getId(),               // Context ID 2
                        "VIEW_ENVELOPE",                // Action Type
                        "/envelopes/" + envelope.getId() // Navigation URL
                );
                logger.debug("Sent 5-minute pre-disbursement notification for envelope {}: {}", envelope.getId(), message5);
                taskIdsToDelete.add(task.getId());
                break;
            case "DISBURSEMENT":
                processEnvelopeDisbursement(envelope, now.toLocalDate(), envelopesToUpdate, logsToSave);
                // CRITICAL: Schedule the NEXT disbursement so it happens again tomorrow/next week
                scheduleNextTask(envelope, "DISBURSEMENT", now);
//                scheduleDynamicTasks(envelope);
                taskIdsToDelete.add(task.getId());
                break;
            default:
                logger.warn("Unknown task type {} for envelope {}", task.getTaskType(), envelope.getId());
                taskIdsToDelete.add(task.getId());
        }
    }

    // ================== YOU NEED TO ADD THIS HELPER METHOD ==========================
    private void scheduleNextTask(Envelope envelope, String taskType, LocalDateTime lastTriggerTime) {
        LocalDateTime nextTime = calculateNextDisbursementTime(envelope); // You already have this logic!

        if (nextTime != null) {
            ScheduledTask newTask = new ScheduledTask();
            newTask.setEnvelopeId(envelope.getId());
            newTask.setTaskType(taskType);
            newTask.setTriggerTime(nextTime);
            newTask.setCreatedAt(LocalDateTime.now());
            scheduledTaskRepository.save(newTask);
            logger.info("Chained next {} task for envelope {} at {}", taskType, envelope.getId(), nextTime);
        }
    }
    //===================================================
    private void processBudgetExpiry(Budget budget, List<Budget> budgetsToUpdate, List<Envelope> envelopesToUpdate,
                                     List<TransactionLog> logsToSave) {
        User user = budget.getUser();
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        BigDecimal totalRefunded = BigDecimal.ZERO;
        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budget.getId());

        // FIX: Use totalRemainingAmount for refunds and reset both amounts
        for (Envelope envelope : envelopes) {
            BigDecimal remainingAmount = envelope.getTotalRemainingAmount(); // Changed to totalRemainingAmount
            if (remainingAmount.compareTo(BigDecimal.ZERO) > 0) {
                walletService.fundWallet(
                        user.getId(),
                        remainingAmount,
                        String.format("Refund of unused amount from envelope %s of budget %s", envelope.getName(), budget.getName())
                );

                TransactionLog refundLog = new TransactionLog();
                refundLog.setUserId(user.getId());
                refundLog.setBudgetId(budget.getId());
                refundLog.setSourceEnvelopeId(envelope.getId());
                refundLog.setAmount(remainingAmount);
                refundLog.setTransactionType(BUDGET_COMPLETION_REFUND);
                refundLog.setStatus(COMPLETED);
                refundLog.setCreatedAt(now);
                // FIX: Generate an internal reference
                String ref = "MW-REFUND-" + UUID.randomUUID().toString();
                refundLog.setReference(ref);
                logsToSave.add(refundLog);

                notificationService.sendNotification(
                        user.getId().toString(),
                        String.format("Your budget '%s' has ended. ₦%.2f from '%s' (total remaining: ₦%.2f) has been refunded to your wallet.",
                                budget.getName(), remainingAmount, envelope.getName(), remainingAmount),
                        NotificationType.BUDGET_COMPLETED,
                        budget.getId(),
                        envelope.getId(),
                        "VIEW_BUDGET",
                        "/budgets/" + budget.getId() + "/envelopes" // Navigation URL
                );

                envelope.setRemainingAmount(BigDecimal.ZERO);
                envelope.setTotalRemainingAmount(BigDecimal.ZERO); // Reset both
                envelopesToUpdate.add(envelope);
                scheduledTaskRepository.deleteByEnvelopeId(envelope.getId());
                totalRefunded = totalRefunded.add(remainingAmount);
            }
        }

        budget.setStatus(BudgetStatus.COMPLETED);
        budget.setRemainingAmount(BigDecimal.ZERO);
        budgetsToUpdate.add(budget);

        // FIX: Include totalRemainingAmount in notification
        if (totalRefunded.compareTo(BigDecimal.ZERO) > 0) {
            notificationService.sendNotification(
                    user.getId().toString(),
                    String.format("Your budget '%s' has ended. A total of ₦%.2f has been refunded to your wallet.", budget.getName(), totalRefunded),
                    NotificationType.BUDGET_COMPLETED,
                    budget.getId(),                     // Context ID 1
                    null,                               // No specific envelope context for summary
                    "VIEW_BUDGET",                      // Action Type
                    "/budgets/" + budget.getId() + "/envelopes" // Navigation URL
            );
        } else {
            notificationService.sendNotification(
                    user.getId().toString(),
                    String.format("Your budget '%s' has ended with no unused funds to refund.", budget.getName()),
                    NotificationType.BUDGET_COMPLETED
            );
        }

        logger.info("Budget {} completed for user {}. Refunded ₦{}", budget.getId(), user.getId(), totalRefunded);
    }

    private void processEnvelopeDisbursement(Envelope envelope, LocalDate today, List<Envelope> envelopesToUpdate,
                                             List<TransactionLog> logsToSave) {
        Map<String, Object> conditions = envelope.getConditions();
        if (conditions == null || !conditions.containsKey("type")) {
            logger.warn("Invalid conditions for envelope {}", envelope.getId());
            return;
        }

        // 🛑 1. GUARD CLAUSE: Has this already run?
        // This stops the infinite loop for daily/weekly envelopes
        if (isSamePeriod(envelope.getConditions(), fetchCurrentDateTimeFromDatabase(), envelope.getLastDisbursedAt())) {
            logger.info("Skipping disbursement for envelope {} - already processed for this period.", envelope.getId());
            return;
        }

        String type = (String) conditions.get("type");
        // FIX: Skip dynamic envelopes to prevent duplicate disbursements
        if ("dynamic".equals(type)) {
            return; // Handled by processScheduledTasks
        }

        LocalDateTime lastDisbursedAt = envelope.getLastDisbursedAt() != null
                ? envelope.getLastDisbursedAt()
                : LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
        String userId = envelope.getBudget().getUser().getId().toString();
        String message = null;
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        switch (type) {

            // The Scheduler already checked the time. Trust the Scheduler.
            case "daily":
            case "weekly":
            case "dynamic":
                // 🛑 2. SAFETY CHECK FOR NEW ENVELOPES
                // If lastDisbursedAt is NULL (The Bug), assume it was created "Just Now" and fix the date
                // WITHOUT refunding/resetting the money.
                if (envelope.getLastDisbursedAt() == null) {
                    logger.info("Fixing NULL lastDisbursedAt for envelope {}", envelope.getId());
                    envelope.setLastDisbursedAt(now);
                    envelopesToUpdate.add(envelope);
                    return; // EXIT. Do not refill.
                }

                // 1. CHECK FOR UNSPENT MONEY (The "Saver's Reward")
                BigDecimal unspent = envelope.getRemainingAmount();

                if (unspent.compareTo(BigDecimal.ZERO) > 0) {
                    // Move it back to the Vault
                    envelope.setTotalRemainingAmount(envelope.getTotalRemainingAmount().add(unspent));

                    // Optional: Create a log so the user knows why their vault increased
                    TransactionLog refundLog = new TransactionLog();
                    refundLog.setUserId(Long.valueOf(userId)); // Parse from string
                    refundLog.setBudgetId(envelope.getBudget().getId());
                    refundLog.setSourceEnvelopeId(envelope.getId());
                    refundLog.setAmount(unspent);
                    refundLog.setTransactionType(TransactionType.ROLLOVER_REFUND); // Make sure this Enum exists!
                    refundLog.setReference("ROLLOVER-" + UUID.randomUUID().toString());
                    refundLog.setStatus(TransactionStatus.COMPLETED);
                    refundLog.setDescription("Unspent daily funds returned to vault");
                    refundLog.setCreatedAt(now);
                    logsToSave.add(refundLog);

                    logger.info("Swept unspent ₦{} back to vault for envelope {}", unspent, envelope.getId());
                }

                // 2. NOW IT IS SAFE TO RESET
                envelope.setRemainingAmount(BigDecimal.ZERO);

                // =========================================================
                // 🛑 THE MISSING LINK: RECALCULATE LIMIT NOW! 🛑
                // =========================================================
                // Because TotalRemainingAmount just went UP, the daily limit for
                // the remaining days should also go UP.
                try {
                    // We call the service to do the math and update conditions["limit"]
                    envelopeService.triggerRecalculation(envelope);

                    // Reload condition map in case it changed
                    // (envelope reference might need refreshing if Hibernate didn't auto-sync)
                } catch (Exception e) {
                    logger.error("Failed to recalculate limit for envelope {}", envelope.getId(), e);
                }
                // =========================================================

                envelopesToUpdate.add(envelope);

                // 3. PROCEED TO DISBURSE
                disburseEnvelope(envelope, now, envelopesToUpdate, logsToSave);
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
                    // FIX: Update totalRemainingAmount as well
                    envelope.setRemainingAmount(envelope.getRemainingAmount().add(interest));
                    envelope.setTotalRemainingAmount(envelope.getTotalRemainingAmount().add(interest));
                    envelopesToUpdate.add(envelope);

                    disburseEnvelope(envelope, now, envelopesToUpdate, logsToSave);
                    // FIX: Include totalRemainingAmount in notification
                    message = String.format("Lock lifted ",
                            envelope.getName(), interest, conditions.get("limit"), envelope.getTotalRemainingAmount());
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
            notificationService.sendNotification(
                    userId,
                    message,
                    NotificationType.DISBURSEMENT,
                    envelope.getBudget().getId(),
                    envelope.getId(),
                    "VIEW_ENVELOPE",
                    "/envelopes/" + envelope.getId() // <--- URL
            );
            logger.debug("Sent disbursement notification for envelope {}: {}", envelope.getId(), message);
        }
    }

//    private void disburseEnvelope(Envelope envelope, LocalDateTime now, List<Envelope> envelopesToUpdate,
//                                  List<TransactionLog> logsToSave) {
//        Map<String, Object> conditions = envelope.getConditions();
//        if (conditions == null || !conditions.containsKey("type") || !conditions.containsKey("limit")) {
//            logger.warn("Invalid conditions for envelope {}", envelope.getId());
//            return;
//        }
//
//        BigDecimal limit = new BigDecimal(((Number) conditions.get("limit")).doubleValue());
//        if (limit.compareTo(BigDecimal.ZERO) <= 0) {
//            logger.warn("Non-positive limit for envelope {}: {}", envelope.getId(), limit);
//            return;
//        }
//
//        // Use TOTAL (Vault), not Remaining (Pocket)
//        BigDecimal amountToDisburse = envelope.getTotalRemainingAmount().min(limit);
//
//        if (amountToDisburse.compareTo(BigDecimal.ZERO) > 0 && envelope.getTotalRemainingAmount().compareTo(BigDecimal.ZERO) > 0) {
//            amountToDisburse = amountToDisburse.min(envelope.getTotalRemainingAmount()); // Cap at total remaining
//            // FIX: Use PendingDisbursement instead of direct wallet funding
//            long gracePeriodMinutes = conditions.containsKey("gracePeriodMinutes")
//                    ? Long.parseLong(conditions.get("gracePeriodMinutes").toString())
//                    : GRACE_PERIOD_MINUTES;
//
//            PendingDisbursement pd = new PendingDisbursement();
//            pd.setEnvelopeId(envelope.getId());
//            pd.setUserId(envelope.getBudget().getUser().getId());
//            pd.setEnvelopeName(envelope.getName());
//            pd.setAmount(amountToDisburse);
//            pd.setMaturedAt(now);
//            pd.setExpiresAt(now.plusMinutes(gracePeriodMinutes));
//            pendingDisbursementRepository.save(pd);
//
//            // 3. LOCK THE ENVELOPE (The Change)
//            // We set remainingAmount (Spendable) to ZERO. User cannot spend until they claim.
//            // BUT we do NOT subtract from totalRemainingAmount yet. Money is still safe inside.
//            envelope.setRemainingAmount(BigDecimal.ZERO);
//
//            envelope.setLastDisbursedAt(now);
//            envelopesToUpdate.add(envelope);
//
//            // Update Budget.remainingAmount
//            Budget budget = envelope.getBudget();
//            BigDecimal newBudgetRemaining = envelopeRepository.findByBudgetId(budget.getId())
//                    .stream()
//                    .map(Envelope::getTotalRemainingAmount)
//                    .reduce(BigDecimal.ZERO, BigDecimal::add);
//            budget.setRemainingAmount(newBudgetRemaining);
//            budgetRepository.save(budget);
//
//            notificationService.sendNotification(
//                    pd.getUserId().toString(),
//                    String.format("₦%.2f from your '%s' envelope (Budget: %s, total remaining: ₦%.2f) is available. Claim within %d minutes!",
//                            amountToDisburse, envelope.getName(), budget.getName(), envelope.getTotalRemainingAmount(), gracePeriodMinutes),
//                    NotificationType.DISBURSEMENT,
//                    budget.getId(),                 // Context ID 1
//                    envelope.getId(),               // Context ID 2
//                    "CLAIM_DISBURSEMENT",           // Action Type
//                    "/envelopes/" + envelope.getId() // Navigation URL
//            );
//
//            pd.setNotifiedUser(true);
//            pendingDisbursementRepository.save(pd);
//
//            logger.info("Created pending disbursement of ₦{} from envelope {} to user {}", amountToDisburse, envelope.getId(),
//                    envelope.getBudget().getUser().getId());
//        }
//    }

    private void disburseEnvelope(Envelope envelope, LocalDateTime now, List<Envelope> envelopesToUpdate,
                                  List<TransactionLog> logsToSave) {
        Map<String, Object> conditions = envelope.getConditions();
        if (conditions == null || !conditions.containsKey("limit")) return;

        // 1. Validate Limit
        BigDecimal limit = new BigDecimal(((Number) conditions.get("limit")).doubleValue());
        if (limit.compareTo(BigDecimal.ZERO) <= 0) return;

        // 2. Calculate Amount (Cap at what is actually in the Vault)
        BigDecimal amountToDisburse = envelope.getTotalRemainingAmount().min(limit);

        if (amountToDisburse.compareTo(BigDecimal.ZERO) > 0) {

            // 3. AUTO-DEPOSIT (Data Integrity Check)
            // We set the Pocket (remainingAmount) to the disbursed amount.
            // We do NOT subtract from TotalRemainingAmount yet, because Total = Vault + Pocket.
            // The money hasn't left the envelope; it just changed status to "Spendable".
            envelope.setRemainingAmount(amountToDisburse);
            envelope.setLastDisbursedAt(now);

            // 4. Handle Locks (Prevent them from locking again immediately)
            String type = (String) conditions.getOrDefault("type", "");
            if ("safe_lock".equals(type) || "strict_lock".equals(type)) {
                envelope.setHasMatured(true);
            }

            envelopesToUpdate.add(envelope);

            // 5. Create Transaction Log (So user sees "+N2000" in history)
            TransactionLog log = new TransactionLog();
            log.setUserId(envelope.getBudget().getUser().getId());
            log.setBudgetId(envelope.getBudget().getId());
            log.setSourceEnvelopeId(envelope.getId());
            log.setAmount(amountToDisburse);
            log.setTransactionType(TransactionType.ENVELOPE_DISBURSEMENT);
            log.setDescription("Auto-deposit to pocket");
            log.setStatus(TransactionStatus.COMPLETED);
            log.setReference("AUTO-" + envelope.getId() + "-" + System.currentTimeMillis());
            log.setCreatedAt(now);
            logsToSave.add(log);

            // 6. Notify User (Success Message)
            notificationService.sendNotification(
                    envelope.getBudget().getUser().getId().toString(),
                    String.format("₦%.2f is now available in '%s'.", amountToDisburse, envelope.getName()),
                    NotificationType.DISBURSEMENT_SUCCESS,
                    envelope.getBudget().getId(),
                    envelope.getId(),
                    "VIEW_ENVELOPE",
                    "/envelopes/" + envelope.getId()
            );

            logger.info("Auto-disbursed ₦{} to envelope {}", amountToDisburse, envelope.getId());
        }
    }
    private LocalDateTime getPeriodStart(Map<String, Object> conditions, LocalDateTime now, LocalDateTime lastDisbursedAt) {
        String type = (String) conditions.get("type");
        LocalDateTime periodStart;
        switch (type) {
            case "daily":
                periodStart = now.toLocalDate().atStartOfDay();
                break;
            case "weekly":
                periodStart = now.toLocalDate().minusDays(now.getDayOfWeek().getValue() - 1).atStartOfDay();
                break;
            case "dynamic":
                String disbursementTimeStr = (String) conditions.getOrDefault("disbursementTime", "08:00");
                LocalTime disbursementTime;
                try {
                    disbursementTime = LocalTime.parse(disbursementTimeStr);
                } catch (DateTimeParseException e) {
                    logger.error("Invalid disbursementTime for envelope: {}, defaulting to 08:00", conditions, e);
                    disbursementTime = LocalTime.of(8, 0);
                }
                periodStart = now.toLocalDate().atTime(disbursementTime);
                break;
            default:
                periodStart = now.minusYears(1);
        }
        return periodStart;
    }

//    private boolean isSamePeriod(Map<String, Object> conditions, LocalDateTime now, LocalDateTime lastDisbursedAt) {
//        if (lastDisbursedAt == null) return false;
//        String type = (String) conditions.get("type");
//        switch (type) {
//            case "daily":
//                return now.toLocalDate().equals(lastDisbursedAt.toLocalDate());
//            case "weekly":
//                LocalDate weekStartNow = now.toLocalDate().minusDays(now.getDayOfWeek().getValue() - 1);
//                LocalDate weekStartLast = lastDisbursedAt.toLocalDate().minusDays(lastDisbursedAt.getDayOfWeek().getValue() - 1);
//                return weekStartNow.equals(weekStartLast);
//            case "dynamic":
//                String disbursementTimeStr = (String) conditions.getOrDefault("disbursementTime", "08:00");
//                LocalTime disbursementTime;
//                try {
//                    disbursementTime = LocalTime.parse(disbursementTimeStr);
//                } catch (DateTimeParseException e) {
//                    logger.error("Invalid disbursementTime for envelope: {}, defaulting to 08:00", conditions, e);
//                    disbursementTime = LocalTime.of(8, 0);
//                }
//                LocalDateTime periodStartNow = now.toLocalDate().atTime(disbursementTime);
//                LocalDateTime periodStartLast = lastDisbursedAt.toLocalDate().atTime(disbursementTime);
//                return now.isAfter(periodStartNow) && now.isBefore(periodStartNow.plusHours(1)) &&
//                        lastDisbursedAt.isAfter(periodStartLast) && lastDisbursedAt.isBefore(periodStartLast.plusHours(1));
//            default:
//                return true;
//        }
//    }

    private boolean isSamePeriod(Map<String, Object> conditions, LocalDateTime now, LocalDateTime lastDisbursedAt) {
        // 1. Safety Check: If never disbursed, obviously not same period.
        if (lastDisbursedAt == null) return false;

        String type = (String) conditions.getOrDefault("type", "daily");
        LocalDate today = now.toLocalDate();
        LocalDate lastRunDate = lastDisbursedAt.toLocalDate();

        switch (type) {
            // 2. DAILY & DYNAMIC: Both just need to ensure they haven't run TODAY.
            case "daily":
            case "dynamic":
                return today.isEqual(lastRunDate);

            // 3. WEEKLY: Check if we are in the same ISO Week (Monday start)
            case "weekly":
                // Calculate the "Monday" of the current week and the last run week
                LocalDate thisWeekStart = today.minusDays(today.getDayOfWeek().getValue() - 1);
                LocalDate lastWeekStart = lastRunDate.minusDays(lastRunDate.getDayOfWeek().getValue() - 1);

                return thisWeekStart.isEqual(lastWeekStart);

            default:
                return false; // Default to "Run It" if type is unknown
        }
    }
    @Scheduled(cron = "0 0 2 * * ?", zone = "Africa/Lagos") // FIX: Added zone for consistency
    public void cleanOldTasks() {
        LocalDateTime threshold = fetchCurrentDateTimeFromDatabase().minusDays(30);
        scheduledTaskRepository.deleteByTriggerTimeBefore(threshold);
        logger.info("Cleaned tasks older than {}", threshold);
    }

    @Scheduled(cron = "0 0 3 * * ?", zone = "Africa/Lagos") // FIX: Added zone for consistency
    public void cleanOldNotifications() {
        LocalDateTime threshold = fetchCurrentDateTimeFromDatabase().minusDays(30);
        notificationRepository.deleteByCreatedAtBefore(threshold);
        logger.info("Cleaned notifications older than {}", threshold);
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void checkAndHandleMaturedEnvelopes() {
        // FIX: Use fetchCurrentDateTimeFromDatabase and optimized query
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        List<Envelope> envelopes = envelopeRepository.findByNextDisbursementAtBefore(now);

        for (Envelope envelope : envelopes) {
            handleMaturedEnvelope(envelope);
        }
    }

//    private void handleMaturedEnvelope(Envelope envelope) {
//        Map<String, Object> conditions = envelope.getConditions();
//        if (conditions == null || !conditions.containsKey("limit")) {
//            logger.warn("Invalid conditions for envelope {}", envelope.getId());
//            return;
//        }
//        BigDecimal limit = new BigDecimal(((Number) conditions.get("limit")).doubleValue());
//        BigDecimal amountToDisburse = limit.min(envelope.getTotalRemainingAmount());
//        if (amountToDisburse.compareTo(BigDecimal.ZERO) <= 0) {
//            logger.info("No funds to disburse for envelope {}", envelope.getId());
//            envelope.setNextDisbursementAt(calculateNextDisbursementTime(envelope));
//            envelopeRepository.save(envelope);
//            return;
//        }
//
//        long gracePeriodMinutes = conditions.containsKey("gracePeriodMinutes")
//                ? Long.parseLong(conditions.get("gracePeriodMinutes").toString())
//                : GRACE_PERIOD_MINUTES;
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//
//        // FIX: Reset via service before disbursement
//        envelopeService.resetEnvelopeLimits(envelope);
//        envelope.setLastDisbursedAt(now);
//        envelope.setNextDisbursementAt(calculateNextDisbursementTime(envelope));
//        envelope.setTotalRemainingAmount(envelope.getTotalRemainingAmount().subtract(amountToDisburse));
//        envelope.setHasMatured(true);
//        envelopeRepository.save(envelope);
//
//        // Update Budget.remainingAmount
//        Budget budget = envelope.getBudget();
//        BigDecimal newBudgetRemaining = envelopeRepository.findByBudgetId(budget.getId())
//                .stream()
//                .map(Envelope::getTotalRemainingAmount)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//        budget.setRemainingAmount(newBudgetRemaining);
//        budgetRepository.save(budget);
//
//        PendingDisbursement pd = new PendingDisbursement();
//        pd.setEnvelopeId(envelope.getId());
//        pd.setUserId(envelope.getBudget().getUser().getId());
//        pd.setEnvelopeName(envelope.getName());
//        pd.setAmount(amountToDisburse);
//        pd.setMaturedAt(now);
//        pd.setExpiresAt(now.plusMinutes(gracePeriodMinutes));
//        pendingDisbursementRepository.save(pd);
//
//        // FIX: Include totalRemainingAmount in notification
//        notificationService.sendNotification(
//                pd.getUserId().toString(),
//                String.format("₦%.2f from your '%s' envelope (Budget: %s, total remaining: ₦%.2f) is available. You have %d minutes to claim before refund.",
//                        amountToDisburse, envelope.getName(), envelope.getBudget().getName(), envelope.getTotalRemainingAmount(), gracePeriodMinutes),
//                NotificationType.DISBURSEMENT
//        );
//
//        pd.setNotifiedUser(true);
//        pendingDisbursementRepository.save(pd);
//    }

    private void handleMaturedEnvelope(Envelope envelope) {
        Map<String, Object> conditions = envelope.getConditions();

        // Default to total amount if no limit exists (unlocks everything)
        BigDecimal limit = conditions != null && conditions.containsKey("limit")
                ? new BigDecimal(((Number) conditions.get("limit")).doubleValue())
                : envelope.getTotalRemainingAmount();

        BigDecimal amountToDisburse = limit.min(envelope.getTotalRemainingAmount());

        // If empty, just reschedule next check and exit
        if (amountToDisburse.compareTo(BigDecimal.ZERO) <= 0) {
            envelope.setNextDisbursementAt(calculateNextDisbursementTime(envelope));
            envelopeRepository.save(envelope);
            return;
        }

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        // 1. Reset Limits (Critical for data integrity)
        envelopeService.resetEnvelopeLimits(envelope);

        // 2. Auto-Deposit to Pocket
        envelope.setRemainingAmount(amountToDisburse);
        envelope.setLastDisbursedAt(now);
        envelope.setHasMatured(true);

        // 3. Schedule Next Check (prevent infinite loop)
        envelope.setNextDisbursementAt(calculateNextDisbursementTime(envelope));

        envelopeRepository.save(envelope);

        // 4. Notify
        notificationService.sendNotification(
                envelope.getBudget().getUser().getId().toString(),
                String.format("Lock Matured! ₦%.2f is now available in '%s'.", amountToDisburse, envelope.getName()),
                NotificationType.DISBURSEMENT_SUCCESS,
                envelope.getBudget().getId(),
                envelope.getId(),
                "VIEW_ENVELOPE",
                "/envelopes/" + envelope.getId()
        );
    }
    public void remindUsersOfExpiringFunds() {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        LocalDateTime inFifteenMinutes = now.plusMinutes(15);
        LocalDateTime inFiveMinutes = now.plusMinutes(5);

        // 15-minute reminders
        List<PendingDisbursement> disbursements15Min = pendingDisbursementRepository.findByExpiresAtBeforeAndNotifiedUserFalse(inFifteenMinutes);
        for (PendingDisbursement pd : disbursements15Min) {
            // FIX: Include totalRemainingAmount from envelope
            Envelope envelope = envelopeRepository.findById(pd.getEnvelopeId()).orElse(null);
            if (envelope == null) {
                logger.warn("Envelope {} not found for disbursement {}", pd.getEnvelopeId(), pd.getId());
                continue;
            }
            String message = String.format(
                    "Your ₦%.2f disbursement from '%s' (total remaining: ₦%.2f) expires in 15 minutes. Claim it now!",
                    pd.getAmount(), pd.getEnvelopeName(), envelope.getTotalRemainingAmount());
            notificationService.sendNotification(
                    pd.getUserId().toString(),
                    message,
                    NotificationType.DISBURSEMENT
            );
            pd.setNotifiedUser(true);
            pendingDisbursementRepository.save(pd);
            logger.debug("Sent 15-minute grace period notification for disbursement {}", pd.getId());
        }

        // 5-minute reminders
        List<PendingDisbursement> disbursements5Min = pendingDisbursementRepository.findByExpiresAtBeforeAndNotifiedUserTrue(inFiveMinutes);
        for (PendingDisbursement pd : disbursements5Min) {
            // FIX: Include totalRemainingAmount from envelope
            Envelope envelope = envelopeRepository.findById(pd.getEnvelopeId()).orElse(null);
            if (envelope == null) {
                logger.warn("Envelope {} not found for disbursement {}", pd.getEnvelopeId(), pd.getId());
                continue;
            }
            String message = String.format(
                    "Your ₦%.2f disbursement from '%s' (total remaining: ₦%.2f) expires in 5 minutes. Claim it now!",
                    pd.getAmount(), pd.getEnvelopeName(), envelope.getTotalRemainingAmount());
            Long budgetId = envelope.getBudget().getId(); // Get Budget ID for context
            notificationService.sendNotification(
                    pd.getUserId().toString(),
                    message,
                    NotificationType.DISBURSEMENT,
                    budgetId,                       // Context ID 1
                    envelope.getId(),               // Context ID 2
                    "CLAIM_DISBURSEMENT",           // Action Type
                    "/envelopes/" + envelope.getId() // Navigation URL
            );
            logger.debug("Sent 5-minute grace period notification for disbursement {}", pd.getId());
        }
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void refundExpiredPendingDisbursements() {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        // 1. Find expired items
        List<PendingDisbursement> expiredDisbursements = pendingDisbursementRepository.findByExpiresAtBeforeAndNotifiedUserTrue(now);
        List<PendingDisbursement> disbursementsToDelete = new ArrayList<>();


        for (PendingDisbursement pd : expiredDisbursements) {
            try {
                // 2. Just Notify (No money movement needed)
                // We inform them the window is closed.
                Envelope envelope = envelopeRepository.findById(pd.getEnvelopeId()).orElse(null);
                Long budgetId = (envelope != null) ? envelope.getBudget().getId() : null;

                notificationService.sendNotification(
                        pd.getUserId().toString(),
                        String.format("Your disbursement window for '%s' has closed. The funds remain in your budget vault.",
                                pd.getEnvelopeName()),
                        NotificationType.EXPIRED_DISBURSEMENT,
                        budgetId,                       // Context ID 1
                        pd.getEnvelopeId(),             // Context ID 2
                        "VIEW_ENVELOPE",                // Action Type
                        "/envelopes/" + pd.getEnvelopeId() // Navigation URL
                );

                disbursementsToDelete.add(pd);

                // REMOVED: TransactionLog creation (It was fake news)
                // REMOVED: Envelope update (Money is already safe)

            } catch (Exception e) {
                logger.error("Failed to process expiration for {}: {}", pd.getId(), e.getMessage());
            }
        }

        // 3. Cleanup
        pendingDisbursementRepository.deleteAll(disbursementsToDelete);
    }

    public LocalDateTime calculateNextDisbursementTime(Envelope envelope) {
        Map<String, Object> conditions = envelope.getConditions();
        if (conditions == null || !conditions.containsKey("type")) return null;

        String type = (String) conditions.get("type");
        LocalDateTime last = envelope.getLastDisbursedAt() != null
                ? envelope.getLastDisbursedAt()
                : envelope.getCreatedAt();
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        LocalDate budgetStart = envelope.getBudget().getStartDate();
        LocalDate budgetEnd = envelope.getBudget().getEndDate();

        switch (type) {
            case "daily":
                String disbursementTime = (String) conditions.getOrDefault("disbursementTime", "00:00");
                LocalTime time;
                try {
                    time = LocalTime.parse(disbursementTime);
                } catch (DateTimeParseException e) {
                    logger.error("Invalid disbursementTime format for daily envelope {}: {}, defaulting to 00:00", envelope.getId(), disbursementTime, e);
                    time = LocalTime.of(0, 0);
                }
                LocalDateTime next = last.toLocalDate().atTime(time);
                if (next.isBefore(now) || next.isBefore(budgetStart.atStartOfDay())) {
                    next = (now.toLocalDate().isBefore(budgetStart) ? budgetStart : now.toLocalDate()).atTime(time);
                    while (next.isBefore(now)) {
                        next = next.plusDays(1);
                    }
                }
                if (next.isAfter(budgetEnd.atTime(23, 59, 59))) {
                    return null;
                }
                return next;

            case "weekly":
                LocalDate nextWeekStart = last.toLocalDate()
                        .plusWeeks(1)
                        .with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                return nextWeekStart.atStartOfDay();

            case "dynamic":
                // 1. Safety Check
                if (!conditions.containsKey("days") || !conditions.containsKey("disbursementTime")) {
                    return null;
                }

                try {
                    // 2. Get the target time (e.g., 1:00 PM)
                    String timeStr = (String) conditions.get("disbursementTime");
                    LocalTime targetTime = LocalTime.parse(timeStr);

                    // 3. Get Allowed Days (e.g., [MONDAY, WEDNESDAY, FRIDAY])
                    List<String> allowedDays = ((List<String>) conditions.get("days")).stream()
                            .map(String::toUpperCase)
                            .toList();

                    // 4. Start checking from TODAY at the target time
                    // Example: Wednesday Jan 28 @ 1:00 PM
                    LocalDateTime candidate = now.toLocalDate().atTime(targetTime);

                    // 5. THE FIX: If today's time has passed (11:21 PM > 1:00 PM),
                    // effectively start looking from TOMORROW.
                    if (candidate.isBefore(now)) {
                        candidate = candidate.plusDays(1);
                        // Now candidate is Thursday Jan 29 @ 1:00 PM
                    }

                    // 6. THE SEARCH LOOP (Find the next matching day)
                    // We check up to 14 days into the future
                    for (int i = 0; i < 14; i++) {
                        String dayName = candidate.getDayOfWeek().name(); // e.g., "THURSDAY"

                        // CHECK: Is "THURSDAY" in [MONDAY, WEDNESDAY, FRIDAY]?
                        if (allowedDays.contains(dayName)) {

                            // YES! We found a match (e.g., when loop reaches FRIDAY)

                            // Check bounds (Budget Start/End)
                            if (candidate.toLocalDate().isAfter(budgetEnd)) return null;
                            if (candidate.toLocalDate().isBefore(budgetStart)) {
                                candidate = candidate.plusDays(1);
                                continue;
                            }

                            // Return this valid future time
                            return candidate;
                        }

                        // NO: Thursday is NOT in the list.
                        // So we add 1 day and loop again (Candidate becomes FRIDAY)
                        candidate = candidate.plusDays(1);
                    }
                } catch (Exception e) {
                    logger.error("Error calculating dynamic time for envelope {}", envelope.getId(), e);
                }
                return null;

            case "safe_lock":
            case "strict_lock":
                if (conditions.containsKey("lockStartDate") && conditions.containsKey("lockDurationDays")) {
                    LocalDate lockStart = LocalDate.parse((String) conditions.get("lockStartDate"));
                    int lockDays = Integer.parseInt(conditions.get("lockDurationDays").toString());
                    return lockStart.plusDays(lockDays).atStartOfDay();
                }
                return null;

            default:
                return null; // emergency or unsupported types
        }
    }

    public LocalDateTime findNextValidWindow(
            LocalDate searchDate,
            LocalDate budgetStartDate,
            LocalDate budgetEndDate,
            List<DayOfWeek> allowedDays,
            LocalTime disbursementTime) {
        LocalDate nextDate = searchDate;
        for (int i = 0; i <= 31; i++) {
            nextDate = nextDate.plusDays(1);
            if (nextDate.isAfter(budgetEndDate)) {
                return null;
            }
            if (allowedDays.contains(nextDate.getDayOfWeek()) && !nextDate.isBefore(budgetStartDate)) {
                return nextDate.atTime(disbursementTime);
            }
        }
        return null;
    }

    private boolean shouldResetToday(Envelope envelope, LocalDateTime now) {
        String timeStr = (String) envelope.getConditions().getOrDefault("disbursementTime", "00:00");
        LocalTime time = LocalTime.parse(timeStr);
        LocalDateTime todayReset = now.toLocalDate().atTime(time);
        return now.isAfter(todayReset) || now.equals(todayReset);
    }
    private void scheduleDynamicReset(Envelope envelope, String day, String disbursementTime) {
        // FIX: Implement actual scheduling instead of direct reset
        try {
            DayOfWeek.valueOf(day.toUpperCase()); // Validate day
            LocalTime time = LocalTime.parse(disbursementTime);
            LocalDateTime now = fetchCurrentDateTimeFromDatabase();
            LocalDateTime triggerTime = now.toLocalDate()
                    .with(TemporalAdjusters.next(DayOfWeek.valueOf(day.toUpperCase())))
                    .atTime(time);
            if (triggerTime.isAfter(now) && triggerTime.isBefore(envelope.getBudget().getEndDate().atTime(23, 59, 59))) {
                ScheduledTask resetTask = new ScheduledTask();
                resetTask.setEnvelopeId(envelope.getId());
                resetTask.setTaskType("LIMIT_RESET");
                resetTask.setTriggerTime(triggerTime);
                resetTask.setCreatedAt(now);
                scheduledTaskRepository.save(resetTask);
                logger.info("Scheduled LIMIT_RESET task for envelope {} on {} at {}", envelope.getId(), day, disbursementTime);
            }
        } catch (IllegalArgumentException | DateTimeParseException e) {
            logger.error("Invalid day {} or disbursementTime {} for envelope {}", day, disbursementTime, envelope.getId(), e);
        }
    }


}
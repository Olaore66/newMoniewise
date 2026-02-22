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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    private final ApplicationEventPublisher eventPublisher;
    @PersistenceContext
    private EntityManager entityManager;
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
            @Lazy EnvelopeService envelopeService,
            ApplicationEventPublisher eventPublisher) {
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
        this.eventPublisher = eventPublisher;
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
//    @Transactional(timeout = 120)
//    @Scheduled(cron = "0 */5 * * * ?", zone = "Africa/Lagos") // FIX: Added zone for consistency
//    // ✅ NEW SAFE VERSION
//    @Scheduled(cron = "0 */15 * * * ?", zone = "Africa/Lagos")
//    public void processBudgets() {
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//        LocalDate today = now.toLocalDate();
//        LocalDate threeDaysFromNow = today.plusDays(3);
//
//        // 1. Notifications (These are light, List is fine)
////        List<Budget> nearingEnd = budgetRepository.findByStatusAndEndDate(BudgetStatus.ACTIVE, threeDaysFromNow);
////        for (Budget budget : nearingEnd) {
////            notificationService.sendNotification(
////                    budget.getUser().getId().toString(),
////                    "Budget '" + budget.getName() + "' ends in 3 days.",
////                    NotificationType.BUDGET_END
////            );
////        }
//
//        // 2. HEAVY WORK: Process Expired Budgets in Batches
//        int batchSize = 100;
//        boolean hasMore = true;
//
//        while (hasMore) {
//            hasMore = transactionTemplate.execute(status -> {
//                // Page 0 because processed budgets change status to COMPLETED
//                Pageable pageable = PageRequest.of(0, batchSize);
//                Page<Budget> page = budgetRepository.findByStatusAndEndDateLessThanEqual(BudgetStatus.ACTIVE, today, pageable);
//
//                if (page.isEmpty()) return false;
//
//                List<Budget> budgetsToUpdate = new ArrayList<>();
//                List<Envelope> envelopesToUpdate = new ArrayList<>();
//                List<TransactionLog> logsToSave = new ArrayList<>();
//
//                for (Budget budget : page.getContent()) {
//                    try {
//                        processBudgetExpiry(budget, budgetsToUpdate, envelopesToUpdate, logsToSave);
//                    } catch (Exception e) {
//                        logger.error("Error expiring budget {}: {}", budget.getId(), e.getMessage());
//                    }
//                }
//
//                budgetRepository.saveAll(budgetsToUpdate);
//                envelopeRepository.saveAll(envelopesToUpdate);
//                transactionLogRepository.saveAll(logsToSave);
//
//                // 🧹 RAM CLEANUP
//                entityManager.flush();
//                entityManager.clear();
//
//                return page.hasNext();
//            });
//        }
//
//        // 3. Dynamic Refresh (Optional: keep as is or batch if >1000 dynamic envelopes)
//        refreshDynamicTasks(today);
//    }

    @Transactional(timeout = 120)
    @Scheduled(cron = "0 */15 * * * ?", zone = "Africa/Lagos")
    public void processBudgets() {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        LocalDate today = now.toLocalDate();

        int batchSize = 100;
        boolean hasMore = true;
        int maxLoops = 50;
        int currentLoop = 0;

        while (hasMore && currentLoop < maxLoops) {
            currentLoop++;

            // 1. Fetch the page OUTSIDE the transaction
            Pageable pageable = PageRequest.of(0, batchSize);
            Page<Budget> page = budgetRepository.findByStatusAndEndDateLessThanEqual(BudgetStatus.ACTIVE, today, pageable);

            if (page.isEmpty()) {
                hasMore = false;
                continue;
            }

            for (Budget budget : page.getContent()) {
                try {
                    // 2. Process EACH budget in its own isolated transaction
                    transactionTemplate.execute(status -> {
                        List<Budget> bUpdate = new ArrayList<>();
                        List<Envelope> eUpdate = new ArrayList<>();
                        List<TransactionLog> lSave = new ArrayList<>();

                        processBudgetExpiry(budget, bUpdate, eUpdate, lSave);

                        budgetRepository.saveAll(bUpdate);
                        envelopeRepository.saveAll(eUpdate);
                        transactionLogRepository.saveAll(lSave);
                        return null;
                    });
                } catch (Exception e) {
                    logger.error("🚨 Failed to expire Budget ID {}. Quarantining.", budget.getId(), e);

                    // 3. Save Quarantine state in a NEW transaction so it doesn't roll back
                    transactionTemplate.execute(status -> {
                        Budget failedBudget = budgetRepository.findById(budget.getId()).orElse(null);
                        if (failedBudget != null) {
                            failedBudget.setStatus(BudgetStatus.FAILED_PROCESSING);
                            budgetRepository.save(failedBudget);
                        }
                        return null;
                    });
                }
            }

            // 🧹 RAM CLEANUP
            entityManager.flush();
            entityManager.clear();

            hasMore = page.hasNext();
        }

        refreshDynamicTasks(today);
    }
    // ========================================================================
    // 🛑 FIXED SPAM: SEPARATE DAILY CRON FOR BUDGET WARNINGS (Runs at 9:00 AM)
    // ========================================================================
    @Scheduled(cron = "0 0 9 * * ?", zone = "Africa/Lagos")
    @Transactional(readOnly = true)
    public void notifyExpiringBudgets() {
        LocalDate threeDaysFromNow = fetchCurrentDateTimeFromDatabase().toLocalDate().plusDays(3);
        List<Budget> nearingEnd = budgetRepository.findByStatusAndEndDate(BudgetStatus.ACTIVE, threeDaysFromNow);

        for (Budget budget : nearingEnd) {
            Map<String, Object> params = Map.of("budgetName", budget.getName());

            eventPublisher.publishEvent(new GenericNotificationEvent(
                    this,
                    budget.getUser().getId().toString(),
                    NotificationType.BUDGET_END_SOON,
                    params,
                    budget.getId(), null, "/budgets/" + budget.getId()
            ));
        }
        logger.info("Sent 3-day warning notifications to {} budgets.", nearingEnd.size());
    }

    private void refreshDynamicTasks(LocalDate today) {
        // Keep your existing logic for dynamic envelopes here
        // If this list gets huge, we can batch it later.
        List<Envelope> dynamicEnvelopes = envelopeRepository.findByConditionsType("dynamic");
        for (Envelope envelope : dynamicEnvelopes) {
            Budget budget = envelope.getBudget();
            if (budget != null && budget.getStatus() == BudgetStatus.ACTIVE && !budget.getEndDate().isBefore(today)) {
                scheduleDynamicTasks(envelope);
            }
        }
    }
    // ✅ NEW SAFE VERSION
    @Scheduled(fixedRate = 30000)
    public void processScheduledTasks() {
        long startTime = System.nanoTime();
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        logger.debug("Starting BATCH task processing at {}", now);

        int batchSize = 100; // Only load 100 at a time
        boolean hasNextBatch = true;

        while (hasNextBatch) {
            // Process chunks in separate transactions
            hasNextBatch = transactionTemplate.execute(status -> {
                // Always fetch Page 0. Because we DELETE or RESCHEDULE tasks,
                // they leave the "due" list, so the next batch moves up to Page 0.
                Pageable pageable = PageRequest.of(0, batchSize);

                // Uses the new Repository method we added
                Page<ScheduledTask> page = scheduledTaskRepository.findTasksDueBy(now, pageable);

                if (page.isEmpty()) return false; // Stop loop

                List<Long> tasksToDelete = new ArrayList<>();
                List<Envelope> envelopesToUpdate = new ArrayList<>();
                List<TransactionLog> logsToSave = new ArrayList<>();

                for (ScheduledTask task : page.getContent()) {
                    try {
                        processTask(task, now, envelopesToUpdate, logsToSave, tasksToDelete);
                    } catch (Exception e) {
                        logger.error("Skipping failed task {}: {}", task.getId(), e.getMessage());
                        tasksToDelete.add(task.getId()); // Delete bad tasks to prevent infinite loops
                    }
                }

                // Save Batch
                if (!envelopesToUpdate.isEmpty()) envelopeRepository.saveAll(envelopesToUpdate);
                if (!logsToSave.isEmpty()) transactionLogRepository.saveAll(logsToSave);
                if (!tasksToDelete.isEmpty()) scheduledTaskRepository.deleteAllById(tasksToDelete);

                // 🧹 RAM CLEANUP (Prevents OOM)
                entityManager.flush();
                entityManager.clear();

                return page.hasNext();
            });
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        logger.debug("Batch tasks completed in {}ms", durationMs);
    }

    //  PLEASE RETURN THIS BACK TO THE ORIGINAL ONCE YOU ARE DONE TESING.
//    @Scheduled(cron = "0 * * * * ?", zone = "Africa/Lagos") // FIX: Added zone for consistency
//    @Transactional(timeout = 120)

        // ✅ TEST MODE: Runs every 30 seconds
//        @Scheduled(fixedRate = 30000)
//        @Transactional(timeout = 120)
//    public void processScheduledTasks() {
//        long startTime = System.nanoTime();
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//        logger.debug("Starting scheduled tasks processing at {}", now);
//
//        List<ScheduledTask> dueTasks = scheduledTaskRepository.findTasksDueBy(now);
//        List<Envelope> envelopesToUpdate = Collections.synchronizedList(new ArrayList<>());
//        List<TransactionLog> logsToSave = Collections.synchronizedList(new ArrayList<>());
//        List<Long> taskIdsToDelete = Collections.synchronizedList(new ArrayList<>());
//
//        if (dueTasks.size() > 100) {
//            logger.info("Processing {} tasks in batch using parallel stream", dueTasks.size());
//            dueTasks.parallelStream().forEach(task -> transactionTemplate.execute(status -> {
//                try {
//                    processTask(task, now, envelopesToUpdate, logsToSave, taskIdsToDelete);
//                    return null;
//                } catch (Exception e) {
//                    logger.error("Failed to process task {} for envelope {}: {}", task.getId(), task.getEnvelopeId(), e.getMessage());
//                    throw new RuntimeException("Task processing failed", e);
//                }
//            }));
//        } else {
//            logger.debug("Processing {} tasks sequentially", dueTasks.size());
//            for (ScheduledTask task : dueTasks) {
//                transactionTemplate.execute(status -> {
//                    try {
//                        processTask(task, now, envelopesToUpdate, logsToSave, taskIdsToDelete);
//                        return null;
//                    } catch (Exception e) {
//                        logger.error("Failed to process task {} for envelope {}: {}", task.getId(), task.getEnvelopeId(), e.getMessage());
//                        throw new RuntimeException("Task processing failed", e);
//                    }
//                });
//            }
//        }
//
//        if (!envelopesToUpdate.isEmpty()) {
//            envelopeRepository.saveAll(envelopesToUpdate);
//        }
//        if (!logsToSave.isEmpty()) {
//            transactionLogRepository.saveAll(logsToSave);
//        }
//        if (!taskIdsToDelete.isEmpty()) {
//            scheduledTaskRepository.deleteAllById(taskIdsToDelete);
//        }
//
//        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
//        logger.debug("Scheduled tasks processing completed in {}ms. Processed {} tasks", durationMs, dueTasks.size());
//    }

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
//            case "PRE_DISBURSEMENT_NOTIFICATION_15MIN":
//                // FIX: Include totalRemainingAmount in notification
//                String message15 = String.format("Your '%s' envelope disbursement of ₦%s is 15 minutes away! (Total remaining: ₦%.2f)",
//                        envelope.getName(), envelope.getConditions().get("limit"), envelope.getTotalRemainingAmount());
//                notificationService.sendNotification(
//                        userId,
//                        message15,
//                        NotificationType.PRE_DISBURSEMENT,
//                        budget.getId(),                 // Context ID 1 (Budget)
//                        envelope.getId(),               // Context ID 2 (Envelope)
//                        "VIEW_ENVELOPE",                // Action Type
//                        "/envelopes/" + envelope.getId() // Navigation URL
//                );
//                logger.debug("Sent 15-minute pre-disbursement notification for envelope {}: {}", envelope.getId(), message15);
//                taskIdsToDelete.add(task.getId());
//                break;
//            case "PRE_DISBURSEMENT_NOTIFICATION_5MIN":
//                // FIX: Include totalRemainingAmount in notification
//                String message5 = String.format("Your '%s' envelope disbursement of ₦%s is 5 minutes away! (Total remaining: ₦%.2f)",
//                        envelope.getName(), envelope.getConditions().get("limit"), envelope.getTotalRemainingAmount());
//                notificationService.sendNotification(
//                        userId,
//                        message5,
//                        NotificationType.PRE_DISBURSEMENT,
//                        budget.getId(),                 // Context ID 1
//                        envelope.getId(),               // Context ID 2
//                        "VIEW_ENVELOPE",                // Action Type
//                        "/envelopes/" + envelope.getId() // Navigation URL
//                );
//                logger.debug("Sent 5-minute pre-disbursement notification for envelope {}: {}", envelope.getId(), message5);
//                taskIdsToDelete.add(task.getId());
//                break;
            case "PRE_DISBURSEMENT_NOTIFICATION_15MIN":
            case "PRE_DISBURSEMENT_NOTIFICATION_5MIN":
                // ✅ PUBLISH EVENT INSTEAD OF HARDCODED NOTIFICATION
                String timeLimit = task.getTaskType().contains("15") ? "15 minutes" : "5 minutes";
                Map<String, Object> preParams = Map.of(
                        "amount", formatAmount(envelope.getConditions().get("limit")),
                        "envelopeName", envelope.getName(),
                        "time", timeLimit
                );
                eventPublisher.publishEvent(new GenericNotificationEvent(
                        this, userId, NotificationType.PRE_DISBURSEMENT, preParams,
                        budget.getId(), envelope.getId(), "/envelopes/" + envelope.getId()
                ));
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
//    private void processBudgetExpiry(Budget budget, List<Budget> budgetsToUpdate, List<Envelope> envelopesToUpdate,
//                                     List<TransactionLog> logsToSave) {
//        User user = budget.getUser();
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//        BigDecimal totalRefunded = BigDecimal.ZERO;
//        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budget.getId());
//
//        // FIX: Use totalRemainingAmount for refunds and reset both amounts
//        for (Envelope envelope : envelopes) {
//            BigDecimal remainingAmount = envelope.getTotalRemainingAmount(); // Changed to totalRemainingAmount
//            if (remainingAmount.compareTo(BigDecimal.ZERO) > 0) {
//                walletService.fundWallet(
//                        user.getId(),
//                        remainingAmount,
//                        String.format("Refund of unused amount from envelope %s of budget %s", envelope.getName(), budget.getName())
//                );
//
//                TransactionLog refundLog = new TransactionLog();
//                refundLog.setUserId(user.getId());
//                refundLog.setBudgetId(budget.getId());
//                refundLog.setSourceEnvelopeId(envelope.getId());
//                refundLog.setAmount(remainingAmount);
//                refundLog.setTransactionType(BUDGET_COMPLETION_REFUND);
//                refundLog.setStatus(COMPLETED);
//                refundLog.setCreatedAt(now);
//                // FIX: Generate an internal reference
//                String ref = "MW-REFUND-" + UUID.randomUUID().toString();
//                refundLog.setReference(ref);
//                logsToSave.add(refundLog);
//
//                notificationService.sendNotification(
//                        user.getId().toString(),
//                        String.format("Your budget '%s' has ended. ₦%.2f from '%s' (total remaining: ₦%.2f) has been refunded to your wallet.",
//                                budget.getName(), remainingAmount, envelope.getName(), remainingAmount),
//                        NotificationType.BUDGET_COMPLETED,
//                        budget.getId(),
//                        envelope.getId(),
//                        "VIEW_BUDGET",
//                        "/budgets/" + budget.getId() + "/envelopes" // Navigation URL
//                );
//
//                envelope.setRemainingAmount(BigDecimal.ZERO);
//                envelope.setTotalRemainingAmount(BigDecimal.ZERO); // Reset both
//                envelopesToUpdate.add(envelope);
//                scheduledTaskRepository.deleteByEnvelopeId(envelope.getId());
//                totalRefunded = totalRefunded.add(remainingAmount);
//            }
//        }
//
//        budget.setStatus(BudgetStatus.COMPLETED);
//        budget.setRemainingAmount(BigDecimal.ZERO);
//        budgetsToUpdate.add(budget);
//
//        // FIX: Include totalRemainingAmount in notification
//        if (totalRefunded.compareTo(BigDecimal.ZERO) > 0) {
////            notificationService.sendNotification(
////                    user.getId().toString(),
////                    String.format("Your budget '%s' has ended. A total of ₦%.2f has been refunded to your wallet.", budget.getName(), totalRefunded),
////                    NotificationType.BUDGET_COMPLETED,
////                    budget.getId(),                     // Context ID 1
////                    null,                               // No specific envelope context for summary
////                    "VIEW_BUDGET",                      // Action Type
////                    "/budgets/" + budget.getId() + "/envelopes" // Navigation URL
////            );
//            // ✅ PUBLISH EVENT FOR COMPLETION
//            Map<String, Object> compParams = Map.of(
//                    "budgetName", budget.getName(),
//                    "refunded", String.format("%,.2f", totalRefunded)
//            );
//            eventPublisher.publishEvent(new GenericNotificationEvent(
//                    this, user.getId().toString(), NotificationType.BUDGET_COMPLETED,
//                    compParams, budget.getId(), null, "/budgets/" + budget.getId()
//            ));
//        } else {
//            notificationService.sendNotification(
//                    user.getId().toString(),
//                    String.format("Your budget '%s' has ended with no unused funds to refund.", budget.getName()),
//                    NotificationType.BUDGET_COMPLETED
//            );
//        }
//
//        logger.info("Budget {} completed for user {}. Refunded ₦{}", budget.getId(), user.getId(), totalRefunded);
//    }

    private void processBudgetExpiry(Budget budget, List<Budget> budgetsToUpdate, List<Envelope> envelopesToUpdate,
                                     List<TransactionLog> logsToSave) {
        User user = budget.getUser();

        // 🛡️ DEFENSIVE CHECK 1: Does user exist?
        if (user == null || user.getId() == null) {
            throw new IllegalStateException("Budget " + budget.getId() + " has no valid user linked.");
        }

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        BigDecimal totalRefunded = BigDecimal.ZERO;
        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budget.getId());

        for (Envelope envelope : envelopes) {
            BigDecimal remainingAmount = envelope.getTotalRemainingAmount();

            // 🛡️ DEFENSIVE CHECK 2: Ignore negative/zero balances safely
            if (remainingAmount != null && remainingAmount.compareTo(BigDecimal.ZERO) > 0) {

                try {
                    // Attempt Refund
                    walletService.fundWallet(
                            user.getId(), remainingAmount,
                            String.format("Refund from '%s' (Budget: %s)", envelope.getName(), budget.getName()), true
                    );
                } catch (Exception e) {
                    // If wallet funding fails, we MUST throw exception to trigger Quarantine
                    throw new RuntimeException("Wallet funding failed for user " + user.getId() + ": " + e.getMessage(), e);
                }

                // Log the Refund
                TransactionLog refundLog = new TransactionLog();
                refundLog.setUserId(user.getId());
                refundLog.setBudgetId(budget.getId());
                refundLog.setSourceEnvelopeId(envelope.getId());
                refundLog.setAmount(remainingAmount);
                refundLog.setTransactionType(BUDGET_COMPLETION_REFUND);
                refundLog.setStatus(COMPLETED);
                refundLog.setCreatedAt(now);
                refundLog.setReference("MW-REF-" + UUID.randomUUID().toString());
                logsToSave.add(refundLog);

                totalRefunded = totalRefunded.add(remainingAmount);
            }

            // Clear envelope balance
            envelope.setRemainingAmount(BigDecimal.ZERO);
            envelope.setTotalRemainingAmount(BigDecimal.ZERO);
            envelopesToUpdate.add(envelope);

            // Clean tasks
            scheduledTaskRepository.deleteByEnvelopeId(envelope.getId());
        }

        // Mark Success
        budget.setStatus(BudgetStatus.COMPLETED);
        budget.setRemainingAmount(BigDecimal.ZERO);
        budgetsToUpdate.add(budget);

        // Notify User
        if (totalRefunded.compareTo(BigDecimal.ZERO) > 0) {
            Map<String, Object> compParams = Map.of(
                    "budgetName", budget.getName(),
                    "refunded", String.format("%,.2f", totalRefunded)
            );
            eventPublisher.publishEvent(new GenericNotificationEvent(
                    this, user.getId().toString(), NotificationType.BUDGET_COMPLETED,
                    compParams, budget.getId(), null, "/budgets/" + budget.getId()
            ));
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
//            notificationService.sendNotification(
//                    envelope.getBudget().getUser().getId().toString(),
//                    String.format("₦%.2f is now available in '%s'.", amountToDisburse, envelope.getName()),
//                    NotificationType.DISBURSEMENT_SUCCESS,
//                    envelope.getBudget().getId(),
//                    envelope.getId(),
//                    "VIEW_ENVELOPE",
//                    "/envelopes/" + envelope.getId()
//            );
            // ✅ PUBLISH EVENT INSTEAD OF HARDCODED NOTIFICATION
            Map<String, Object> params = Map.of(
                    "amount", String.format("%,.2f", amountToDisburse),
                    "envelopeName", envelope.getName()
            );
            eventPublisher.publishEvent(new GenericNotificationEvent(
                    this, envelope.getBudget().getUser().getId().toString(),
                    NotificationType.DISBURSEMENT_SUCCESS, params,
                    envelope.getBudget().getId(), envelope.getId(), "/envelopes/" + envelope.getId()
            ));

            logger.info("Auto-disbursed ₦{} to envelope {}", amountToDisburse, envelope.getId());
        }
    }
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

//    @Scheduled(fixedRate = 60000)
//    @Transactional
//    public void checkAndHandleMaturedEnvelopes() {
//        // FIX: Use fetchCurrentDateTimeFromDatabase and optimized query
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//        List<Envelope> envelopes = envelopeRepository.findByNextDisbursementAtBefore(now);
//
//        for (Envelope envelope : envelopes) {
//            handleMaturedEnvelope(envelope);
//        }
//    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void checkAndHandleMaturedEnvelopes() {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        // Limit to 100 per minute to prevent memory spikes
        Pageable limit = PageRequest.of(0, 100);

        // Note: You must update your repository method to accept Pageable:
        // List<Envelope> findByNextDisbursementAtBefore(LocalDateTime time, Pageable pageable);
        List<Envelope> envelopes = envelopeRepository.findByNextDisbursementAtBefore(now, limit);

        for (Envelope envelope : envelopes) {
            handleMaturedEnvelope(envelope);
        }
    }
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
//        notificationService.sendNotification(
//                envelope.getBudget().getUser().getId().toString(),
//                String.format("Lock Matured! ₦%.2f is now available in '%s'.", amountToDisburse, envelope.getName()),
//                NotificationType.DISBURSEMENT_SUCCESS,
//                envelope.getBudget().getId(),
//                envelope.getId(),
//                "VIEW_ENVELOPE",
//                "/envelopes/" + envelope.getId()
//        );
        // ✅ PUBLISH EVENT INSTEAD OF HARDCODED NOTIFICATION
        Map<String, Object> params = Map.of(
                "amount", String.format("%,.2f", amountToDisburse),
                "envelopeName", envelope.getName()
        );
        eventPublisher.publishEvent(new GenericNotificationEvent(
                this, envelope.getBudget().getUser().getId().toString(),
                NotificationType.DISBURSEMENT_SUCCESS, params,
                envelope.getBudget().getId(), envelope.getId(), "/envelopes/" + envelope.getId()
        ));
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void refundExpiredPendingDisbursements() {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        Pageable limit = PageRequest.of(0, 100);

        // 1. Find expired items
        List<PendingDisbursement> expiredDisbursements = pendingDisbursementRepository.findByExpiresAtBeforeAndNotifiedUserTrue(now, limit);
        List<PendingDisbursement> disbursementsToDelete = new ArrayList<>();


        for (PendingDisbursement pd : expiredDisbursements) {
            try {
                // 2. Just Notify (No money movement needed)
                // We inform them the window is closed.
                Envelope envelope = envelopeRepository.findById(pd.getEnvelopeId()).orElse(null);
                Long budgetId = (envelope != null) ? envelope.getBudget().getId() : null;

//                notificationService.sendNotification(
//                        pd.getUserId().toString(),
//                        String.format("Your disbursement window for '%s' has closed. The funds remain in your budget vault.",
//                                pd.getEnvelopeName()),
//                        NotificationType.EXPIRED_DISBURSEMENT,
//                        budgetId,                       // Context ID 1
//                        pd.getEnvelopeId(),             // Context ID 2
//                        "VIEW_ENVELOPE",                // Action Type
//                        "/envelopes/" + pd.getEnvelopeId() // Navigation URL
//                );
                // ✅ PUBLISH EVENT
                Map<String, Object> params = Map.of("envelopeName", pd.getEnvelopeName());
                eventPublisher.publishEvent(new GenericNotificationEvent(
                        this, pd.getUserId().toString(), NotificationType.EXPIRED_DISBURSEMENT,
                        params, budgetId, pd.getEnvelopeId(), "/envelopes/" + pd.getEnvelopeId()
                ));

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

    private String formatAmount(Object obj) {
        if(obj == null) return "0";
        return obj.toString();
    }


}
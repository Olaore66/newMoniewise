//package com.moniewise.moniewise_backend.service;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.moniewise.moniewise_backend.config.BudgetLifeCycleManager;
//import com.moniewise.moniewise_backend.controller.BudgetController;
//import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
//import com.moniewise.moniewise_backend.dto.request.P2PTransferRequest;
//import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;
//import com.moniewise.moniewise_backend.entity.*;
//import com.moniewise.moniewise_backend.enums.*;
//import com.moniewise.moniewise_backend.exception.EntityNotFoundException;
//import com.moniewise.moniewise_backend.externalTransfers.PaymentProvider;
//import com.moniewise.moniewise_backend.repository.*;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Lazy;
//import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import javax.annotation.PostConstruct;
//import java.math.BigDecimal;
//import java.math.RoundingMode;
//import java.time.DayOfWeek;
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.time.LocalTime;
//import java.time.format.DateTimeParseException;
//import java.time.temporal.ChronoUnit;
//import java.util.*;
//import java.util.stream.Collectors;
//
//@Service
//public class EnvelopeService {
//
//    private static final Logger logger = LoggerFactory.getLogger(EnvelopeService.class);
//    private final EnvelopeRepository envelopeRepository;
//    private final BudgetRepository budgetRepository;
//    private final RevenueLogRepository revenueLogRepository;
//    private final UserService userService;
//    private final ObjectMapper objectMapper = new ObjectMapper();
//    private final TransactionLogRepository transactionLogRepository;
//    private final NotificationService notificationService;
//    private final WalletService walletService;
//    private final ScheduledTaskRepository scheduledTaskRepository;
//    private final BudgetLifeCycleManager budgetLifeCycleManager;
//
//    private final BudgetService budgetService;
//    private final PendingDisbursementRepository pendingDisbursementRepository;
//    private final JdbcTemplate jdbcTemplate;
//
//    private final BeneficiaryService beneficiaryService;
//
//    private final PaymentProvider paymentProvider;
//
//    @Value("${moniewise.revenue.wallet.user-id}")
//    private Long revenueWalletUserId;
//
//    public EnvelopeService(
//            EnvelopeRepository envelopeRepository,
//            BudgetRepository budgetRepository,
//            RevenueLogRepository revenueLogRepository,
//            UserService userService,
//            TransactionLogRepository transactionLogRepository,
//            NotificationService notificationService,
//            WalletService walletService,
//            ScheduledTaskRepository scheduledTaskRepository,
//            @Lazy BudgetLifeCycleManager budgetLifeCycleManager,
//            BudgetService budgetService, PendingDisbursementRepository pendingDisbursementRepository,
//            JdbcTemplate jdbcTemplate, BeneficiaryService beneficiaryService, PaymentProvider paymentProvider) {
//        this.envelopeRepository = envelopeRepository;
//        this.budgetRepository = budgetRepository;
//        this.revenueLogRepository = revenueLogRepository;
//        this.userService = userService;
//        this.transactionLogRepository = transactionLogRepository;
//        this.notificationService = notificationService;
//        this.walletService = walletService;
//        this.scheduledTaskRepository = scheduledTaskRepository;
//        this.budgetLifeCycleManager = budgetLifeCycleManager;
//        this.budgetService = budgetService;
//        this.pendingDisbursementRepository = pendingDisbursementRepository;
//        this.jdbcTemplate = jdbcTemplate;
//        this.beneficiaryService = beneficiaryService;
//        this.paymentProvider = paymentProvider;
//    }
//
//    @PostConstruct
//    public void init() {
//        logger.info("Revenue Wallet User ID: {}", revenueWalletUserId);
//    }
//
//    private LocalDateTime fetchCurrentDateTimeFromDatabase() {
//        String sql = "SELECT CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Lagos'";
//        return jdbcTemplate.queryForObject(sql, LocalDateTime.class);
//    }
//
//    @Transactional
//    public void moveMoney(Long sourceId, Long targetId, Double amount, String email, String withdrawalReason) {
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
//
//        User user = userService.findByEmail(email);
//        Envelope source = envelopeRepository.findByIdAndBudget_UserEmail(sourceId, email)
//                .orElseThrow(() -> new EntityNotFoundException("Source not found"));
//        Envelope target = envelopeRepository.findByIdAndBudget_UserEmail(targetId, email)
//                .orElseThrow(() -> new EntityNotFoundException("Target not found"));
//        Budget sourceBudget = source.getBudget();
//
//        // 1. VALIDATE BUDGET STATUS
//        // Add this near the top of your method (after fetching the envelope)
//
//
//        if (!sourceBudget.getId().equals(target.getBudget().getId())) {
//            throw new IllegalArgumentException("Envelopes must belong to the same budget");
//        }
//        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
//            throw new IllegalArgumentException("Budget must be active");
//        }
//
//        // 2. 🛑 RESTORED: VALIDATE TIME/CYCLE RULES (Dynamic/Weekly checks)
//        validateTransferRules(source, sourceBudget, now);
//
//        // 3. CHECK FUNDS
//        BigDecimal transferAmount = BigDecimal.valueOf(amount);
//        if (transferAmount.compareTo(source.getRemainingAmount()) > 0) {
//            throw new IllegalArgumentException("Insufficient spendable limit. Available: ₦" + source.getRemainingAmount());
//        }
//
//        // =====================================================================
//        // 4. HANDLE SOURCE
//        // =====================================================================
//        BigDecimal newSourceTotal = source.getTotalRemainingAmount().subtract(transferAmount);
//        BigDecimal newSourcePocket = source.getRemainingAmount().subtract(transferAmount);
//
//        source.setTotalRemainingAmount(newSourceTotal);
//        source.setRemainingAmount(newSourcePocket);
//
//        // Force DB Update
//        envelopeRepository.save(source);
//        envelopeRepository.flush();
//
//        // =====================================================================
//        // 5. HANDLE TARGET
//        // =====================================================================
//        target.setTotalRemainingAmount(target.getTotalRemainingAmount().add(transferAmount));
//
//        // Lump Sum Addition (Predictable)
//        target.setRemainingAmount(target.getRemainingAmount().add(transferAmount));
//
//        // Update Future Math
//        recalculateTargetEnvelopeLimit(target, sourceBudget);
//
//        envelopeRepository.save(target);
//        envelopeRepository.saveAll(List.of(source, target));
//
//        // =====================================================================
//        // 6. LOGGING & NOTIFICATION
//        // =====================================================================
//
//        // 👇 INSERT THIS BLOCK 👇
//        String description;
//        String type = (String) source.getConditions().getOrDefault("type", "");
//
//        if ("emergency".equalsIgnoreCase(type)) {
//            // Emergency Description
//            if (withdrawalReason == null || withdrawalReason.trim().isEmpty()) {
//                throw new IllegalArgumentException("Emergency withdrawals require a valid reason.");
//            }else {
//                description = String.format("EMERGENCY WITHDRAWAL: %s (To: %s)",
//                        withdrawalReason != null ? withdrawalReason : "Unspecified",
//                        target.getName());
//            }
//
//        } else {
//            // Standard Description
//            description = String.format("From %s → %s • Moved ₦%.2f",
//                    source.getName(), target.getName(), transferAmount);
//        }
//        // 👆 END INSERT
//
//        // Log Transaction
//        TransactionLog transactionLog = new TransactionLog(
//                user.getId(), sourceBudget.getId(), sourceId, targetId, transferAmount,
//                TransactionType.ENVELOPE_TO_ENVELOPE, description
//        );
//        transactionLog.setStatus(TransactionStatus.COMPLETED);
//        transactionLog.setReference("ENV-MOV-" + sourceId + "-" + System.currentTimeMillis());
//        transactionLog.setCreatedAt(now);
//        transactionLogRepository.save(transactionLog);
//
//        // 🛑 FIX: Don't call getRemainingLimit(). Use the value we just calculated!
//        BigDecimal remainingLimit = newSourcePocket;
//
//        String period = source.getConditions().getOrDefault("type", "period").toString().equals("daily") ? "today" : "this period";
//
//        notificationService.sendNotification(
//                user.getId().toString(),
//                String.format("Moved ₦%.2f. %s Remaining: ₦%.2f.", transferAmount, period, remainingLimit),
//                NotificationType.ENVELOPE_TRANSFER,
//                sourceBudget.getId(), sourceId, "VIEW_ENVELOPE", "/envelopes/" + sourceId
//        );
//    }
//
//    // 👇 ADD THIS NEW HELPER METHOD TO RESTORE YOUR CHECKS
//    // Inside EnvelopeService.java
//
//    private void validateTransferRules(Envelope source, Budget budget, LocalDateTime now) {
//        Map<String, Object> conditions = source.getConditions();
//        if (conditions == null || !conditions.containsKey("type")) return;
//
//        String type = ((String) conditions.get("type")).toLowerCase();
//        boolean createdToday = source.getCreatedAt().toLocalDate().isEqual(now.toLocalDate());
//
//        switch (type) {
//            case "dynamic":
//                // 1. Check Day of Week (Handles ANY day set in the list)
//                List<String> days = (List<String>) conditions.getOrDefault("days", List.of());
//                List<String> upperDays = days.stream().map(String::toUpperCase).collect(Collectors.toList());
//                String todayName = now.getDayOfWeek().name(); // e.g., "MONDAY", "SUNDAY"
//
//                // If today is NOT in your list, block it.
//                if (!upperDays.contains(todayName)) {
//                    throw new IllegalArgumentException("Dynamic transfers are only allowed on: " + days);
//                }
//
//                // 2. Check Time (Unlock for the WHOLE day after start time)
//                String timeStr = (String) conditions.getOrDefault("disbursementTime", "00:00");
//                try {
//                    LocalTime startTime = LocalTime.parse(timeStr);
//
//                    // If it is the right day but TOO EARLY (e.g. 8 AM vs 11 AM), block it.
//                    // Once it hits 11:00 AM, this passes until midnight.
//                    if (now.toLocalTime().isBefore(startTime)) {
//                        throw new IllegalArgumentException("Dynamic funds are locked until " + startTime);
//                    }
//                } catch (DateTimeParseException e) {
//                    throw new IllegalArgumentException("Invalid time format");
//                }
//                break;
//
//            case "daily":
//                // 3. Strict Time Check for Daily
//                // If they set 08:00, block transfers at 07:59, even if funds are available.
//                if (conditions.containsKey("disbursementTime")) {
//                    String dailyTimeStr = (String) conditions.get("disbursementTime");
//                    LocalTime startTime = LocalTime.parse(dailyTimeStr);
//
//                    // Allow spending anytime AFTER the time, or restrict to a window?
//                    // FIX: Skip check if created today
//                    if (!createdToday && now.toLocalTime().isBefore(startTime)) {
//                        throw new IllegalArgumentException("Daily funds are locked until " + startTime);
//                    }
//                }
//                break;
//
//            case "strict_lock":
//            case "safe_lock":
//                // 4. Maturity Check
//                // We use the calculated 'maturedAt' if available, or recalculate from conditions
//                if (source.getMaturedAt() != null && now.isBefore(source.getMaturedAt())) {
//                    throw new IllegalStateException("This envelope is locked until " + source.getMaturedAt().toLocalDate());
//                }
//                // Fallback: Check conditions manually if maturedAt is null
//                else if (conditions.containsKey("lockStartDate") && conditions.containsKey("lockDurationDays")) {
//                    LocalDate lockStart = LocalDate.parse((String) conditions.get("lockStartDate"));
//                    int duration = Integer.parseInt(conditions.get("lockDurationDays").toString());
//                    LocalDate unlockDate = lockStart.plusDays(duration);
//
//                    if (now.toLocalDate().isBefore(unlockDate)) {
//                        throw new IllegalStateException("This envelope is locked until " + unlockDate);
//                    }
//                }
//                break;
//
//            case "emergency":
//                // Emergency is always allowed (subject to the Reason check handled in the main method)
//                break;
//        }
//    }
//
//    // Helper for Time Checking to keep code clean
//    private void checkTimeWindow(LocalDateTime now, String timeStr, String typeName) {
//        try {
//            LocalTime startTime = LocalTime.parse(timeStr);
//            LocalTime endTime = startTime.plusHours(1); // 1 Hour Window
//            LocalTime currentTime = now.toLocalTime();
//
//            if (currentTime.isBefore(startTime) || currentTime.isAfter(endTime)) {
//                throw new IllegalArgumentException(String.format("%s transfers only allowed between %s and %s", typeName, startTime, endTime));
//            }
//        } catch (DateTimeParseException e) {
//            throw new IllegalArgumentException("Invalid time format in envelope settings: " + timeStr);
//        }
//    }
//    @Transactional(rollbackFor = Exception.class)
//    public void transferToExternal(Long sourceId, BudgetController.ExternalAccount externalAccount, Double amountDouble, String email, String withdrawalReason) {
//        BigDecimal amount = BigDecimal.valueOf(amountDouble);
//
//
//        // 1. BASIC VALIDATION
//        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("Amount must be positive");
//
//        User user = userService.findByEmail(email);
//        Envelope source = envelopeRepository.findByIdAndBudget_UserEmail(sourceId, email)
//                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));
//
//        // 2. CHECK FUNDS
//        BigDecimal remainingLimit = getRemainingLimit(sourceId, email);
//
//        LocalDateTime now = LocalDateTime.now();
//        validateTransferRules(source, source.getBudget(), now);
//
//        if (amount.compareTo(remainingLimit) > 0) throw new IllegalStateException("Exceeds period limit: ₦" + remainingLimit);
//        if (amount.compareTo(source.getTotalRemainingAmount()) > 0) throw new IllegalStateException("Insufficient funds");
//
//        // 3. RESOLVE ACCOUNT (Verify Name First)
//        // In a real app, frontend calls this BEFORE the user clicks "Send".
//        // But we double-check here to be safe.
//        String resolvedName = paymentProvider.resolveAccount(externalAccount.getBankCode(), externalAccount.getAccountNumber());
//        if (resolvedName == null) {
//            throw new IllegalArgumentException("Invalid Account Number");
//        }
//
//        // 4. DEBIT ENVELOPE (Lock the money locally first)
//        source.setTotalRemainingAmount(source.getTotalRemainingAmount().subtract(amount));
//        source.setRemainingAmount(remainingLimit.subtract(amount));
//        envelopeRepository.save(source);
//
//        // 5. CREATE TRANSACTION RECORD (PENDING)
//        String myReference = "EXT-" + java.util.UUID.randomUUID().toString();
//
//        // 👇 INSERT THIS BLOCK 👇
//        String description;
//        String type = (String) source.getConditions().getOrDefault("type", "");
//
//        if ("emergency".equalsIgnoreCase(type)) {
//            // Use the reason passed from frontend
//            if (withdrawalReason == null || withdrawalReason.trim().isEmpty()) {
//                throw new IllegalArgumentException("Emergency withdrawals require a valid reason.");
//            }else {
//                description = String.format("EMERGENCY WITHDRAWAL: %s", withdrawalReason != null ? withdrawalReason : "Unspecified");
//            }
//        } else {
//            // Use the standard description
//            description = "Transfer to " + resolvedName + " (" + externalAccount.getBankName() + ")";
//        }
//        // 👆 END INSERT
//
//        TransactionLog txn = TransactionLog.builder()
//                .userId(user.getId())
//                .budgetId(source.getBudget().getId())
//                .sourceEnvelopeId(sourceId)
//                .externalAccountId(externalAccount.getAccountNumber())
//                .amount(amount.negate()) // Money leaving
//                .fee(BigDecimal.ZERO)    // Add fee logic later
//                .transactionType(TransactionType.ENVELOPE_TO_EXTERNAL)
//                .status(TransactionStatus.PENDING) // <--- Important!
//                .reference(myReference)
//                .description("Transfer to " + resolvedName + " (" + externalAccount.getBankName() + ")")
//                .description(description)
//                .createdAt(LocalDateTime.now())
//                .build();
//
//        transactionLogRepository.save(txn);
//
//        // 6. CALL THE "BANK" (The Mock Provider)
//        try {
//            // If this fails (Network error), the catch block runs
//            String providerRef = paymentProvider.initiateTransfer(
//                    externalAccount.getBankCode(),
//                    externalAccount.getAccountNumber(),
//                    resolvedName,
//                    amount,
//                    myReference,
//                    "Payment from " + user.getName()
//            );
//
//            // 7. UPDATE STATUS TO SUCCESS (Since it's a Mock)
//            // NOTE: In real Paystack, you might leave it PENDING and wait for a Webhook.
//            // But for this Mock, we assume immediate success.
//            txn.setStatus(TransactionStatus.COMPLETED);
//            transactionLogRepository.save(txn);
//
//            // 8. NOTIFICATION
//            notificationService.sendNotification(
//                    user.getId().toString(),
//                    "Sent ₦" + amount + " to " + resolvedName,
//                    NotificationType.EXTERNAL_TRANSFER,
//                    source.getBudget().getId(),         // Context 1
//                    sourceId,                           // Context 2
//                    "VIEW_ENVELOPE",                    // Action
//                    "/envelopes/" + sourceId            // URL
//            );
//
//        } catch (Exception e) {
//            // BANK FAILED? ROLLBACK MONEY
//            // Because we are inside @Transactional, throwing an exception automatically
//            // rolls back the Envelope Debit. The money returns to the user.
//            logger.error("External transfer failed: {}", e.getMessage());
//            throw new RuntimeException("Transfer failed: " + e.getMessage());
//        }
//    }
//
//    @Transactional
//    public EnvelopeResponse createEnvelope(EnvelopeRequest request, String email) {
//        // 1. Validation & Setup (Same as before)
//        Budget budget = budgetRepository.findById(request.getBudgetId())
//                .orElseThrow(() -> new EntityNotFoundException("Budget not found with ID: " + request.getBudgetId()));
//
//        if (!budget.getUser().getEmail().equals(email)) {
//            throw new SecurityException("Unauthorized access to budget");
//        }
//        if (budget.getStatus() == BudgetStatus.DRAFT) {
//            throw new IllegalArgumentException("Can only add envelopes to active budgets");
//        }
//
//        // 2. Calculate Total Vault Amount
//        BigDecimal amount;
//        if (request.getExactAmount() != null && request.getExactAmount().compareTo(BigDecimal.ZERO) > 0) {
//            amount = request.getExactAmount();
//        } else {
//            amount = budget.getTotalAmount()
//                    .multiply(request.getPercentage())
//                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
//        }
//
//        validateEnvelopeConditions(request, amount);
//
//        // 3. Condition Defaults (Same as before)
//        Map<String, Object> conditions = request.getConditions();
//        String type = "standard"; // default
//        if (conditions != null && conditions.containsKey("type")) {
//            type = (String) conditions.get("type");
//            switch (type) {
//                case "daily":
//                    conditions.putIfAbsent("gracePeriodMinutes", 30);
//                    break;
//                case "weekly":
//                    conditions.putIfAbsent("gracePeriodMinutes", 30);
//                    break;
//                case "dynamic":
//                    conditions.putIfAbsent("gracePeriodMinutes", 30);
//                    break;
//                case "safe_lock":
//                case "strict_lock":
//                    conditions.putIfAbsent("gracePeriodMinutes", 1440);
//                    break;
//                case "emergency":
//                    // Force the limit to be the TOTAL allocated amount
//                    conditions.put("limit", amount);
//                    // Also ensure grace period is 0 (instant)
//                    conditions.putIfAbsent("gracePeriodMinutes", 0);
//                    break;
//            }
//        }
//
//        // 4. Create Envelope Object
//        Envelope envelope = new Envelope(budget, request.getName(), amount, conditions);
//        envelope.setCreatedAt(fetchCurrentDateTimeFromDatabase());
//
//        // =================================================================================
//        // 🛑 THE CHANGE: STRICT MODE INITIALIZATION 🛑
//        // =================================================================================
//
//        // A. Always fill the Vault
//        envelope.setInitialAmount(amount);
//        envelope.setTotalRemainingAmount(amount);
//
//        // =================================================================
//        // 🛑 FIX: IMMEDIATE FUNDING (Graceful Start)
//        // =================================================================
//
//        // 1. Save first so we have an ID and can use helper methods
//        envelopeRepository.save(envelope);
//
//        // 2. Calculate the correct Limit for this period (Daily/Weekly/Dynamic)
//        // This helper method updates conditions["limit"] based on remaining days
//        recalculateTargetEnvelopeLimit(envelope, budget);
//
//        // 3. Move that limit into the Pocket immediately
//        String typez = (String) conditions.getOrDefault("type", "");
//
//        if ("emergency".equalsIgnoreCase(typez)) {
//            envelope.setRemainingAmount(amount); // Full access
//        } else {
//            // Fetch the limit we just calculated
//            BigDecimal startingPocket = getPeriodLimit(envelope.getConditions());
//
//            // Safety: Cap at Vault Total
//            startingPocket = startingPocket.min(envelope.getTotalRemainingAmount());
//
//            envelope.setRemainingAmount(startingPocket);
//        }
//        // =================================================================
//        // 5. Calculate Schedule
//        // The scheduler will look at this and say "Oh, next payment is tomorrow at 8 AM".
//        // Since remainingAmount is 0, the user is correctly locked until then.
//        envelope.setNextDisbursementAt(budgetLifeCycleManager.calculateNextDisbursementTime(envelope));
//        envelope.setHasMatured(false);
//
//        envelopeRepository.save(envelope);
//
//        // 6. Schedule Tasks
//        budgetLifeCycleManager.scheduleDynamicTasks(envelope);
//
//        notificationService.sendNotification(
//                budget.getUser().getId().toString(),
//                String.format("Created envelope '%s' with ₦%.2f in budget '%s'.",
//                        envelope.getName(), amount, budget.getName()),
//                NotificationType.ENVELOPE_CREATED
//        );
//
//        return toResponse(envelope);
//    }
//
//    public EnvelopeResponse getEnvelopeById(Long envelopeId, String email) {
//        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
//                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible"));
//        return toResponse(envelope);
//    }
//
//    @Transactional
//    public EnvelopeResponse updateEnvelopeConditions(Long envelopeId, EnvelopeRequest request, String email) {
//        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
//                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible"));
//        validateEnvelopeConditions(request, envelope.getAmount());
//        envelope.setConditions(request.getConditions());
//        envelope.setRemainingAmount(getPeriodLimit(request.getConditions()));
//        envelopeRepository.save(envelope);
//
//        notificationService.sendNotification(
//                envelope.getBudget().getUser().getId().toString(),
//                String.format("Updated conditions for envelope '%s' in budget '%s'.",
//                        envelope.getName(), envelope.getBudget().getName()),
//                NotificationType.ENVELOPE_UPDATED
//        );
//
//        return toResponse(envelope);
//    }
//
//    @Transactional
//    public void claimDisbursement(Long pendingDisbursementId, String email) {
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//
//        // 1. Validation (Keep existing checks)
//        PendingDisbursement pd = pendingDisbursementRepository.findById(pendingDisbursementId)
//                .orElseThrow(() -> new IllegalArgumentException("Not found"));
//
//        if (!pd.getStatus().equals(Status.PENDING) || now.isAfter(pd.getExpiresAt())) {
//            throw new IllegalStateException("Disbursement expired");
//        }
//
//        Envelope envelope = envelopeRepository.findById(pd.getEnvelopeId())
//                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));
//
//        // 2. THE CHANGE: Unlock the Envelope instead of funding Wallet
//        // We add the amount BACK to the 'remainingAmount' (Spendable Limit).
//        // Now 'spendEnvelope' will allow transactions up to this amount.
//        envelope.setRemainingAmount(envelope.getRemainingAmount().add(pd.getAmount()));
//
//        // REMOVE THIS: walletService.fundWallet(...); <--- DELETE THIS
//        // REMOVE THIS: envelope.setTotalRemainingAmount(...subtract...); <--- DELETE THIS
//
//        envelopeRepository.save(envelope);
//
//        // 3. Update Status
//        pd.setStatus(Status.CLAIMED);
//        pd.setProcessedAt(now);
//        pendingDisbursementRepository.save(pd);
//
//        // 4. Notification
//        notificationService.sendNotification(
//                email,
//                String.format("₦%.2f unlocked! You can now spend from your '%s' envelope.",
//                        pd.getAmount(), envelope.getName()),
//                NotificationType.DISBURSEMENT_SUCCESS,
//                envelope.getBudget().getId(),       // Context 1
//                envelope.getId(),                   // Context 2
//                "VIEW_ENVELOPE",                    // Action
//                "/envelopes/" + envelope.getId()    // URL
//        );
//    }
//
//    public void deleteEnvelope(Long envelopeId, String email) {
//        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
//                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible"));
//        if (envelope.getBudget().getStatus() == BudgetStatus.ACTIVE) {
//            throw new IllegalStateException("Cannot delete envelope from an active budget");
//        }
//        envelopeRepository.delete(envelope);
//
//        notificationService.sendNotification(
//                envelope.getBudget().getUser().getId().toString(),
//                String.format("Deleted envelope '%s' from budget '%s'.",
//                        envelope.getName(), envelope.getBudget().getName()),
//                NotificationType.ENVELOPE_DELETED
//        );
//    }
//
////    private EnvelopeResponse toResponse(Envelope envelope) {
////        BigDecimal visibleBalance = getSpendableBalance(envelope);
////        return new EnvelopeResponse(
////                envelope.getId(),
////                envelope.getBudget().getId(),
////                envelope.getName(),
////
////                // Legacy
////                envelope.getAmount(),
////                visibleBalance,// amount
////                envelope.getRemainingAmount(),           // remainingAmount
////
////                // New
////                envelope.getAmount(),                    // initialAmount
////                envelope.getTotalRemainingAmount(),
////                visibleBalance,
////
////                envelope.getRemainingAmount(),           // periodRemaining
////                getPeriodLimit(envelope.getConditions()), // periodLimit
////                budgetService.getUsedThisPeriod(envelope),             // usedThisPeriod
////
////                envelope.getConditions(),
////                envelope.getCreatedAt(),
////                envelope.getLastDisbursedAt(),
////                envelope.getNextDisbursementAt()
////        );
////    }
//
//    private EnvelopeResponse toResponse(Envelope envelope) {
//        // 1. Calculate "Visible" Balance (Returns 0.00 if locked)
//        BigDecimal visibleBalance = getSpendableBalance(envelope);
//
//        return new EnvelopeResponse(
//                envelope.getId(),
//                envelope.getBudget().getId(),
//                envelope.getName(),
//
//                // Legacy Fields
//                envelope.getAmount(),                    // amount
//                visibleBalance,                          // remainingAmount (Use visibleBalance!)
//
//                // New Fields
//                envelope.getAmount(),                    // initialAmount
//                envelope.getTotalRemainingAmount(),      // totalRemaining
//                visibleBalance,                          // periodRemaining (Use visibleBalance!)
//
//                getPeriodLimit(envelope.getConditions()), // periodLimit
//                budgetService.getUsedThisPeriod(envelope), // usedThisPeriod
//
//                envelope.getConditions(),
//                envelope.getCreatedAt(),
//                envelope.getLastDisbursedAt(),
//                envelope.getNextDisbursementAt()
//        );
//    }
//
//    // 👇 NEW HELPER METHOD FOR STRICT VISIBILITY
//    private BigDecimal getSpendableBalance(Envelope envelope) {
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//        Map<String, Object> conditions = envelope.getConditions();
//        String type = (String) conditions.getOrDefault("type", "");
//
//        // Graceful Start: Always show money on creation day
//        boolean createdToday = envelope.getCreatedAt().toLocalDate().isEqual(now.toLocalDate());
//
//        try {
//            if ("daily".equals(type)) {
//                if (conditions.containsKey("disbursementTime")) {
//                    LocalTime startTime = LocalTime.parse((String) conditions.get("disbursementTime"));
//                    // STRICT: If not created today AND time is early -> HIDE MONEY
//                    if (!createdToday && now.toLocalTime().isBefore(startTime)) {
//                        return BigDecimal.ZERO;
//                    }
//                }
//            }
//            else if ("dynamic".equals(type)) {
//                // 1. Day Check
//                List<String> rawDays = (List<String>) conditions.getOrDefault("days", List.of());
//                if (rawDays != null && !rawDays.isEmpty()) {
//                    List<String> allowedDays = rawDays.stream().map(String::toUpperCase).collect(Collectors.toList());
//                    String currentDay = now.getDayOfWeek().name();
//
//                    if (!allowedDays.contains(currentDay)) {
//                        return BigDecimal.ZERO; // Wrong Day -> HIDE MONEY
//                    }
//                }
//
//                // 2. Time Check
//                if (conditions.containsKey("disbursementTime")) {
//                    LocalTime startTime = LocalTime.parse((String) conditions.get("disbursementTime"));
//                    // If not created today AND time is early -> HIDE MONEY
//                    if (!createdToday && now.toLocalTime().isBefore(startTime)) {
//                        return BigDecimal.ZERO;
//                    }
//                }
//            }
//        } catch (Exception e) {
//            logger.error("Error calculating spendable balance for envelope {}", envelope.getId(), e);
//        }
//
//        // Default: If rules pass (or it's Weekly/Emergency), show the actual pocket money
//        return envelope.getRemainingAmount();
//    }
//
//    private void validateEnvelopeConditions(EnvelopeRequest request, BigDecimal allocatedAmount) {
//        Map<String, Object> conditions = request.getConditions();
//        if (conditions == null || !conditions.containsKey("type")) {
//            throw new IllegalArgumentException("Envelope conditions must include 'type'");
//        }
//        String type = conditions.get("type").toString().toLowerCase();
//        switch (type) {
//            case "daily":
//                if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number)) {
//                    throw new IllegalArgumentException("Daily envelope must include a numeric 'limit'");
//                }
//                if (!conditions.containsKey("disbursementTime")) {
//                    throw new IllegalArgumentException("Daily envelope must include 'disbursementTime'");
//                }
//                try {
//                    LocalTime.parse((String) conditions.get("disbursementTime"));
//                } catch (DateTimeParseException e) {
//                    throw new IllegalArgumentException("Invalid disbursementTime format: " + conditions.get("disbursementTime"));
//                }
//                break;
//            case "weekly":
//                if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number)) {
//                    throw new IllegalArgumentException("Weekly envelope must include a numeric 'limit'");
//                }
//                break;
//            case "dynamic":
//                if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number)) {
//                    throw new IllegalArgumentException("Dynamic envelope must include a numeric 'limit'");
//                }
//                if (!conditions.containsKey("days") || ((List<?>) conditions.get("days")).isEmpty()) {
//                    throw new IllegalArgumentException("Dynamic envelope must have non-empty 'days'");
//                }
//                if (!conditions.containsKey("disbursementTime")) {
//                    throw new IllegalArgumentException("Dynamic envelope must include 'disbursementTime'");
//                }
//                try {
//                    LocalTime.parse((String) conditions.get("disbursementTime"));
//                } catch (DateTimeParseException e) {
//                    throw new IllegalArgumentException("Invalid disbursementTime format: " + conditions.get("disbursementTime"));
//                }
//                @SuppressWarnings("unchecked")
//                List<String> days = (List<String>) conditions.get("days");
//                try {
//                    days.forEach(day -> DayOfWeek.valueOf(day.toUpperCase()));
//                } catch (IllegalArgumentException e) {
//                    throw new IllegalArgumentException("Invalid day in dynamic condition: " + e.getMessage());
//                }
//                break;
//            case "safe_lock":
//            case "strict_lock":
//                if (!conditions.containsKey("lockStartDate") || !conditions.containsKey("lockDurationDays") || !conditions.containsKey("interestRate")) {
//                    throw new IllegalArgumentException("Lock envelope must include 'lockStartDate', 'lockDurationDays', and 'interestRate'");
//                }
//                try {
//                    LocalDate.parse((String) conditions.get("lockStartDate"));
//                    Integer.parseInt(conditions.get("lockDurationDays").toString());
//                    new BigDecimal(conditions.get("interestRate").toString());
//                } catch (Exception e) {
//                    throw new IllegalArgumentException("Invalid lock conditions: " + e.getMessage());
//                }
//                break;
//            case "emergency":
////                if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number)) {
////                    throw new IllegalArgumentException("Emergency envelope must include a numeric 'limit'");
////                }
//                break;
//            default:
//                throw new IllegalArgumentException("Unsupported envelope type: " + type);
//        }
//    }
//
//    public BigDecimal getRemainingLimit(Long envelopeId, String email) {
//
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//
//        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
//                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible: " + envelopeId));
//
//        Map<String, Object> conditions = envelope.getConditions();
//
//        if (conditions == null || !conditions.containsKey("type") || !conditions.containsKey("limit")) {
//            throw new IllegalArgumentException("Envelope conditions must include 'type' and 'limit'");
//        }
//
//        String type = conditions.get("type").toString();
//        Object limitObj = conditions.get("limit");
//
//        if (!(limitObj instanceof Number)) {
//            throw new IllegalArgumentException("Invalid limit type for envelope " + envelopeId + ": " + limitObj);
//        }
//
//        BigDecimal limit;
//        if (limitObj instanceof Number) {
//            limit = new BigDecimal(limitObj.toString()); // Use toString for precision
//        } else {
//            limit = BigDecimal.ZERO;
//        }
//        LocalDateTime periodStart;
//        LocalDateTime periodEnd;
//
//        // 🛑 NEW: Check for "Graceful Start" (Created Today?)
//        boolean createdToday = envelope.getCreatedAt().toLocalDate().isEqual(now.toLocalDate());
//
//        switch (type) {
//            case "daily":
//                if (conditions.containsKey("disbursementTime")) {
//                    String timeStr = (String) conditions.get("disbursementTime");
//                    try {
//                        LocalTime startTime = LocalTime.parse(timeStr);
//
//                        // FIX: If NOT created today, and time hasn't reached, limit is ZERO.
//                        if (!createdToday && now.toLocalTime().isBefore(startTime)) {
//                            return BigDecimal.ZERO;
//                        }
//                    } catch (DateTimeParseException e) {
//                        logger.error("Invalid time format", e);
//                    }
//                }
//                periodStart = now.toLocalDate().atStartOfDay();
//                periodEnd = periodStart.plusDays(1);
//                break;
//
//            case "weekly":
//                // Weekly usually doesn't have a time restriction, just date.
//                // It starts on Monday (or created day).
//                LocalDate weekStart = now.toLocalDate().minusDays(now.toLocalDate().getDayOfWeek().getValue() - 1);
//                periodStart = weekStart.atStartOfDay();
//                periodEnd = periodStart.plusDays(7);
//                break;
//            case "dynamic":
//                // 1. Validate Days (Case Insensitive)
//                @SuppressWarnings("unchecked")
//                List<String> rawDays = (List<String>) conditions.getOrDefault("days", List.of());
//                List<String> allowedDays = rawDays.stream()
//                        .map(String::toUpperCase)
//                        .toList();
//
//                String currentDay = now.getDayOfWeek().name();
//
//                // If today is not in the list, balance is 0.
//                if (!allowedDays.contains(currentDay)) {
//                    return BigDecimal.ZERO;
//                }
//
//                // 2. Parse Time
//                String timeStr = (String) conditions.getOrDefault("disbursementTime", "08:00");
//                LocalTime targetTime;
//                try {
//                    targetTime = LocalTime.parse(timeStr);
//                } catch (DateTimeParseException e) {
//                    throw new IllegalArgumentException("Invalid disbursementTime format");
//                }
//
//                // 3. UX Check: Is it too early?
//                LocalTime nowTime = now.toLocalTime();
//
//                // 3. THE FIX: Remove 'isAfter' check
//                // Only hide the money if it is TOO EARLY. Never hide it if it's "too late".
//                if (now.toLocalTime().isBefore(targetTime)) {
//                    return BigDecimal.ZERO;
//                }
//
//                // 4. Set Calculation Window (Start Time -> Midnight)
//                periodStart = now.toLocalDate().atTime(targetTime);
//                periodEnd = now.toLocalDate().atTime(23, 59, 59);
//                break;
//            case "safe_lock":
//            case "strict_lock":
//            case "emergency":
//                periodStart = now.minusYears(1);
//                periodEnd = now.plusYears(1);
//                return envelope.getRemainingAmount(); // Use stored value for these types
//            default:
//                throw new IllegalArgumentException("Unsupported envelope type: " + type);
//        }
//        BigDecimal spentAmount = transactionLogRepository.findBySourceEnvelopeIdAndTimeRange(envelopeId, periodStart, periodEnd)
//                .stream().filter(t -> {
//                    String typeTxn = t.getTransactionType().toString().toUpperCase(); // Handle Enum or String safely
//                    return List.of(
//                            "ENVELOPE_TO_ENVELOPE",
//                            "ENVELOPE_TO_EXTERNAL",
//                            "ENVELOPE_TO_USER"
//                    ).contains(typeTxn);
//                })
//                .map(TransactionLog::getAmount)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//
//        BigDecimal remainingLimit = limit.subtract(spentAmount);
//
//        // Safety check: Don't go below zero
//        remainingLimit = remainingLimit.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remainingLimit;
//
//        // Apply the fix
//        envelope.setRemainingAmount(remainingLimit);
//        envelopeRepository.save(envelope);
//
//        return envelope.getRemainingAmount();
//    }
//
//    @Transactional
//    public void resetEnvelopeLimits(Envelope envelope) {
//        Map<String, Object> conditions = envelope.getConditions();
//        if (conditions != null && conditions.containsKey("type")) {
//            String type = conditions.get("type").toString();
//            if (List.of("daily", "weekly", "dynamic").contains(type)) {
//                envelope.setRemainingAmount(getPeriodLimit(conditions));
//                envelopeRepository.save(envelope);
//            }
//        }
//    }
//
//    private BigDecimal getPeriodLimit(Map<String, Object> conditions) {
//        if (conditions != null && conditions.containsKey("limit") && conditions.get("limit") instanceof Number) {
////            return new BigDecimal(((Number) conditions.get("limit")).doubleValue());
//            return new BigDecimal(conditions.get("limit").toString());
//        }
//        return BigDecimal.ZERO;
//    }
//
//    // HELPER METHOD TO RECALC
//    private void recalculateTargetEnvelopeLimit(Envelope envelope, Budget budget) {
//        String type = (String) envelope.getConditions().get("type");
//        if (type == null) return;
//
//        // Only recalculate for time-based spending limits
//        if (!Set.of("daily", "weekly", "dynamic").contains(type)) {
//            return; // safe_lock, strict_lock, emergency → no recalculation
//        }
//
//        LocalDate today = LocalDate.now();
//        LocalDate budgetEnd = budget.getEndDate();
//        if (budgetEnd == null || budgetEnd.isBefore(today)) {
//            return; // Budget ended → no recalc
//        }
//
//        BigDecimal totalRemaining = envelope.getTotalRemainingAmount();
//        long remainingUnits = 0;
//        BigDecimal newLimit;
//
//        switch (type) {
//            case "daily" -> {
//                remainingUnits = ChronoUnit.DAYS.between(today, budgetEnd) + 1; // includes today
//
//                if (remainingUnits <= 0) remainingUnits = 1;
//                // This is the correct formula for your vision 👇
//                newLimit = totalRemaining.divide(BigDecimal.valueOf(remainingUnits), 2, RoundingMode.HALF_UP);
//            }
//            case "weekly" -> {
//                remainingUnits = ChronoUnit.WEEKS.between(today, budgetEnd) + 1;
//                if (remainingUnits <= 0) remainingUnits = 1;
//                newLimit = totalRemaining.divide(BigDecimal.valueOf(remainingUnits), 2, RoundingMode.HALF_UP);
//            }
//            case "dynamic" -> {
//                // "dynamic" = user selected specific days (Mon, Wed, Sat, etc.)
//                // We assume you store selected days as comma-separated string or list
//                String selectedDaysStr = (String) envelope.getConditions().get("selectedDays");
//                if (selectedDaysStr == null || selectedDaysStr.isBlank()) {
//                    // Fallback: treat as daily
//                    remainingUnits = ChronoUnit.DAYS.between(today, budgetEnd) + 1;
//                } else {
//                    List<String> selectedDays = Arrays.stream(selectedDaysStr.split(","))
//                            .map(String::trim)
//                            .map(String::toUpperCase)
//                            .toList();
//
//                    long daysUntilEnd = ChronoUnit.DAYS.between(today, budgetEnd);
//                    long remainingOccurrences = 0;
//
//                    for (int i = 0; i <= daysUntilEnd; i++) {
//                        LocalDate checkDate = today.plusDays(i);
//                        String dayName = checkDate.getDayOfWeek().name(); // MONDAY, TUESDAY...
//                        if (selectedDays.contains(dayName)) {
//                            remainingOccurrences++;
//                        }
//                    }
//                    remainingUnits = remainingOccurrences > 0 ? remainingOccurrences : 1;
//                }
//                newLimit = totalRemaining.divide(BigDecimal.valueOf(remainingUnits), 2, RoundingMode.HALF_UP);
//            }
//            default -> {
//                return; // Should never happen
//            }
//        }
//
//        // 🛑 FIX: Update the ACTUAL "limit" key so the Scheduler sees it!
//        envelope.getConditions().put("limit", newLimit);
//
//        // (Optional) Keep "limit_value" for debugging if you want
//        envelope.getConditions().put("limit_value", newLimit);
//        envelope.getConditions().put("remaining_units", remainingUnits);
//
//        // 🛑 IMPORTANT: Save the changes!
//        envelopeRepository.save(envelope);
//    }
//
//    // 🛑 EXPOSE THIS METHOD PUBLICLY SO BLCM CAN CALL IT
//    public void triggerRecalculation(Envelope envelope) {
//        recalculateTargetEnvelopeLimit(envelope, envelope.getBudget());
//    }
//
//    private BigDecimal getCurrentLimitValue(Envelope e) {
//        return (BigDecimal) e.getConditions().getOrDefault("limit_value", BigDecimal.ZERO);
//    }
//
//    //===========The P2P Logic (Envelope → Other User's Wallet)====================
////    @Transactional(rollbackFor = Exception.class)
////    public void transferToMonieWiseUser(P2PTransferRequest request, String senderEmail) {
////
////    BigDecimal amount = request.getAmount();
////
////
////    // 1. Sanity Checks
////    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
////        throw new IllegalArgumentException("Amount must be positive");
////    }
////
////    User sender = userService.findByEmail(senderEmail);
////    User recipient = userService.findByEmailOrPhone(request.getRecipientIdentity())
////            .orElseThrow(() -> new EntityNotFoundException("Recipient not found with identifier: " + request.getRecipientIdentity()));
////
////
////    if (sender.getId().equals(recipient.getId())) {
////        throw new IllegalArgumentException("You cannot transfer to yourself.");
////    }
////
////    Envelope sourceEnvelope = envelopeRepository.findByIdAndBudget_UserEmail(request.getSourceEnvelopeId(), senderEmail)
////            .orElseThrow(() -> new EntityNotFoundException("Envelope not found or access denied"));
////
////    // =========================================================================
////    // 🛡️ STEP 1.5: LOCK & MATURITY VALIDATION (Added This)
////    // =========================================================================
////    LocalDateTime now = LocalDateTime.now();
////    validateTransferRules(sourceEnvelope, sourceEnvelope.getBudget(), now);
////
////    // Check 1: Has it matured? (If maturity date is in the future, BLOCK IT)
////    if (sourceEnvelope.getMaturedAt() != null && sourceEnvelope.getMaturedAt().isAfter(now)) {
////        throw new IllegalStateException("This envelope is locked until " + sourceEnvelope.getMaturedAt().toLocalDate());
////    }
////
////    // 2. Limit & Balance Checks
////    BigDecimal remainingLimit = getRemainingLimit(request.getSourceEnvelopeId(), senderEmail);
////    if (amount.compareTo(remainingLimit) > 0) {
////        throw new IllegalStateException("Transfer exceeds your spending limit. Available limit: ₦" + remainingLimit);
////    }
////    if (amount.compareTo(sourceEnvelope.getTotalRemainingAmount()) > 0) {
////        throw new IllegalStateException("Insufficient funds. Available balance: ₦" + sourceEnvelope.getTotalRemainingAmount());
////    }
////
////    // 3. EXECUTE DEBIT (Sender's Envelope)
////    sourceEnvelope.setTotalRemainingAmount(sourceEnvelope.getTotalRemainingAmount().subtract(amount));
////    sourceEnvelope.setRemainingAmount(remainingLimit.subtract(amount));
////    envelopeRepository.save(sourceEnvelope);
////
////    // 4. PREPARE SAFE NAMES (Fixes "null" notification issue)
////    String senderName = getSafeName(sender);
////    String recipientName = getSafeName(recipient);
////
////    // 5. EXECUTE CREDIT (Recipient's Wallet)
////    walletService.fundWallet(recipient.getId(), amount, null, true);
////
////    // 6. LOGGING (Double Entry with Unique Refs)
////    String baseRef = UUID.randomUUID().toString();
////
////    // 👇 LOGIC UPDATE: DETERMINE SENDER DESCRIPTION 👇
////    String senderDescription;
////    String type = (String) sourceEnvelope.getConditions().getOrDefault("type", "");
////
////    if ("emergency".equalsIgnoreCase(type)) {
////            String reason = request.getWithdrawalReason(); // Get from DTO
////            if (reason == null || reason.trim().isEmpty()) {
////                throw new IllegalArgumentException("Emergency withdrawals require a valid reason.");
////            }else {
////                // Priority 1: Emergency Reason
////                senderDescription = String.format("EMERGENCY P2P: %s (To: %s)",
////                        request.getWithdrawalReason() != null ? request.getWithdrawalReason() : "Unspecified",
////                        recipientName);
////            }
////
////
////    } else if (request.getNote() != null && !request.getNote().isEmpty()) {
////        // Priority 2: User Note
////        senderDescription = request.getNote();
////    } else {
////        // Priority 3: Default
////        senderDescription = "Transfer to " + recipientName;
////    }
////    // 👆 END UPDATE 👆
////
////    // Log 1: Sender (Debit)
////    TransactionLog senderLog = TransactionLog.builder()
////            .userId(sender.getId())
////            .budgetId(sourceEnvelope.getBudget().getId())
////            .sourceEnvelopeId(sourceEnvelope.getId())
////            .counterpartyUserId(recipient.getId())
////            .amount(amount.negate())
////            .fee(BigDecimal.ZERO)
////            .transactionType(TransactionType.ENVELOPE_TO_USER)
////            .status(TransactionStatus.COMPLETED)
////            .reference("P2P-DB-" + baseRef) // Unique Ref
////            .description(senderDescription)
////            .createdAt(now)
////            .build();
////    transactionLogRepository.save(senderLog);
////
////    // Log 2: Recipient (Credit)
////    TransactionLog recipientLog = TransactionLog.builder()
////            .userId(recipient.getId())
////            .counterpartyUserId(sender.getId())
////            .amount(amount)
////            .fee(BigDecimal.ZERO)
////            .transactionType(TransactionType.USER_TO_ENVELOPE)
////            .status(TransactionStatus.COMPLETED)
////            .reference("P2P-CR-" + baseRef) // Unique Ref
////            .description("Received from " + senderName)
////            .createdAt(now)
////            .build();
////    transactionLogRepository.save(recipientLog);
////
////    // 7. NOTIFICATIONS
////    // Sender
////    notificationService.sendNotification(
////            sender.getId().toString(),
////            "You sent ₦" + amount + " to " + recipientName,
////            NotificationType.ENVELOPE_TRANSFER,
////            sourceEnvelope.getBudget().getId(),
////            sourceEnvelope.getId(),
////            "VIEW_ENVELOPE",
////            "/envelopes/" + sourceEnvelope.getId()
////    );
////
////    // Recipient
////    notificationService.sendNotification(
////            recipient.getId().toString(),
////            senderName + " sent you ₦" + amount,
////            NotificationType.WALLET_DEPOSIT,
////            null,
////            null,
////            "VIEW_WALLET",
////            "/dashboard"
////    );
////
////    // 8. AUTO-SAVE BENEFICIARY
////    try {
////        beneficiaryService.addBeneficiary(sender.getId(), recipient.getEmail(), recipientName);
////    } catch (Exception e) {
////        logger.warn("Auto-save beneficiary failed: {}", e.getMessage());
////    }
////}
////
//
//    @Transactional(rollbackFor = Exception.class)
//    public void transferToMonieWiseUser(P2PTransferRequest request, String senderEmail) {
//        BigDecimal amount = request.getAmount();
//
//        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
//            throw new IllegalArgumentException("Amount must be positive");
//        }
//
//        User sender = userService.findByEmail(senderEmail);
//        User recipient = userService.findByEmailOrPhone(request.getRecipientIdentity())
//                .orElseThrow(() -> new EntityNotFoundException("Recipient not found"));
//
//        if (sender.getId().equals(recipient.getId())) {
//            throw new IllegalArgumentException("You cannot transfer to yourself.");
//        }
//
//        Envelope sourceEnvelope = envelopeRepository.findByIdAndBudget_UserEmail(request.getSourceEnvelopeId(), senderEmail)
//                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));
//
//        LocalDateTime now = LocalDateTime.now();
//        validateTransferRules(sourceEnvelope, sourceEnvelope.getBudget(), now);
//
//        if (sourceEnvelope.getMaturedAt() != null && sourceEnvelope.getMaturedAt().isAfter(now)) {
//            throw new IllegalStateException("This envelope is locked until " + sourceEnvelope.getMaturedAt().toLocalDate());
//        }
//
//        // =====================================================================
//        // 🛑 FIX: SYNC MEMORY WITH DATABASE CALCULATION
//        // =====================================================================
//
//        // 1. Calculate the authoritative limit (This updates the DB behind the scenes)
//        BigDecimal authoritativeBalance = getRemainingLimit(request.getSourceEnvelopeId(), senderEmail);
//
//        // 2. CRITICAL: Update the local object to match the authoritative balance
//        // If we don't do this, sourceEnvelope.getRemainingAmount() returns the OLD value
//        sourceEnvelope.setRemainingAmount(authoritativeBalance);
//
//        // 3. Now perform the check using the SYNCED balance
//        if (amount.compareTo(sourceEnvelope.getRemainingAmount()) > 0) {
//            String cleanLimit = String.format("%,.2f", sourceEnvelope.getRemainingAmount());
//            throw new IllegalStateException("Transfer exceeds your spending limit. Available: ₦" + cleanLimit);
//        }
//
//        // Check 4: Actual Cash (Vault) - Safety Net
//        if (amount.compareTo(sourceEnvelope.getTotalRemainingAmount()) > 0) {
//            throw new IllegalStateException("Insufficient funds in vault.");
//        }
//
//        // 4. Subtract from the SYNCED balance
//        sourceEnvelope.setTotalRemainingAmount(sourceEnvelope.getTotalRemainingAmount().subtract(amount));
//        sourceEnvelope.setRemainingAmount(sourceEnvelope.getRemainingAmount().subtract(amount));
//
//        envelopeRepository.save(sourceEnvelope);
//
//        // =====================================================================
//        // END FIX
//        // =====================================================================
//
//        // Names & Logs
//        String senderName = getSafeName(sender);
//        String recipientName = getSafeName(recipient);
//        walletService.fundWallet(recipient.getId(), amount, null, true);
//
//        String baseRef = UUID.randomUUID().toString();
//        String description = "Transfer to " + recipientName;
//        String type = (String) sourceEnvelope.getConditions().getOrDefault("type", "");
//        if ("emergency".equalsIgnoreCase(type) && request.getWithdrawalReason() != null) {
//            description = "EMERGENCY: " + request.getWithdrawalReason();
//        } else if (request.getNote() != null) {
//            description = request.getNote();
//        }
//
//        // Log Sender
//        TransactionLog senderLog = TransactionLog.builder()
//                .userId(sender.getId())
//                .budgetId(sourceEnvelope.getBudget().getId())
//                .sourceEnvelopeId(sourceEnvelope.getId())
//                .counterpartyUserId(recipient.getId())
//                .amount(amount.negate())
//                .fee(BigDecimal.ZERO)
//                .transactionType(TransactionType.ENVELOPE_TO_USER)
//                .status(TransactionStatus.COMPLETED)
//                .reference("P2P-DB-" + baseRef)
//                .description(description)
//                .createdAt(now)
//                .build();
//        transactionLogRepository.save(senderLog);
//
//        // Log Recipient
//        TransactionLog recipientLog = TransactionLog.builder()
//                .userId(recipient.getId())
//                .counterpartyUserId(sender.getId())
//                .amount(amount)
//                .fee(BigDecimal.ZERO)
//                .transactionType(TransactionType.USER_TO_ENVELOPE)
//                .status(TransactionStatus.COMPLETED)
//                .reference("P2P-CR-" + baseRef)
//                .description("Received from " + senderName)
//                .createdAt(now)
//                .build();
//        transactionLogRepository.save(recipientLog);
//
//        // Notifications
//        notificationService.sendNotification(sender.getId().toString(), "Sent ₦" + amount + " to " + recipientName, NotificationType.ENVELOPE_TRANSFER, sourceEnvelope.getBudget().getId(), sourceEnvelope.getId(), "VIEW_ENVELOPE", "/envelopes/" + sourceEnvelope.getId());
//        notificationService.sendNotification(recipient.getId().toString(), senderName + " sent you ₦" + amount, NotificationType.WALLET_DEPOSIT, null, null, "VIEW_WALLET", "/dashboard");
//
//        try { beneficiaryService.addBeneficiary(sender.getId(), recipient.getEmail(), recipientName); } catch (Exception e) {}
//    }
////    @Transactional(rollbackFor = Exception.class)
////    public void transferToMonieWiseUser(P2PTransferRequest request, String senderEmail) {
////        BigDecimal amount = request.getAmount();
////
////        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
////            throw new IllegalArgumentException("Amount must be positive");
////        }
////
////        User sender = userService.findByEmail(senderEmail);
////        User recipient = userService.findByEmailOrPhone(request.getRecipientIdentity())
////                .orElseThrow(() -> new EntityNotFoundException("Recipient not found"));
////
////        if (sender.getId().equals(recipient.getId())) {
////            throw new IllegalArgumentException("You cannot transfer to yourself.");
////        }
////
////        Envelope sourceEnvelope = envelopeRepository.findByIdAndBudget_UserEmail(request.getSourceEnvelopeId(), senderEmail)
////                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));
////
////        LocalDateTime now = LocalDateTime.now();
////        validateTransferRules(sourceEnvelope, sourceEnvelope.getBudget(), now);
////
////        if (sourceEnvelope.getMaturedAt() != null && sourceEnvelope.getMaturedAt().isAfter(now)) {
////            throw new IllegalStateException("This envelope is locked until " + sourceEnvelope.getMaturedAt().toLocalDate());
////        }
////
////        // 🛑 FIX: SWAP THESE TWO LINES 👇
////
////        // 1. Calculate and Fill the Pocket (Lazy Auto-Deposit)
////        BigDecimal limitAvailable = getRemainingLimit(request.getSourceEnvelopeId(), senderEmail);
////
////        // 2. NOW fetch the updated balance (It will now be correct)
////        BigDecimal pocketBalance = sourceEnvelope.getRemainingAmount();
////
////        // 🛑 OPTIONAL: CLEAN UP THE ERROR MESSAGE FORMATTING
////        if (amount.compareTo(limitAvailable) > 0) {
////            String cleanLimit = String.format("%,.2f", limitAvailable); // Fixes the "1.09E-13" error
////            throw new IllegalStateException("Transfer exceeds your spending limit. Available: ₦" + cleanLimit);
////        }
////
////        // Check 2: Actual Cash (Vault) - Safety Net
////        if (amount.compareTo(sourceEnvelope.getTotalRemainingAmount()) > 0) {
////            throw new IllegalStateException("Insufficient funds in vault.");
////        }
////
////        // Check 3: Actual Pocket (Wallet) - Critical
////        if (amount.compareTo(pocketBalance) > 0) {
////            throw new IllegalStateException("Insufficient funds in pocket. Available: ₦" + pocketBalance);
////        }
////
////        // 🛑 FIX: Subtract from Actual Balance, NOT Limit
////        sourceEnvelope.setTotalRemainingAmount(sourceEnvelope.getTotalRemainingAmount().subtract(amount));
////        sourceEnvelope.setRemainingAmount(sourceEnvelope.getRemainingAmount().subtract(amount));
////
////        envelopeRepository.save(sourceEnvelope);
////
////        // Names & Logs
////        String senderName = getSafeName(sender);
////        String recipientName = getSafeName(recipient);
////        walletService.fundWallet(recipient.getId(), amount, null, true);
////
////        String baseRef = UUID.randomUUID().toString();
////        String description = "Transfer to " + recipientName;
////        String type = (String) sourceEnvelope.getConditions().getOrDefault("type", "");
////        if ("emergency".equalsIgnoreCase(type) && request.getWithdrawalReason() != null) {
////            description = "EMERGENCY: " + request.getWithdrawalReason();
////        } else if (request.getNote() != null) {
////            description = request.getNote();
////        }
////
////        // Log Sender
////        TransactionLog senderLog = TransactionLog.builder()
////                .userId(sender.getId())
////                .budgetId(sourceEnvelope.getBudget().getId())
////                .sourceEnvelopeId(sourceEnvelope.getId())
////                .counterpartyUserId(recipient.getId())
////                .amount(amount.negate())
////                .fee(BigDecimal.ZERO)
////                .transactionType(TransactionType.ENVELOPE_TO_USER)
////                .status(TransactionStatus.COMPLETED)
////                .reference("P2P-DB-" + baseRef)
////                .description(description)
////                .createdAt(now)
////                .build();
////        transactionLogRepository.save(senderLog);
////
////        // Log Recipient
////        TransactionLog recipientLog = TransactionLog.builder()
////                .userId(recipient.getId())
////                .counterpartyUserId(sender.getId())
////                .amount(amount)
////                .fee(BigDecimal.ZERO)
////                .transactionType(TransactionType.USER_TO_ENVELOPE)
////                .status(TransactionStatus.COMPLETED)
////                .reference("P2P-CR-" + baseRef)
////                .description("Received from " + senderName)
////                .createdAt(now)
////                .build();
////        transactionLogRepository.save(recipientLog);
////
////        // Notifications
////        notificationService.sendNotification(sender.getId().toString(), "Sent ₦" + amount + " to " + recipientName, NotificationType.ENVELOPE_TRANSFER, sourceEnvelope.getBudget().getId(), sourceEnvelope.getId(), "VIEW_ENVELOPE", "/envelopes/" + sourceEnvelope.getId());
////        notificationService.sendNotification(recipient.getId().toString(), senderName + " sent you ₦" + amount, NotificationType.WALLET_DEPOSIT, null, null, "VIEW_WALLET", "/dashboard");
////
////        try { beneficiaryService.addBeneficiary(sender.getId(), recipient.getEmail(), recipientName); } catch (Exception e) {}
////    }
//
//// =================================================================================
//    // HELPER METHOD (Add this to the bottom of your Service Class)
//    // =================================================================================
//    private String getSafeName(User user) {
//        if (user.getProfileData() != null) {
//            // Try to get "fullName", fallback to "name"
//            Object nameObj = user.getProfileData().getOrDefault("fullName", user.getProfileData().get("name"));
//            if (nameObj != null && !nameObj.toString().trim().isEmpty()) {
//                return nameObj.toString();
//            }
//        }
//        // Fallback: Use Username from Email (e.g. "olaore66@..." -> "Olaore66")
//        if (user.getEmail() != null) {
//            String handle = user.getEmail().split("@")[0];
//            return handle.substring(0, 1).toUpperCase() + handle.substring(1);
//        }
//        return "User"; // Ultimate fallback
//    }
//
//
//    public PendingDisbursement findPendingDisbursementByEnvelopeId(Long envelopeId) {
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//
//        // 1. Find a pending item for this envelope
//        return pendingDisbursementRepository.findFirstByEnvelopeIdAndStatus(envelopeId, Status.PENDING)
//                .filter(pd -> pd.getExpiresAt().isAfter(now)) // 2. Ensure it hasn't expired
//                .orElse(null); // Return null if nothing valid found
//    }
//
//}


package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.config.BudgetLifeCycleManager;
import com.moniewise.moniewise_backend.controller.BudgetController;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.request.P2PTransferRequest;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.*;
import com.moniewise.moniewise_backend.exception.EntityNotFoundException;
import com.moniewise.moniewise_backend.externalTransfers.PaymentProvider;
import com.moniewise.moniewise_backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.time.ZonedDateTime;
import java.time.ZoneId;

@Service
public class EnvelopeService {

    private static final Logger logger = LoggerFactory.getLogger(EnvelopeService.class);
    private final EnvelopeRepository envelopeRepository;
    private final BudgetRepository budgetRepository;
    private final RevenueLogRepository revenueLogRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionLogRepository transactionLogRepository;
    private final NotificationService notificationService;
    private final WalletService walletService;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final BudgetLifeCycleManager budgetLifeCycleManager;

    private final BudgetService budgetService;
    private final PendingDisbursementRepository pendingDisbursementRepository;
    private final JdbcTemplate jdbcTemplate;

    private final BeneficiaryService beneficiaryService;

    private final PaymentProvider paymentProvider;

    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    public EnvelopeService(
            EnvelopeRepository envelopeRepository,
            BudgetRepository budgetRepository,
            RevenueLogRepository revenueLogRepository,
            UserService userService,
            TransactionLogRepository transactionLogRepository,
            NotificationService notificationService,
            WalletService walletService,
            ScheduledTaskRepository scheduledTaskRepository,
            @Lazy BudgetLifeCycleManager budgetLifeCycleManager,
            BudgetService budgetService, PendingDisbursementRepository pendingDisbursementRepository,
            JdbcTemplate jdbcTemplate, BeneficiaryService beneficiaryService, PaymentProvider paymentProvider) {
        this.envelopeRepository = envelopeRepository;
        this.budgetRepository = budgetRepository;
        this.revenueLogRepository = revenueLogRepository;
        this.userService = userService;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.walletService = walletService;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.budgetLifeCycleManager = budgetLifeCycleManager;
        this.budgetService = budgetService;
        this.pendingDisbursementRepository = pendingDisbursementRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.beneficiaryService = beneficiaryService;
        this.paymentProvider = paymentProvider;
    }

    @PostConstruct
    public void init() {
        logger.info("Revenue Wallet User ID: {}", revenueWalletUserId);
    }

    // ✅ TIMEZONE FIX: Uses Lagos Time for consistency
    private LocalDateTime fetchCurrentDateTimeFromDatabase() {
        return ZonedDateTime.now(ZoneId.of("Africa/Lagos")).toLocalDateTime();
    }

    // Restored this method from your original code
    private LocalDateTime fetchUserLocalTime(User user) {
        ZonedDateTime nowUTC = ZonedDateTime.now(ZoneId.of("UTC"));
        return nowUTC.withZoneSameInstant(user.getZoneId()).toLocalDateTime();
    }

    // =========================================================================
    // 1. MOVE MONEY (INTERNAL) - FIXED ✅
    // =========================================================================
    @Transactional
    public void moveMoney(Long sourceId, Long targetId, Double amount, String email, String withdrawalReason) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");

        User user = userService.findByEmail(email);

        // Initial Load
        Envelope source = envelopeRepository.findByIdAndBudget_UserEmail(sourceId, email)
                .orElseThrow(() -> new EntityNotFoundException("Source not found"));
        Envelope target = envelopeRepository.findByIdAndBudget_UserEmail(targetId, email)
                .orElseThrow(() -> new EntityNotFoundException("Target not found"));
        Budget sourceBudget = source.getBudget();

        if (!sourceBudget.getId().equals(target.getBudget().getId())) {
            throw new IllegalArgumentException("Envelopes must belong to the same budget");
        }
        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Budget must be active");
        }

        validateTransferRules(source, sourceBudget, now);

        // 🛑 FIX START: RE-FETCH LOGIC FOR MOVE MONEY
        getRemainingLimit(sourceId, email); // Force DB Update
        source = envelopeRepository.findById(sourceId) // Reload Fresh Data
                .orElseThrow(() -> new EntityNotFoundException("Source envelope not found during refresh"));
        // 🛑 FIX END

        BigDecimal transferAmount = BigDecimal.valueOf(amount);
        if (transferAmount.compareTo(source.getRemainingAmount()) > 0) {
            throw new IllegalArgumentException("Insufficient spendable limit. Available: ₦" + source.getRemainingAmount());
        }
        if (transferAmount.compareTo(source.getTotalRemainingAmount()) > 0) {
            throw new IllegalArgumentException("Insufficient funds in vault.");
        }

        // Subtract Source
        BigDecimal newSourceTotal = source.getTotalRemainingAmount().subtract(transferAmount);
        BigDecimal newSourcePocket = source.getRemainingAmount().subtract(transferAmount);
        source.setTotalRemainingAmount(newSourceTotal);
        source.setRemainingAmount(newSourcePocket);

        recalculateTargetEnvelopeLimit(source, sourceBudget);
        envelopeRepository.save(source);
        envelopeRepository.flush();

        // Add Target
        target.setTotalRemainingAmount(target.getTotalRemainingAmount().add(transferAmount));
        target.setRemainingAmount(target.getRemainingAmount().add(transferAmount));

        recalculateTargetEnvelopeLimit(target, sourceBudget);
        envelopeRepository.save(target);

        // Logs
        String description;
        String type = (String) source.getConditions().getOrDefault("type", "");

        if ("emergency".equalsIgnoreCase(type)) {
            if (withdrawalReason == null || withdrawalReason.trim().isEmpty()) {
                throw new IllegalArgumentException("Emergency withdrawals require a valid reason.");
            } else {
                description = String.format("EMERGENCY WITHDRAWAL: %s (To: %s)",
                        withdrawalReason, target.getName());
            }
        } else {
            description = String.format("From %s → %s • Moved ₦%.2f",
                    source.getName(), target.getName(), transferAmount);
        }

        TransactionLog transactionLog = new TransactionLog(
                user.getId(), sourceBudget.getId(), sourceId, targetId, transferAmount,
                TransactionType.ENVELOPE_TO_ENVELOPE, description
        );
        transactionLog.setStatus(TransactionStatus.COMPLETED);
        transactionLog.setReference("ENV-MOV-" + sourceId + "-" + System.currentTimeMillis());
        transactionLog.setCreatedAt(now);
        transactionLogRepository.save(transactionLog);

        BigDecimal remainingLimit = newSourcePocket;
        String period = source.getConditions().getOrDefault("type", "period").toString().equals("daily") ? "today" : "this period";

        notificationService.sendNotification(
                user.getId().toString(),
                String.format("Moved ₦%.2f. %s Remaining: ₦%.2f.", transferAmount, period, remainingLimit),
                NotificationType.ENVELOPE_TRANSFER,
                sourceBudget.getId(), sourceId, "VIEW_ENVELOPE", "/envelopes/" + sourceId
        );
    }

    // =========================================================================
    // 2. P2P TRANSFER - FIXED & CLEANED ✅
    // =========================================================================
    @Transactional(rollbackFor = Exception.class)
    public void transferToMonieWiseUser(P2PTransferRequest request, String senderEmail) {

        BigDecimal amount = request.getAmount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        User sender = userService.findByEmail(senderEmail);

        User recipient = userService.findByEmailOrPhone(request.getRecipientIdentity())
                .orElseThrow(() -> new EntityNotFoundException("Recipient not found"));

        if (sender.getId().equals(recipient.getId())) {
            throw new IllegalArgumentException("You cannot transfer to yourself.");
        }

        // Initial Load
        Envelope sourceEnvelope = envelopeRepository.findByIdAndBudget_UserEmail(request.getSourceEnvelopeId(), senderEmail)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        validateTransferRules(sourceEnvelope, sourceEnvelope.getBudget(), now);

        if (sourceEnvelope.getMaturedAt() != null && sourceEnvelope.getMaturedAt().isAfter(now)) {
            throw new IllegalStateException("This envelope is locked until " + sourceEnvelope.getMaturedAt().toLocalDate());
        }

        // 🛑 FIX START: RE-FETCH LOGIC
        getRemainingLimit(request.getSourceEnvelopeId(), senderEmail); // Force DB Update
        sourceEnvelope = envelopeRepository.findById(sourceEnvelope.getId()) // Reload Fresh Data
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found during refresh"));
        // 🛑 FIX END

        // Validate Funds (Fresh Data)
        if (amount.compareTo(sourceEnvelope.getRemainingAmount()) > 0) {
            String cleanLimit = String.format("%,.2f", sourceEnvelope.getRemainingAmount());
            throw new IllegalStateException("Transfer exceeds your spending limit. Available: ₦" + cleanLimit);
        }
        if (amount.compareTo(sourceEnvelope.getTotalRemainingAmount()) > 0) {
            throw new IllegalStateException("Insufficient funds in vault.");
        }

        // Subtract
        sourceEnvelope.setTotalRemainingAmount(sourceEnvelope.getTotalRemainingAmount().subtract(amount));
        sourceEnvelope.setRemainingAmount(sourceEnvelope.getRemainingAmount().subtract(amount));

        recalculateTargetEnvelopeLimit(sourceEnvelope, sourceEnvelope.getBudget());
        envelopeRepository.save(sourceEnvelope);

        // Credit & Logs
        String senderName = getSafeName(sender);
        String recipientName = getSafeName(recipient);
        walletService.fundWallet(recipient.getId(), amount, null, true);

        String baseRef = UUID.randomUUID().toString();
        String description = "Transfer to " + recipientName;
        String type = (String) sourceEnvelope.getConditions().getOrDefault("type", "");
        if ("emergency".equalsIgnoreCase(type) && request.getWithdrawalReason() != null) {
            description = "EMERGENCY: " + request.getWithdrawalReason();
        } else if (request.getNote() != null) {
            description = request.getNote();
        }

        TransactionLog senderLog = TransactionLog.builder()
                .userId(sender.getId())
                .budgetId(sourceEnvelope.getBudget().getId())
                .sourceEnvelopeId(sourceEnvelope.getId())
                .counterpartyUserId(recipient.getId())
                .amount(amount.negate())
                .fee(BigDecimal.ZERO)
                .transactionType(TransactionType.ENVELOPE_TO_USER)
                .status(TransactionStatus.COMPLETED)
                .reference("P2P-DB-" + baseRef)
                .description(description)
                .createdAt(now)
                .build();
        transactionLogRepository.save(senderLog);

        TransactionLog recipientLog = TransactionLog.builder()
                .userId(recipient.getId())
                .counterpartyUserId(sender.getId())
                .amount(amount)
                .fee(BigDecimal.ZERO)
                .transactionType(TransactionType.USER_TO_ENVELOPE)
                .status(TransactionStatus.COMPLETED)
                .reference("P2P-CR-" + baseRef)
                .description("Received from " + senderName)
                .createdAt(now)
                .build();
        transactionLogRepository.save(recipientLog);

        notificationService.sendNotification(sender.getId().toString(), "Sent ₦" + amount + " to " + recipientName, NotificationType.ENVELOPE_TRANSFER, sourceEnvelope.getBudget().getId(), sourceEnvelope.getId(), "VIEW_ENVELOPE", "/envelopes/" + sourceEnvelope.getId());
        notificationService.sendNotification(recipient.getId().toString(), senderName + " sent you ₦" + amount, NotificationType.WALLET_DEPOSIT, null, null, "VIEW_WALLET", "/dashboard");

        try { beneficiaryService.addBeneficiary(sender.getId(), recipient.getEmail(), recipientName); } catch (Exception e) {}
    }

    // =========================================================================
    // 3. GET REMAINING LIMIT - FIXED ✅
    // =========================================================================
    public BigDecimal getRemainingLimit(Long envelopeId, String email) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible: " + envelopeId));

        Map<String, Object> conditions = envelope.getConditions();

        // 1. Handling Emergency Envelopes (No Limit)
        String type = conditions.getOrDefault("type", "standard").toString();
        if ("emergency".equalsIgnoreCase(type)) {
            return envelope.getRemainingAmount();
        }

        if (!conditions.containsKey("limit")) {
            throw new IllegalArgumentException("Limit missing");
        }

        BigDecimal limit = new BigDecimal(conditions.get("limit").toString());
        LocalDateTime periodStart;

        // 2. Determine the Time Window
        boolean createdToday = envelope.getCreatedAt().toLocalDate().isEqual(now.toLocalDate());

        switch (type) {
            case "daily":
                if (conditions.containsKey("disbursementTime")) {
                    String timeStr = (String) conditions.get("disbursementTime");
                    try {
                        LocalTime startTime = LocalTime.parse(timeStr);
                        if (!createdToday && now.toLocalTime().isBefore(startTime)) {
                            return BigDecimal.ZERO;
                        }
                    } catch (DateTimeParseException e) {
                        logger.error("Invalid time format", e);
                    }
                }
                periodStart = now.toLocalDate().atStartOfDay();
                break;

            case "weekly":
                periodStart = now.toLocalDate().minusDays(now.getDayOfWeek().getValue() - 1).atStartOfDay();
                break;
            case "dynamic":
                @SuppressWarnings("unchecked")
                List<String> rawDays = (List<String>) conditions.getOrDefault("days", List.of());
                List<String> allowedDays = rawDays.stream()
                        .map(String::toUpperCase)
                        .toList();

                String currentDay = now.getDayOfWeek().name();

                if (!allowedDays.contains(currentDay)) {
                    return BigDecimal.ZERO;
                }

                String timeStr = (String) conditions.getOrDefault("disbursementTime", "08:00");
                LocalTime targetTime;
                try {
                    targetTime = LocalTime.parse(timeStr);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid disbursementTime format");
                }

                if (now.toLocalTime().isBefore(targetTime)) {
                    return BigDecimal.ZERO;
                }

//                String timeStr = (String) conditions.getOrDefault("disbursementTime", "08:00");
                periodStart = now.toLocalDate().atTime(LocalTime.parse(timeStr));
                break;
            case "safe_lock":
            case "strict_lock":
            case "emergency":
                periodStart = now.minusYears(1);
//                periodEnd = now.plusYears(1);
                return envelope.getRemainingAmount();
            default:
                throw new IllegalArgumentException("Unsupported envelope type: " + type);
        }

        // 3. 🛑 THE FIX: DEFINE WHAT COUNTS AS SPENDING 🛑
        // We strictly define: "Money leaving the envelope".
        // We do NOT include refunds or deposits here.
        List<TransactionType> spendingTypes = List.of(
                TransactionType.ENVELOPE_TO_ENVELOPE, // Moving money out
                TransactionType.ENVELOPE_TO_EXTERNAL, // Sending to Bank
                TransactionType.ENVELOPE_TO_USER      // P2P Transfer
        );

//        BigDecimal spentAmount = transactionLogRepository.findBySourceEnvelopeIdAndTimeRange(envelopeId, periodStart, periodEnd)
//                .stream().filter(t -> {
//                    String typeTxn = t.getTransactionType().toString().toUpperCase();
//                    return List.of(
//                            "ENVELOPE_TO_ENVELOPE",
//                            "ENVELOPE_TO_EXTERNAL",
//                            "ENVELOPE_TO_USER"
//                    ).contains(typeTxn);
//                })
//                .map(TransactionLog::getAmount)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 4. 🚀 EXECUTE NUCLEAR QUERY
        // This asks the DB: "Exactly how much left this envelope since [periodStart]?"
        BigDecimal spentAmount = transactionLogRepository.calculateTotalSpent(
                envelopeId,
                periodStart,
                spendingTypes
        );

        BigDecimal remainingLimit = limit.subtract(spentAmount);

        remainingLimit = remainingLimit.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remainingLimit;

        // ✅ VAULT CAP FIX
        BigDecimal vaultBalance = envelope.getTotalRemainingAmount();
        if (remainingLimit.compareTo(vaultBalance) > 0) {
            remainingLimit = vaultBalance;
        }

        envelope.setRemainingAmount(remainingLimit);
        envelopeRepository.save(envelope);

        return envelope.getRemainingAmount();
    }

    // -------------------------------------------------------------------------
    // HELPERS & OTHER METHODS
    // -------------------------------------------------------------------------

    private void validateTransferRules(Envelope source, Budget budget, LocalDateTime now) {
        Map<String, Object> conditions = source.getConditions();
        if (conditions == null || !conditions.containsKey("type")) return;

        String type = ((String) conditions.get("type")).toLowerCase();
        boolean createdToday = source.getCreatedAt().toLocalDate().isEqual(now.toLocalDate());

        switch (type) {
            case "dynamic":
                List<String> days = (List<String>) conditions.getOrDefault("days", List.of());
                List<String> upperDays = days.stream().map(String::toUpperCase).collect(Collectors.toList());
                String todayName = now.getDayOfWeek().name();

                if (!upperDays.contains(todayName)) {
                    throw new IllegalArgumentException("Dynamic transfers are only allowed on: " + days);
                }
                String timeStr = (String) conditions.getOrDefault("disbursementTime", "00:00");
                try {
                    LocalTime startTime = LocalTime.parse(timeStr);
                    if (now.toLocalTime().isBefore(startTime)) {
                        throw new IllegalArgumentException("Dynamic funds are locked until " + startTime);
                    }
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid time format");
                }
                break;

            case "daily":
                if (conditions.containsKey("disbursementTime")) {
                    String dailyTimeStr = (String) conditions.get("disbursementTime");
                    LocalTime startTime = LocalTime.parse(dailyTimeStr);
                    if (!createdToday && now.toLocalTime().isBefore(startTime)) {
                        throw new IllegalArgumentException("Daily funds are locked until " + startTime);
                    }
                }
                break;

            case "strict_lock":
            case "safe_lock":
                if (source.getMaturedAt() != null && now.isBefore(source.getMaturedAt())) {
                    throw new IllegalStateException("This envelope is locked until " + source.getMaturedAt().toLocalDate());
                }
                else if (conditions.containsKey("lockStartDate") && conditions.containsKey("lockDurationDays")) {
                    LocalDate lockStart = LocalDate.parse((String) conditions.get("lockStartDate"));
                    int duration = Integer.parseInt(conditions.get("lockDurationDays").toString());
                    LocalDate unlockDate = lockStart.plusDays(duration);
                    if (now.toLocalDate().isBefore(unlockDate)) {
                        throw new IllegalStateException("This envelope is locked until " + unlockDate);
                    }
                }
                break;
            case "emergency":
                break;
        }
    }

    private void checkTimeWindow(LocalDateTime now, String timeStr, String typeName) {
        try {
            LocalTime startTime = LocalTime.parse(timeStr);
            LocalTime endTime = startTime.plusHours(1);
            LocalTime currentTime = now.toLocalTime();
            if (currentTime.isBefore(startTime) || currentTime.isAfter(endTime)) {
                throw new IllegalArgumentException(String.format("%s transfers only allowed between %s and %s", typeName, startTime, endTime));
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid time format in envelope settings: " + timeStr);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void transferToExternal(Long sourceId, BudgetController.ExternalAccount externalAccount, Double amountDouble, String email, String withdrawalReason) {
        BigDecimal amount = BigDecimal.valueOf(amountDouble);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("Amount must be positive");

        User user = userService.findByEmail(email);
        Envelope source = envelopeRepository.findByIdAndBudget_UserEmail(sourceId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));

        // 🛑 ADDED RE-FETCH HERE TO BE SAFE 🛑
        getRemainingLimit(sourceId, email);
        source = envelopeRepository.findById(source.getId())
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found during refresh"));

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        validateTransferRules(source, source.getBudget(), now);

        if (amount.compareTo(source.getRemainingAmount()) > 0) throw new IllegalStateException("Exceeds period limit: ₦" + source.getRemainingAmount());
        if (amount.compareTo(source.getTotalRemainingAmount()) > 0) throw new IllegalStateException("Insufficient funds");

        String resolvedName = paymentProvider.resolveAccount(externalAccount.getBankCode(), externalAccount.getAccountNumber());
        if (resolvedName == null) {
            throw new IllegalArgumentException("Invalid Account Number");
        }

        source.setTotalRemainingAmount(source.getTotalRemainingAmount().subtract(amount));
        source.setRemainingAmount(source.getRemainingAmount().subtract(amount));
        envelopeRepository.save(source);

        String myReference = "EXT-" + java.util.UUID.randomUUID().toString();
        String description;
        String type = (String) source.getConditions().getOrDefault("type", "");

        if ("emergency".equalsIgnoreCase(type)) {
            if (withdrawalReason == null || withdrawalReason.trim().isEmpty()) {
                throw new IllegalArgumentException("Emergency withdrawals require a valid reason.");
            } else {
                description = String.format("EMERGENCY WITHDRAWAL: %s", withdrawalReason != null ? withdrawalReason : "Unspecified");
            }
        } else {
            description = "Transfer to " + resolvedName + " (" + externalAccount.getBankName() + ")";
        }

        TransactionLog txn = TransactionLog.builder()
                .userId(user.getId())
                .budgetId(source.getBudget().getId())
                .sourceEnvelopeId(sourceId)
                .externalAccountId(externalAccount.getAccountNumber())
                .amount(amount.negate())
                .fee(BigDecimal.ZERO)
                .transactionType(TransactionType.ENVELOPE_TO_EXTERNAL)
                .status(TransactionStatus.PENDING)
                .reference(myReference)
                .description(description)
                .createdAt(LocalDateTime.now())
                .build();
        transactionLogRepository.save(txn);

        try {
            String providerRef = paymentProvider.initiateTransfer(
                    externalAccount.getBankCode(),
                    externalAccount.getAccountNumber(),
                    resolvedName,
                    amount,
                    myReference,
                    "Payment from " + user.getName()
            );
            txn.setStatus(TransactionStatus.COMPLETED);
            transactionLogRepository.save(txn);
            notificationService.sendNotification(
                    user.getId().toString(),
                    "Sent ₦" + amount + " to " + resolvedName,
                    NotificationType.EXTERNAL_TRANSFER,
                    source.getBudget().getId(),
                    sourceId,
                    "VIEW_ENVELOPE",
                    "/envelopes/" + sourceId
            );
        } catch (Exception e) {
            logger.error("External transfer failed: {}", e.getMessage());
            throw new RuntimeException("Transfer failed: " + e.getMessage());
        }
    }

    @Transactional
    public EnvelopeResponse createEnvelope(EnvelopeRequest request, String email) {
        Budget budget = budgetRepository.findById(request.getBudgetId())
                .orElseThrow(() -> new EntityNotFoundException("Budget not found with ID: " + request.getBudgetId()));

        if (!budget.getUser().getEmail().equals(email)) {
            throw new SecurityException("Unauthorized access to budget");
        }
        if (budget.getStatus() == BudgetStatus.DRAFT) {
            throw new IllegalArgumentException("Can only add envelopes to active budgets");
        }

        BigDecimal amount;
        if (request.getExactAmount() != null && request.getExactAmount().compareTo(BigDecimal.ZERO) > 0) {
            amount = request.getExactAmount();
        } else {
            amount = budget.getTotalAmount()
                    .multiply(request.getPercentage())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }

        validateEnvelopeConditions(request, amount);

        Map<String, Object> conditions = request.getConditions();
        String type = "standard";
        if (conditions != null && conditions.containsKey("type")) {
            type = (String) conditions.get("type");
            switch (type) {
                case "daily":
                    conditions.putIfAbsent("gracePeriodMinutes", 30);
                    break;
                case "weekly":
                    conditions.putIfAbsent("gracePeriodMinutes", 30);
                    break;
                case "dynamic":
                    conditions.putIfAbsent("gracePeriodMinutes", 30);
                    break;
                case "safe_lock":
                case "strict_lock":
                    conditions.putIfAbsent("gracePeriodMinutes", 1440);
                    break;
                case "emergency":
                    conditions.put("limit", amount);
                    conditions.putIfAbsent("gracePeriodMinutes", 0);
                    break;
            }
        }

        Envelope envelope = new Envelope(budget, request.getName(), amount, conditions);
        envelope.setCreatedAt(fetchCurrentDateTimeFromDatabase());
        envelope.setInitialAmount(amount);
        envelope.setTotalRemainingAmount(amount);
        envelopeRepository.save(envelope);

        recalculateTargetEnvelopeLimit(envelope, budget);

        String typez = (String) conditions.getOrDefault("type", "");
        if ("emergency".equalsIgnoreCase(typez)) {
            envelope.setRemainingAmount(amount);
        } else {
            BigDecimal startingPocket = getPeriodLimit(envelope.getConditions());
            startingPocket = startingPocket.min(envelope.getTotalRemainingAmount());
            envelope.setRemainingAmount(startingPocket);
        }

        // 5. Calculate Schedule
        envelope.setNextDisbursementAt(budgetLifeCycleManager.calculateNextDisbursementTime(envelope));
        envelope.setHasMatured(false);

        // 🛑 FIX: Initialize this so the Scheduler knows it started TODAY
        envelope.setLastDisbursedAt(fetchCurrentDateTimeFromDatabase());

        envelopeRepository.save(envelope);

        budgetLifeCycleManager.scheduleDynamicTasks(envelope);

        notificationService.sendNotification(
                budget.getUser().getId().toString(),
                String.format("Created envelope '%s' with ₦%.2f in budget '%s'.",
                        envelope.getName(), amount, budget.getName()),
                NotificationType.ENVELOPE_CREATED
        );
        return toResponse(envelope);
    }

    public EnvelopeResponse getEnvelopeById(Long envelopeId, String email) {
        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible"));
        return toResponse(envelope);
    }

    @Transactional
    public EnvelopeResponse updateEnvelopeConditions(Long envelopeId, EnvelopeRequest request, String email) {
        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible"));
        validateEnvelopeConditions(request, envelope.getAmount());
        envelope.setConditions(request.getConditions());
        envelope.setRemainingAmount(getPeriodLimit(request.getConditions()));
        envelopeRepository.save(envelope);

        notificationService.sendNotification(
                envelope.getBudget().getUser().getId().toString(),
                String.format("Updated conditions for envelope '%s' in budget '%s'.",
                        envelope.getName(), envelope.getBudget().getName()),
                NotificationType.ENVELOPE_UPDATED
        );
        return toResponse(envelope);
    }

    @Transactional
    public void claimDisbursement(Long pendingDisbursementId, String email) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        PendingDisbursement pd = pendingDisbursementRepository.findById(pendingDisbursementId)
                .orElseThrow(() -> new IllegalArgumentException("Not found"));

        if (!pd.getStatus().equals(Status.PENDING) || now.isAfter(pd.getExpiresAt())) {
            throw new IllegalStateException("Disbursement expired");
        }

        Envelope envelope = envelopeRepository.findById(pd.getEnvelopeId())
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));

        envelope.setRemainingAmount(envelope.getRemainingAmount().add(pd.getAmount()));
        envelopeRepository.save(envelope);

        pd.setStatus(Status.CLAIMED);
        pd.setProcessedAt(now);
        pendingDisbursementRepository.save(pd);

        notificationService.sendNotification(
                email,
                String.format("₦%.2f unlocked! You can now spend from your '%s' envelope.",
                        pd.getAmount(), envelope.getName()),
                NotificationType.DISBURSEMENT_SUCCESS,
                envelope.getBudget().getId(),
                envelope.getId(),
                "VIEW_ENVELOPE",
                "/envelopes/" + envelope.getId()
        );
    }

    public void deleteEnvelope(Long envelopeId, String email) {
        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible"));
        if (envelope.getBudget().getStatus() == BudgetStatus.ACTIVE) {
            throw new IllegalStateException("Cannot delete envelope from an active budget");
        }
        envelopeRepository.delete(envelope);

        notificationService.sendNotification(
                envelope.getBudget().getUser().getId().toString(),
                String.format("Deleted envelope '%s' from budget '%s'.",
                        envelope.getName(), envelope.getBudget().getName()),
                NotificationType.ENVELOPE_DELETED
        );
    }

    private EnvelopeResponse toResponse(Envelope envelope) {
        BigDecimal visibleBalance = getSpendableBalance(envelope);
        return new EnvelopeResponse(
                envelope.getId(),
                envelope.getBudget().getId(),
                envelope.getName(),
                envelope.getAmount(),
                visibleBalance,
                envelope.getAmount(),
                envelope.getTotalRemainingAmount(),
                visibleBalance,
                getPeriodLimit(envelope.getConditions()),
                budgetService.getUsedThisPeriod(envelope),
                envelope.getConditions(),
                envelope.getCreatedAt(),
                envelope.getLastDisbursedAt(),
                envelope.getNextDisbursementAt()
        );
    }

    @Transactional
    public void resetEnvelopeLimits(Envelope envelope) {
        Map<String, Object> conditions = envelope.getConditions();
        if (conditions != null && conditions.containsKey("type")) {
            String type = conditions.get("type").toString();
            if (List.of("daily", "weekly", "dynamic").contains(type)) {
                envelope.setRemainingAmount(getPeriodLimit(conditions));
                envelopeRepository.save(envelope);
            }
        }
    }

    private BigDecimal getPeriodLimit(Map<String, Object> conditions) {
        if (conditions != null && conditions.containsKey("limit") && conditions.get("limit") instanceof Number) {
            return new BigDecimal(conditions.get("limit").toString());
        }
        return BigDecimal.ZERO;
    }

    private void recalculateTargetEnvelopeLimit(Envelope envelope, Budget budget) {
        String type = (String) envelope.getConditions().get("type");
        if (type == null) return;
        if (!Set.of("daily", "weekly", "dynamic").contains(type)) {
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate budgetEnd = budget.getEndDate();
        if (budgetEnd == null || budgetEnd.isBefore(today)) {
            return;
        }

        BigDecimal totalRemaining = envelope.getTotalRemainingAmount();
        long remainingUnits = 0;
        BigDecimal newLimit;

        switch (type) {
            case "daily" -> {
                remainingUnits = ChronoUnit.DAYS.between(today, budgetEnd) + 1; // includes today

                if (remainingUnits <= 0) remainingUnits = 1;
                newLimit = totalRemaining.divide(BigDecimal.valueOf(remainingUnits), 2, RoundingMode.HALF_UP);
            }
            case "weekly" -> {
                remainingUnits = ChronoUnit.WEEKS.between(today, budgetEnd) + 1;
                if (remainingUnits <= 0) remainingUnits = 1;
                newLimit = totalRemaining.divide(BigDecimal.valueOf(remainingUnits), 2, RoundingMode.HALF_UP);
            }
            case "dynamic" -> {
                String selectedDaysStr = (String) envelope.getConditions().get("selectedDays");
                if (selectedDaysStr == null || selectedDaysStr.isBlank()) {
                    remainingUnits = ChronoUnit.DAYS.between(today, budgetEnd) + 1;
                } else {
                    List<String> selectedDays = Arrays.stream(selectedDaysStr.split(","))
                            .map(String::trim)
                            .map(String::toUpperCase)
                            .toList();
                    long daysUntilEnd = ChronoUnit.DAYS.between(today, budgetEnd);
                    long remainingOccurrences = 0;
                    for (int i = 0; i <= daysUntilEnd; i++) {
                        LocalDate checkDate = today.plusDays(i);
                        String dayName = checkDate.getDayOfWeek().name();
                        if (selectedDays.contains(dayName)) {
                            remainingOccurrences++;
                        }
                    }
                    remainingUnits = remainingOccurrences > 0 ? remainingOccurrences : 1;
                }
                newLimit = totalRemaining.divide(BigDecimal.valueOf(remainingUnits), 2, RoundingMode.HALF_UP);
            }
            default -> {
                return;
            }
        }

        envelope.getConditions().put("limit", newLimit);
        envelope.getConditions().put("limit_value", newLimit);
        envelope.getConditions().put("remaining_units", remainingUnits);
        envelopeRepository.save(envelope);
    }

    public void triggerRecalculation(Envelope envelope) {
        recalculateTargetEnvelopeLimit(envelope, envelope.getBudget());
    }

    private BigDecimal getCurrentLimitValue(Envelope e) {
        return (BigDecimal) e.getConditions().getOrDefault("limit_value", BigDecimal.ZERO);
    }

    private String getSafeName(User user) {
        if (user.getProfileData() != null) {
            Object nameObj = user.getProfileData().getOrDefault("fullName", user.getProfileData().get("name"));
            if (nameObj != null && !nameObj.toString().trim().isEmpty()) {
                return nameObj.toString();
            }
        }
        if (user.getEmail() != null) {
            String handle = user.getEmail().split("@")[0];
            return handle.substring(0, 1).toUpperCase() + handle.substring(1);
        }
        return "User";
    }

    public PendingDisbursement findPendingDisbursementByEnvelopeId(Long envelopeId) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        return pendingDisbursementRepository.findFirstByEnvelopeIdAndStatus(envelopeId, Status.PENDING)
                .filter(pd -> pd.getExpiresAt().isAfter(now))
                .orElse(null);
    }

    private void validateEnvelopeConditions(EnvelopeRequest request, BigDecimal allocatedAmount) {
        Map<String, Object> conditions = request.getConditions();
        if (conditions == null || !conditions.containsKey("type")) {
            throw new IllegalArgumentException("Envelope conditions must include 'type'");
        }
        String type = conditions.get("type").toString().toLowerCase();
        switch (type) {
            case "daily":
                if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number)) {
                    throw new IllegalArgumentException("Daily envelope must include a numeric 'limit'");
                }
                if (!conditions.containsKey("disbursementTime")) {
                    throw new IllegalArgumentException("Daily envelope must include 'disbursementTime'");
                }
                try {
                    LocalTime.parse((String) conditions.get("disbursementTime"));
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid disbursementTime format: " + conditions.get("disbursementTime"));
                }
                break;
            case "weekly":
                if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number)) {
                    throw new IllegalArgumentException("Weekly envelope must include a numeric 'limit'");
                }
                break;
            case "dynamic":
                if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number)) {
                    throw new IllegalArgumentException("Dynamic envelope must include a numeric 'limit'");
                }
                if (!conditions.containsKey("days") || ((List<?>) conditions.get("days")).isEmpty()) {
                    throw new IllegalArgumentException("Dynamic envelope must have non-empty 'days'");
                }
                if (!conditions.containsKey("disbursementTime")) {
                    throw new IllegalArgumentException("Dynamic envelope must include 'disbursementTime'");
                }
                try {
                    LocalTime.parse((String) conditions.get("disbursementTime"));
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid disbursementTime format: " + conditions.get("disbursementTime"));
                }
                @SuppressWarnings("unchecked")
                List<String> days = (List<String>) conditions.get("days");
                try {
                    days.forEach(day -> DayOfWeek.valueOf(day.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid day in dynamic condition: " + e.getMessage());
                }
                break;
            case "safe_lock":
            case "strict_lock":
                if (!conditions.containsKey("lockStartDate") || !conditions.containsKey("lockDurationDays") || !conditions.containsKey("interestRate")) {
                    throw new IllegalArgumentException("Lock envelope must include 'lockStartDate', 'lockDurationDays', and 'interestRate'");
                }
                try {
                    LocalDate.parse((String) conditions.get("lockStartDate"));
                    Integer.parseInt(conditions.get("lockDurationDays").toString());
                    new BigDecimal(conditions.get("interestRate").toString());
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid lock conditions: " + e.getMessage());
                }
                break;
            case "emergency":
//                if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number)) {
//                    throw new IllegalArgumentException("Emergency envelope must include a numeric 'limit'");
//                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported envelope type: " + type);
        }
    }

    // 👇 NEW HELPER METHOD FOR STRICT VISIBILITY
    private BigDecimal getSpendableBalance(Envelope envelope) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        Map<String, Object> conditions = envelope.getConditions();
        String type = (String) conditions.getOrDefault("type", "");

        // Graceful Start: Always show money on creation day
        boolean createdToday = envelope.getCreatedAt().toLocalDate().isEqual(now.toLocalDate());

        try {
            if ("daily".equals(type)) {
                if (conditions.containsKey("disbursementTime")) {
                    LocalTime startTime = LocalTime.parse((String) conditions.get("disbursementTime"));
                    // STRICT: If not created today AND time is early -> HIDE MONEY
                    if (!createdToday && now.toLocalTime().isBefore(startTime)) {
                        return BigDecimal.ZERO;
                    }
                }
            }
            else if ("dynamic".equals(type)) {
                // 1. Day Check
                List<String> rawDays = (List<String>) conditions.getOrDefault("days", List.of());
                if (rawDays != null && !rawDays.isEmpty()) {
                    List<String> allowedDays = rawDays.stream().map(String::toUpperCase).collect(Collectors.toList());
                    String currentDay = now.getDayOfWeek().name();

                    if (!allowedDays.contains(currentDay)) {
                        return BigDecimal.ZERO; // Wrong Day -> HIDE MONEY
                    }
                }

                // 2. Time Check
                if (conditions.containsKey("disbursementTime")) {
                    LocalTime startTime = LocalTime.parse((String) conditions.get("disbursementTime"));
                    // If not created today AND time is early -> HIDE MONEY
                    if (!createdToday && now.toLocalTime().isBefore(startTime)) {
                        return BigDecimal.ZERO;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error calculating spendable balance for envelope {}", envelope.getId(), e);
        }

        // Default: If rules pass (or it's Weekly/Emergency), show the actual pocket money
        return envelope.getRemainingAmount();
    }
}


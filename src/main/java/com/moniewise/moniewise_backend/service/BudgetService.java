package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.config.BudgetLifeCycleManager;
import com.moniewise.moniewise_backend.config.GenericNotificationEvent;
import com.moniewise.moniewise_backend.dto.request.BudgetRequest;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.BudgetResponse;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.exception.InsufficientFundsException;
import com.moniewise.moniewise_backend.repository.*;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static com.moniewise.moniewise_backend.enums.TransactionType.*;

@Service
public class BudgetService {

    private final EnvelopeRepository envelopeRepository;
    private final BudgetRepository budgetRepository;
    private final RevenueLogRepository revenueLogRepository;

    private final WalletRepository walletRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionLogRepository transactionLogRepository;
    private final NotificationService notificationService;
    private final WalletService walletService;

    private final ScheduledTaskRepository scheduledTaskRepository;
    private final EnvelopeService envelopeService;

    private final BudgetLifeCycleManager budgetLifeCycleManager;

    private final ApplicationEventPublisher eventPublisher;

    private final SavingsService savingsService;

    private final SystemConfigService systemConfig;
    private final MonnieCacheInvalidationService monnieCacheInvalidationService;

    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    public BudgetService(
            EnvelopeRepository envelopeRepository,
            BudgetRepository budgetRepository,
            RevenueLogRepository revenueLogRepository,
            WalletRepository walletRepository, UserService userService,
            TransactionLogRepository transactionLogRepository,
            NotificationService notificationService,
            WalletService walletService, ScheduledTaskRepository scheduledTaskRepository,
            @Lazy EnvelopeService envelopeService, BudgetLifeCycleManager budgetLifeCycleManager,
            ApplicationEventPublisher eventPublisher, SavingsService savingsService,
            SystemConfigService systemConfig,
            MonnieCacheInvalidationService monnieCacheInvalidationService) {
        this.envelopeRepository = envelopeRepository;
        this.budgetRepository = budgetRepository;
        this.revenueLogRepository = revenueLogRepository;
        this.walletRepository = walletRepository;
        this.userService = userService;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.walletService = walletService;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.envelopeService = envelopeService;
        this.budgetLifeCycleManager = budgetLifeCycleManager;
        this.eventPublisher = eventPublisher;
        this.savingsService = savingsService;
        this.systemConfig = systemConfig;
        this.monnieCacheInvalidationService = monnieCacheInvalidationService;
    }

    // Helper method to fetch current date/time from Postgres
    @Autowired
    private JdbcTemplate jdbcTemplate;
    private static final Logger logger = LoggerFactory.getLogger(BudgetService.class);
    private static final String LATEST_TNC_VERSION = "2.0";
    private static final String LATEST_TNC_CONTENT = "MonieWise helps you budget... (your terms here)";

    @PostConstruct
    public void init() {
        System.out.println("Revenue Wallet User ID: " + revenueWalletUserId);
    }

    private LocalDateTime fetchCurrentDateTimeFromDatabase() {
        String sql = "SELECT CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Lagos'";
        return jdbcTemplate.queryForObject(sql, LocalDateTime.class);
    }

    // In BudgetService.java, replace lines 118–170 with:
//    @Transactional
//    public BudgetResponse createBudget(BudgetRequest request, String email) {
//        User user = userService.findByEmail(email);
//        logger.debug("Starting budget creation for {}", email);
//        logger.debug("User found: {}", user.getId());
//
//        // 0. CHECK ACTIVE BUDGET LIMIT (Max 5 Concurrent)
//        List<Budget> userBudgets = budgetRepository.findByUserId(user.getId());
//        long activeBudgetCount = userBudgets.stream()
//                .filter(b -> b.getStatus() == BudgetStatus.ACTIVE)
//                .count();
//
//        if (activeBudgetCount >= 10) {
//            throw new IllegalStateException("Limit reached: You can have a maximum of 10 active budgets. Please complete or delete an existing budget to create a new one.");
//        }
//        // 👆 END INSERT 👆
//
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//
//        // Validate dates
//        if (request.getStartDate().isAfter(request.getEndDate())) {
//            throw new IllegalArgumentException("Start date must be before end date");
//        }
//
//        // 👇 ADD THIS BLOCK HERE 👇
//        BigDecimal minAmount = new BigDecimal("5000");
//        if (request.getTotalAmount().compareTo(minAmount) < 0) {
//            // Throw clearer error for UI to display
//            throw new IllegalArgumentException("Minimum budget amount is ₦5,000.00");
//        }
//        // 👆 END INSERT 👆
//
//        // 2. 🛑 MAXIMUM CHECK (₦50,000,000)
//        // Best Practice: Prevent integer overflows, UI breaks, and extreme laundering attempts.
//        BigDecimal maxAmount = new BigDecimal("50000000");
//
//        long durationDays = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate());
//        if (durationDays <= 0) durationDays = 1;
//
//        // Validate duration
//        if (durationDays > 90) {
//            notificationService.sendNotification(user.getId().toString(),
//                    "Budget creation failed: Duration cannot exceed 90 days.", NotificationType.BUDGET_CREATION);
//            throw new IllegalArgumentException("Budget duration must be between 1 and 90 days");
//        }
//
//        // Calculate fee and budget amounts
//        int feeIntervals = (int) Math.ceil((double) durationDays / 30);
//        BigDecimal fee = new BigDecimal("200").multiply(BigDecimal.valueOf(feeIntervals));
//        BigDecimal originalAmount = request.getTotalAmount();
//
//        BigDecimal actualBudgetAmount = originalAmount.subtract(fee);
//
//        // === ADD THIS NEW BLOCK (Option 3 implementation) ===
//        List<EnvelopeRequest> envelopeRequests = request.getEnvelopes();
//        Map<EnvelopeRequest, BigDecimal> finalAmounts = new LinkedHashMap<>();
//
//        BigDecimal sumOfRoundedAmounts = BigDecimal.ZERO;
//
//        long emergencyCount = request.getEnvelopes().stream()
//                .filter(e -> "emergency".equalsIgnoreCase((String) e.getConditions().getOrDefault("type", "")))
//                .count();
//
//        if (emergencyCount > 1) {
//            throw new IllegalArgumentException("Strict Rule: You can only have ONE 'Emergency' envelope per budget. Use Standard envelopes for specific savings (e.g., 'Car Repair').");
//        }
//
//        for (EnvelopeRequest env : envelopeRequests) {
//            BigDecimal percentage = env.getPercentage();
//            BigDecimal calculated = actualBudgetAmount
//                    .multiply(percentage)
//                    .divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP); // keep precision
//
//            BigDecimal rounded = calculated.setScale(2, RoundingMode.HALF_UP);
//            finalAmounts.put(env, rounded);
//            sumOfRoundedAmounts = sumOfRoundedAmounts.add(rounded);
//        }
//
//        // Calculate the rounding error (usually between -0.99 and +0.99)
//        BigDecimal roundingError = actualBudgetAmount.subtract(sumOfRoundedAmounts);
//
//        // Distribute the error to the largest envelope(s) – this makes sum EXACT
//        if (roundingError.compareTo(BigDecimal.ZERO) != 0) {
//            // Strategy: give all the difference to the envelope with highest percentage
//            EnvelopeRequest largest = envelopeRequests.stream()
//                    .max(Comparator.comparing(EnvelopeRequest::getPercentage))
//                    .orElse(envelopeRequests.get(0));
//
//            BigDecimal oldAmount = finalAmounts.get(largest);
//            BigDecimal newAmount = oldAmount.add(roundingError);
//            finalAmounts.put(largest, newAmount);
//
//            logger.info("Adjusted envelope '{}' by ₦{} due to rounding. New amount: ₦{}",
//                    largest.getName(), roundingError, newAmount);
//        }
//
//        // Validate envelope percentages
//        BigDecimal totalPercentage = request.getEnvelopes().stream()
//                .map(EnvelopeRequest::getPercentage)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//        if (totalPercentage.compareTo(new BigDecimal("50")) < 0) {
//            throw new IllegalArgumentException("Envelope percentages must sum to at least 50%");
//        }
//        if (totalPercentage.compareTo(new BigDecimal("100")) > 0) {
//            throw new IllegalArgumentException("Envelope percentages cannot exceed 100%");
//        }
//
//        // Calculate allocation sum
//        BigDecimal allocationSum = request.getEnvelopes().stream()
//                .map(envelope -> actualBudgetAmount.multiply(envelope.getPercentage())
//                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP))
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//
//
//        // Validate allocation
//        BigDecimal minimumAllocation = actualBudgetAmount.multiply(new BigDecimal("50"))
//                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
//        if (allocationSum.compareTo(minimumAllocation) < 0) {
//            throw new IllegalStateException(
//                    "Envelope allocations (₦" + allocationSum + ") must be at least 50% of budget total (₦" + minimumAllocation + ")"
//            );
//        }
//        BigDecimal tolerance = new BigDecimal("0.01");
//        if (allocationSum.compareTo(actualBudgetAmount) > 0 &&
//                allocationSum.subtract(actualBudgetAmount).abs().compareTo(tolerance) > 0) {
//            throw new IllegalStateException(
//                    "Envelope allocations (₦" + allocationSum + ") cannot exceed budget total (₦" + actualBudgetAmount + ")"
//            );
//        }
//
//        // ADD THIS LOOP: VALIDATE EACH ENVELOPE LIMIT
//        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//            BigDecimal envelopeAmount = finalAmounts.get(envelopeRequest); // ← THIS IS NOW GUARANTEED TO SUM CORRECTLY
//
//            validateEnvelopeLimit(envelopeRequest, envelopeAmount, request.getStartDate(), request.getEndDate());
//        }
//
//        // Check wallet balance for allocation sum
//        BigDecimal walletBalance = walletService.checkBalance(user.getId());
//        if (walletBalance.compareTo(allocationSum) < 0) {
//            String message = String.format(
//                    "Transaction failed: Your wallet has insufficient funds. At least ₦%.2f is required, but you have ₦%.2f.",
//                    allocationSum, walletBalance
//            );
//
//            // ❌ DO NOT PUBLISH SUCCESS EVENT HERE
//            // ✅ DO THROW THE EXCEPTION
//            throw new IllegalArgumentException(message);
//        }
//        // Charge the fee
//        deductBudgetCreationFee(user.getId());
//
//        // Create and save budget entity
//        Budget budget = new Budget();
//        budget.setUser(user);
//        budget.setName(request.getName());
//        budget.setOriginalAmount(originalAmount);
//        budget.setFeeAmount(fee);
//        budget.setTotalAmount(actualBudgetAmount);
//        budget.setAllocatedAmount(allocationSum);
//        budget.setStartDate(request.getStartDate());
//        budget.setEndDate(request.getEndDate());
//        budget.setDurationDays((int) durationDays);
//        budget.setStatus(request.getStatus());
//        budget.setCreatedAt(now);
//        budget.setRemainingAmount(request.getTotalAmount().subtract(budget.getFeeAmount()));
//        Budget savedBudget = budgetRepository.save(budget);
//
//        // Create envelopes using EnvelopeService
//        List<Envelope> envelopes = new ArrayList<>();
//        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//
//            BigDecimal correctAmount = finalAmounts.get(envelopeRequest);
//            envelopeRequest.setExactAmount(correctAmount);
//
//            envelopeRequest.setBudgetId(savedBudget.getId()); // Set budget ID
//
//            EnvelopeResponse envelopeResponse = envelopeService.createEnvelope(envelopeRequest, email, true);
//            Envelope envelope = envelopeRepository.findById(envelopeResponse.getId())
//                    .orElseThrow(() -> new IllegalStateException("Failed to retrieve created envelope"));
//            // 🟢 THE SAVINGS SWEEP INTERCEPTOR 🟢
//            Map<String, Object> conditions = envelope.getConditions();
//            if (conditions != null && "savings_sweep".equalsIgnoreCase((String) conditions.getOrDefault("type", ""))) {
//                try {
//                    Long targetSavingsId = Long.valueOf(conditions.get("targetSavingsGoalId").toString());
//
//                    // 1. Send the money to the Pot!
//                    savingsService.sweepEnvelopeToSavings(user.getId(), targetSavingsId, correctAmount, envelope.getName());
//
//                    // 2. Turn this Envelope into an empty "Receipt"
//                    envelope.setRemainingAmount(BigDecimal.ZERO);
//                    envelope.setTotalRemainingAmount(BigDecimal.ZERO);
//                    envelope.setHasMatured(true);
//                    envelopeRepository.save(envelope);
//                } catch (Exception e) {
//                    logger.error("Failed to sweep envelope to savings for user {}", user.getId(), e);
//                    throw new IllegalStateException("Failed to process savings sweep for envelope: " + envelope.getName());
//                }
//            }
//
//            envelopes.add(envelope);
//        }
//
//        savedBudget.clearEnvelopes();
//        savedBudget.addAllEnvelopes(envelopes);
//
//        // Deduct allocation from user wallet
//        walletService.deductBalance(user.getId(), allocationSum);
//
//        // Transfer fee to revenue wallet
////        walletService.fundWallet(revenueWalletUserId, fee,
////                String.format("₦%.2f received as budget creation fee.", fee));
//
//        Wallet revenueWallet = walletRepository.findByIsRevenueWalletTrue()
//                .orElseThrow();
//        revenueWallet.setBalance(revenueWallet.getBalance().add(fee));
//        walletRepository.save(revenueWallet);
//
//        // Refund unallocated amount
////        BigDecimal unallocatedAmount = actualBudgetAmount.subtract(allocationSum);
////        if (unallocatedAmount.compareTo(BigDecimal.ZERO) > 0) {
////            walletService.fundWallet(user.getId(), unallocatedAmount,
////                    String.format("₦%.2f refunded to wallet from unallocated budget funds.", unallocatedAmount));
////            TransactionLog refundLog = new TransactionLog();
////            refundLog.setUserId(user.getId());
////            refundLog.setBudgetId(savedBudget.getId());
////            refundLog.setAmount(unallocatedAmount);
////            refundLog.setTransactionType(BUDGET_UNALLOCATED_REFUNDED);
////            refundLog.setStatus(TransactionStatus.SUCCESS);
////            refundLog.setCreatedAt(now);
////
////            // 👇 ADD THIS LINE (Generate a unique reference)
////            refundLog.setReference("REF-" + System.currentTimeMillis() + "-" + user.getId());
////
////            transactionLogRepository.save(refundLog);
////        }
//
//        // ——————— TRANSACTION LOGS ———————
//        // 1. Budget allocation deduction
//        TransactionLog allocationLog = new TransactionLog();
//        allocationLog.setUserId(user.getId());
//        allocationLog.setBudgetId(savedBudget.getId());
//        allocationLog.setAmount(allocationSum.negate());  // Negative = money left wallet
//        allocationLog.setFee(fee);
//        allocationLog.setTransactionType(BUDGET_ALLOCATION);
//        // 👇 ADD THIS
//        allocationLog.setReference("BUD-ALL-" + savedBudget.getId() + "-" + System.currentTimeMillis());
//
//        allocationLog.setDescription("Allocated to budget envelopes");
//        allocationLog.setStatus(TransactionStatus.COMPLETED);
//        allocationLog.setCreatedAt(now);
//        transactionLogRepository.save(allocationLog);
//
//        // 2. Budget creation fee deduction
//        TransactionLog feeLog = new TransactionLog();
//        feeLog.setUserId(user.getId());
//        feeLog.setBudgetId(savedBudget.getId());
//        feeLog.setAmount(fee.negate());  // ← NEGATIVE = deduction
//        feeLog.setFee(BigDecimal.ZERO);
//        feeLog.setTransactionType(BUDGET_CREATION_FEE);
//        // 👇 ADD THIS
//        feeLog.setReference("BUD-FEE-" + savedBudget.getId() + "-" + System.currentTimeMillis());
//
//        feeLog.setDescription("Budget creation fee");
//        feeLog.setStatus(TransactionStatus.COMPLETED);
//        feeLog.setCreatedAt(now);
//        transactionLogRepository.save(feeLog);
//
//        // ——————— REVENUE LOG ———————
//        RevenueLog revenueLog = new RevenueLog();
//
//        // ✅ GOOD: Uses the actual ID from the database wallet we fetched earlier
//        revenueLog.setUserId(revenueWallet.getUser().getId());
//
////        revenueLog.setUserId(revenueWalletUserId);
//        revenueLog.setType("budget_creation_fee");
//        revenueLog.setAmount(fee);
//        revenueLog.setDescription("Budget fee for " + durationDays + " days");
//        revenueLog.setCreatedAt(now);
//        revenueLogRepository.save(revenueLog);
//
//        // ——————— NOTIFICATION ———————
////        String message = String.format(
////                "Budget '%s' created successfully! " +
////                        "₦%.2f allocated • ₦%.2f fee deducted%s",
////                savedBudget.getName(),
////                allocationSum,
////                fee,
////                unallocatedAmount.compareTo(BigDecimal.ZERO) > 0
////                        ? " • ₦" + unallocatedAmount + " refunded to wallet"
////                        : ""
////        );
////
////        notificationService.sendNotification(
////                user.getId().toString(),
////                message,
////                NotificationType.BUDGET_CREATION,
////                savedBudget.getId(),
////                null,
////                "VIEW_BUDGET",                                      // <--- The Command
////                "/budgets/" + budget.getId() + "/envelopes" // Navigation URL
////        );
//
//        // 🛑 FIXED: PUBLISH SUCCESS EVENT HERE (AT THE VERY END)
////        Map<String, Object> params = new HashMap<>();
////        params.put("budgetName", savedBudget.getName());
////        params.put("allocated", String.format("%,.2f", allocationSum));
////        params.put("fee", String.format("%,.2f", fee));
////
//////        if (unallocatedAmount.compareTo(BigDecimal.ZERO) > 0) {
//////            params.put("refunded", String.format("%,.2f", unallocatedAmount));
//////        }
////
////        // Publish the event!
////        eventPublisher.publishEvent(new GenericNotificationEvent(
////                this,
////                user.getId().toString(),
////                NotificationType.BUDGET_CREATION,
////                params,
////                savedBudget.getId(),
////                null,
////                "/budgets/" + savedBudget.getId()
////        ));
//
//        Map<String, Object> params = new HashMap<>();
//        params.put("budgetName", budget.getName());
//        params.put("allocated", budget.getTotalAmount());
//        params.put("fee", fee);
//        params.put("envelopeCount", request.getEnvelopes().size());
//
//        eventPublisher.publishEvent(new GenericNotificationEvent(
//                this, user.getId().toString(), NotificationType.BUDGET_CREATION,
//                params, budget.getId(), null, "/budgets/" + budget.getId()
//        ));
//
//        return new BudgetResponse(
//                savedBudget.getId(),
//                savedBudget.getName(),
//                savedBudget.getTotalAmount(),
//                savedBudget.getAllocatedAmount(),
//                savedBudget.getDurationDays(),
//                savedBudget.getStartDate(),
//                savedBudget.getEndDate(),
//                savedBudget.getStatus(),
//                savedBudget.getCreatedAt(),
//                user.getId(),
//                savedBudget.getLastTopupTime(),
//                savedBudget.getEnvelopes().stream()
//                        .map(e -> new EnvelopeResponse(
//                                e.getId(),
//                                savedBudget.getId(),
//                                e.getName(),
//                                e.getAmount(),
//                                e.getRemainingAmount(),
//
//                                e.getAmount(),
//                                e.getTotalRemainingAmount(),
//                                e.getRemainingAmount(),
////                                getLimitFromConditions(e),
//                                calculatePeriodLimit(e, savedBudget),
//                                getUsedThisPeriod(e),
//
//                                e.getConditions(),
//                                e.getCreatedAt(),
//                                e.getLastDisbursedAt(),
//                                e.getNextDisbursementAt()
//                        ))
//                        .collect(Collectors.toList()),
//                savedBudget.getOriginalAmount(),
//                savedBudget.getFeeAmount()
//        );
//    }


    @Transactional
    public BudgetResponse createBudget(BudgetRequest request, String email) {
        User user = userService.findByEmail(email);
        logger.debug("Starting budget creation for {}", email);

        // 0. CHECK ACTIVE BUDGET LIMIT (Max 10)
        List<Budget> userBudgets = budgetRepository.findByUserId(user.getId());
        long activeBudgetCount = userBudgets.stream()
                .filter(b -> b.getStatus() == BudgetStatus.ACTIVE)
                .count();

        if (activeBudgetCount >= 10) {
            throw new IllegalStateException("Limit reached: You can have a maximum of 10 active budgets.");
        }

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        // Validate dates
        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new IllegalArgumentException("Start date must be before end date");
        }

        // Minimum budget amount
        BigDecimal minAmount = new BigDecimal("5000");
        if (request.getTotalAmount().compareTo(minAmount) < 0) {
            throw new IllegalArgumentException("Minimum budget amount is ₦5,000.00");
        }

        // Duration — read max from system_config so it can be changed without a deploy.
        // Default 730 days (2 years) supports goal budgets and annual savings plans.
        // +1: the Flutter date picker counts the start day as day 1 (today -> tomorrow
        // = 2 days), but ChronoUnit.DAYS.between() is exclusive (1 day) - without the
        // +1 this disagreed with what the user picked and under-counted the fee.
        long durationDays = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        if (durationDays <= 0) durationDays = 1;

        int maxDurationDays = systemConfig.getInt(SystemConfigService.BUDGET_MAX_DURATION_DAYS, 730);
        if (durationDays > maxDurationDays) {
            throw new IllegalArgumentException(
                    "Budget duration cannot exceed " + maxDurationDays + " days");
        }

        // Envelope count — server-side guard matching frontend soft/hard limits
        int minEnvelopes = systemConfig.getInt(SystemConfigService.BUDGET_MIN_ENVELOPES, 1);
        int maxEnvelopes = systemConfig.getInt(SystemConfigService.BUDGET_MAX_ENVELOPES, 15);
        int envelopeCount = request.getEnvelopes() == null ? 0 : request.getEnvelopes().size();

        if (envelopeCount < minEnvelopes) {
            throw new IllegalArgumentException(
                    "A budget needs at least " + minEnvelopes + " envelopes to be meaningful.");
        }
        if (envelopeCount > maxEnvelopes) {
            throw new IllegalArgumentException(
                    "Maximum " + maxEnvelopes + " envelopes per budget. " +
                    "More than that makes budgets harder to stick to.");
        }

        // === CALCULATE FEE (read from system_config — never hardcoded) ===
        // budget.creation.fee = 0 means no fee charged. Positive value = fee per 30-day interval.
        BigDecimal baseFee = systemConfig.getBigDecimal(SystemConfigService.BUDGET_CREATION_FEE, BigDecimal.ZERO);
        BigDecimal fee;
        if (baseFee.compareTo(BigDecimal.ZERO) > 0) {
            int feeIntervals = (int) Math.ceil((double) durationDays / 30);
            fee = baseFee.multiply(BigDecimal.valueOf(feeIntervals));
        } else {
            fee = BigDecimal.ZERO;
        }
        BigDecimal originalAmount = request.getTotalAmount();   // This is what goes to envelopes

        // === ENVELOPE PROCESSING & ROUNDING (unchanged logic) ===
        List<EnvelopeRequest> envelopeRequests = request.getEnvelopes();
        Map<EnvelopeRequest, BigDecimal> finalAmounts = new LinkedHashMap<>();
        BigDecimal sumOfRoundedAmounts = BigDecimal.ZERO;

        long emergencyCount = request.getEnvelopes().stream()
                .filter(e -> "emergency".equalsIgnoreCase((String) e.getConditions().getOrDefault("type", "")))
                .count();

        if (emergencyCount > 1) {
            throw new IllegalArgumentException("You can only have ONE 'Emergency' envelope per budget.");
        }

        for (EnvelopeRequest env : envelopeRequests) {
            BigDecimal rounded;
            BigDecimal exact = env.getExactAmount();
            if (exact != null && exact.compareTo(BigDecimal.ZERO) > 0) {
                // Honor the exact amount the user allocated. Percentages are
                // derived and lossy — recomputing the amount from a rounded
                // percentage turned a 4,000 allocation into 4,002. The
                // percentage is still validated below; it's just no longer the
                // source of truth for the amount.
                rounded = exact.setScale(2, RoundingMode.HALF_UP);
            } else {
                BigDecimal calculated = originalAmount
                        .multiply(env.getPercentage())
                        .divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP);
                rounded = calculated.setScale(2, RoundingMode.HALF_UP);
            }
            finalAmounts.put(env, rounded);
            sumOfRoundedAmounts = sumOfRoundedAmounts.add(rounded);
        }

        // Fix rounding error
        BigDecimal roundingError = originalAmount.subtract(sumOfRoundedAmounts);
        if (roundingError.compareTo(BigDecimal.ZERO) != 0) {
            EnvelopeRequest largest = envelopeRequests.stream()
                    .max(Comparator.comparing(EnvelopeRequest::getPercentage))
                    .orElse(envelopeRequests.get(0));

            BigDecimal oldAmount = finalAmounts.get(largest);
            finalAmounts.put(largest, oldAmount.add(roundingError));
        }

        // Validate percentages
        BigDecimal totalPercentage = request.getEnvelopes().stream()
                .map(EnvelopeRequest::getPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalPercentage.compareTo(new BigDecimal("50")) < 0) {
            throw new IllegalArgumentException("Envelope percentages must sum to at least 50%");
        }
        if (totalPercentage.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Envelope percentages cannot exceed 100%");
        }

        BigDecimal allocationSum = finalAmounts.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // === KEY CHANGE: SINGLE BALANCE CHECK FOR BUDGET + FEE ===
        BigDecimal totalRequired = allocationSum.add(fee);
        BigDecimal walletBalance = walletService.checkBalance(user.getId());

        if (walletBalance.compareTo(totalRequired) < 0) {
            throw new InsufficientFundsException(
                    String.format("Insufficient funds to create budget. " +
                            "Required: ₦%.2f (envelopes) + ₦%.2f (creation fee) = ₦%.2f total. " +
                            "Available: ₦%.2f", allocationSum, fee, totalRequired, walletBalance)
            );
        }

        // === SAVE BUDGET & ENVELOPES FIRST (as requested) ===
        Budget budget = new Budget();
        budget.setUser(user);
        budget.setName(request.getName());
        budget.setOriginalAmount(originalAmount);
        budget.setFeeAmount(fee);
        budget.setTotalAmount(originalAmount);           // Full amount goes to budget (fee is extra)
        budget.setAllocatedAmount(allocationSum);
        budget.setStartDate(request.getStartDate());
        budget.setEndDate(request.getEndDate());
        budget.setDurationDays((int) durationDays);
        budget.setStatus(request.getStatus());
        budget.setCreatedAt(now);
        budget.setRemainingAmount(originalAmount);       // Initially full amount

        Budget savedBudget = budgetRepository.save(budget);

        // Create envelopes
        List<Envelope> envelopes = new ArrayList<>();
        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
            BigDecimal correctAmount = finalAmounts.get(envelopeRequest);
            envelopeRequest.setExactAmount(correctAmount);
            envelopeRequest.setBudgetId(savedBudget.getId());

            EnvelopeResponse envelopeResponse = envelopeService.createEnvelope(envelopeRequest, email, true);
            Envelope envelope = envelopeRepository.findById(envelopeResponse.getId())
                    .orElseThrow(() -> new IllegalStateException("Failed to retrieve created envelope"));

            // Savings sweep logic (unchanged)
            Map<String, Object> conditions = envelope.getConditions();
            if (conditions != null && "savings_sweep".equalsIgnoreCase((String) conditions.getOrDefault("type", ""))) {
                try {
                    Long targetSavingsId = Long.valueOf(conditions.get("targetSavingsGoalId").toString());
                    savingsService.sweepEnvelopeToSavings(user.getId(), targetSavingsId, correctAmount,
                            envelope.getName(), savedBudget.getId(), envelope.getId());

                    envelope.setRemainingAmount(BigDecimal.ZERO);
                    envelope.setTotalRemainingAmount(BigDecimal.ZERO);
                    envelope.setHasMatured(true);
                    envelopeRepository.save(envelope);
                } catch (Exception e) {
                    logger.error("Failed to sweep envelope to savings for user {}", user.getId(), e);
                    throw new IllegalStateException("Failed to process savings sweep");
                }
            }

            envelopes.add(envelope);
        }

        savedBudget.clearEnvelopes();
        savedBudget.addAllEnvelopes(envelopes);

        // === NOW DEDUCT FEE USING PRIVATE HELPER (skipped when fee = 0) ===
        deductBudgetCreationFee(user.getId(), fee);

        // === DEDUCT ALLOCATION FROM WALLET ===
        walletService.deductBalance(user.getId(), allocationSum);

        // === CREDIT REVENUE WALLET (only when fee > 0) ===
        Wallet revenueWallet = null;
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            revenueWallet = walletRepository.findByRevenueWalletTrue()
                    .orElseThrow(() -> new RuntimeException("Revenue wallet not found"));
            revenueWallet.setBalance(revenueWallet.getBalance().add(fee));
            walletRepository.save(revenueWallet);
        }

        // === TRANSACTION LOGS ===
        // 1. Budget allocation log
        TransactionLog allocationLog = new TransactionLog();
        allocationLog.setUserId(user.getId());
        allocationLog.setBudgetId(savedBudget.getId());
        allocationLog.setAmount(allocationSum.negate());
        allocationLog.setFee(fee);
        allocationLog.setTransactionType(BUDGET_ALLOCATION);
        allocationLog.setReference("BUD-ALL-" + savedBudget.getId() + "-" + System.currentTimeMillis());
        allocationLog.setDescription("Allocated to budget envelopes");
        allocationLog.setStatus(TransactionStatus.COMPLETED);
        allocationLog.setCreatedAt(now);
        transactionLogRepository.save(allocationLog);

        // 2. Budget creation fee log (only when a fee was actually charged)
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            TransactionLog feeLog = new TransactionLog();
            feeLog.setUserId(user.getId());
            feeLog.setBudgetId(savedBudget.getId());
            feeLog.setAmount(fee.negate());
            feeLog.setFee(BigDecimal.ZERO);
            feeLog.setTransactionType(BUDGET_CREATION_FEE);
            feeLog.setReference("BUD-FEE-" + savedBudget.getId() + "-" + System.currentTimeMillis());
            feeLog.setDescription("Budget creation fee");
            feeLog.setStatus(TransactionStatus.COMPLETED);
            feeLog.setCreatedAt(now);
            transactionLogRepository.save(feeLog);

            // === REVENUE LOG ===
            if (revenueWallet != null) {
                RevenueLog revenueLog = new RevenueLog();
                revenueLog.setUserId(revenueWallet.getUser().getId());
                revenueLog.setType("budget_creation_fee");
                revenueLog.setAmount(fee);
                revenueLog.setDescription("Budget fee for " + durationDays + " days");
                revenueLog.setCreatedAt(now);
                revenueLogRepository.save(revenueLog);
            }
        }

        // === NOTIFICATION ===
        Map<String, Object> params = new HashMap<>();
        params.put("budgetName", savedBudget.getName());
        params.put("allocated", allocationSum);
        params.put("fee", fee);
        params.put("envelopeCount", request.getEnvelopes().size());

        eventPublisher.publishEvent(new GenericNotificationEvent(
                this, user.getId().toString(), NotificationType.BUDGET_CREATION,
                params, savedBudget.getId(), null, "/budgets/" + savedBudget.getId()
        ));

        return new BudgetResponse(/* ... your existing response mapping ... */);
    }

    // Helper classes to calculate period
    public BigDecimal getLimitFromConditions(Envelope e){
        Map<String, Object> cond = e.getConditions();
        if(cond == null || !cond.containsKey("limit")) return BigDecimal.ZERO;
        Object limit = cond.get("limit");
        return limit instanceof Number ? new BigDecimal(((Number) limit).doubleValue()) : BigDecimal.ZERO;
    }

    // Helper classes to calculate how much has been used in the condition limit
    public BigDecimal getUsedThisPeriod(Envelope e){
        BigDecimal limit = getLimitFromConditions(e);
        return limit.subtract(e.getRemainingAmount()).max(BigDecimal.ZERO);
    }

    // New: Get a single Budget by ID
    public BudgetResponse getBudgetById(Long budgetId, String email) {
        User user = userService.findByEmail(email);
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to view this budget");
        }
        return mapToResponse(budget);
    }

    // New: Activate a Budget
    @Transactional
    public BudgetResponse activateBudget(Long budgetId, String email) {
        User user = userService.findByEmail(email);
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to activate this budget");
        }
        if (budget.getStatus() == BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Budget is already active");
        }
        budget.setStatus(BudgetStatus.ACTIVE);
        Budget updatedBudget = budgetRepository.save(budget);
        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
        return mapToResponse(updatedBudget);
    }

    // New: Delete a Budget
    @Transactional
    public void deleteBudget(Long budgetId, String email) {
        User user = userService.findByEmail(email);
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to delete this budget");
        }
        budgetRepository.delete(budget);
        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
    }


    // New: Fetch all Budgets for a user
    public List<BudgetResponse> getBudgets(String email) {
        User user = userService.findByEmail(email);
        return budgetRepository.findByUserId(user.getId())
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

//    public List<EnvelopeResponse> getEnvelopesByBudget(Long budgetId, String email) {
//        System.out.println("Fetching envelopes for budgetId: " + budgetId + ", email: " + email);
//        User user = userService.findByEmail(email);
//
//        Budget budget = budgetRepository.findById(budgetId)
//                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
//        System.out.println("Found budget: " + budget.getId() + ", userId: " + budget.getUser().getId());
//        if (!budget.getUser().getId().equals(user.getId())) {
//            throw new SecurityException("You do not have permission to view this budget");
//        }
//        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budgetId);
//        System.out.println("Found " + envelopes.size() + " envelopes");
//        return envelopes.stream()
//                .map(envelope -> new EnvelopeResponse(
//                        envelope.getId(),
//                        envelope.getBudget().getId(),
//                        envelope.getName(),
//                        envelope.getAmount(),
//                        // New fields
//                        envelope.getAmount(),                    // ← initialAmount
//                        envelope.getTotalRemainingAmount(),      // ← totalRemaining
//                        envelope.getRemainingAmount(),           // ← periodRemaining
////                        getPeriodLimit(envelope),                // ← periodLimit
//                        calculatePeriodLimit(envelope, budget),
//                        getUsedThisPeriod(envelope),
//
//                        envelope.getRemainingAmount(),
//                        envelope.getConditions(),
//                        envelope.getCreatedAt(),
//                        envelope.getLastDisbursedAt(),
//                        envelope.getNextDisbursementAt()
//                ))
//                .collect(Collectors.toList());
//    }

    public List<EnvelopeResponse> getEnvelopesByBudget(Long budgetId, String email) {
        User user = userService.findByEmail(email);

        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));

        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to view this budget");
        }

        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budgetId);

        return envelopes.stream()
                .map(envelope -> mapEnvelopeToResponse(envelope, budget, email))
                .collect(Collectors.toList());
    }

    // 12/04/2025 -----> New: Top-up Budget
    @Transactional
    public void topUpBudget(Long budgetId, Double amount, String email) {
        // Step 1: Validate inputs and ownership
        if (amount <= 0) {
            throw new IllegalArgumentException("Top-up amount must be positive");
        }
        User user = userService.findByEmail(email);
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to top up this budget");
        }
        if (budget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Budget must be active to top up");
        }

        // Step 2: Check last top-up time (once per day)
//        LocalDateTime now = LocalDateTime.now();
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        LocalDateTime lastTopup = budget.getLastTopupTime();
        if (lastTopup != null) {
            LocalDateTime nextAllowedTopup = lastTopup.plusDays(1);
            if (now.isBefore(nextAllowedTopup)) {
                throw new IllegalArgumentException("Top-up allowed only once per day. Next top-up available after " + nextAllowedTopup);
            }
        }

        // Step 3: Deduct from Wallet (simulated for now)
        BigDecimal topupAmount = BigDecimal.valueOf(amount);
        // TODO: Integrate with Paystack/Flutterwave to deduct 'topupAmount' from user's Wallet
        // - Verify wallet balance: gateway.checkBalance(user.getWalletId())
        // - Deduct amount: gateway.deductFromWallet(user.getWalletId(), topupAmount)
        System.out.println("Simulating deduction of ₦" + topupAmount + " from user's Wallet for Budget ID: " + budgetId);

        // Step 4: Increase total_amount
        BigDecimal newTotalAmount = budget.getTotalAmount().add(topupAmount);
        budget.setTotalAmount(newTotalAmount);

        // Step 5: Reallocate to Envelopes (static: same percentage as initial allocation)
        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budgetId);
        if (envelopes.isEmpty()) {
            throw new IllegalArgumentException("No envelopes found in Budget to reallocate top-up amount");
        }

        BigDecimal originalAllocated = budget.getAllocatedAmount();
        if (originalAllocated.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Original allocated amount is zero, cannot determine allocation percentages");
        }

        for (Envelope envelope : envelopes) {
            // Calculate percentage of original allocation for this envelope
            BigDecimal envelopePercentage = envelope.getAmount().divide(originalAllocated, 4, BigDecimal.ROUND_HALF_UP);
            // Calculate additional amount for this envelope
            BigDecimal additionalAmount = topupAmount.multiply(envelopePercentage);
            // Update envelope amounts
            envelope.setAmount(envelope.getAmount().add(additionalAmount));
            envelope.setRemainingAmount(envelope.getRemainingAmount().add(additionalAmount));
            envelopeRepository.save(envelope);
        }

        // Update allocated amount
        BigDecimal newAllocatedAmount = budget.getAllocatedAmount().add(topupAmount);
        budget.setAllocatedAmount(newAllocatedAmount);

        // Step 6: Update last_topup_time
        budget.setLastTopupTime(now);
        budgetRepository.save(budget);

        // Step 7: Log transaction (no fee, so no revenue log)
        TransactionLog transactionLog = new TransactionLog(
                user.getId(),
                budgetId,
                null, // No source envelope
                null, // No target envelope
                null, // No external account
                topupAmount,
                BigDecimal.ZERO, // No fee
                WALLET_DEDUCTION,
                "Topped up budget from user wallet"
        );
        transactionLog.setCreatedAt(now);
        transactionLogRepository.save(transactionLog);
        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
    }

    // New: Extend Budget
    @Transactional
    public void extendBudget(Long budgetId, String newName, LocalDate newEndDate, String email) {
        // Step 1: Validate inputs and ownership
        if (newEndDate == null || newEndDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("New end date must be in the future");
        }
        User user = userService.findByEmail(email);
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to extend this budget");
        }
        if (budget.getStatus() == BudgetStatus.COMPLETED) {
            throw new IllegalArgumentException("Cannot extend an ended budget. Create a new budget instead.");
        }

        // Step 2: Update Budget details
        if (newName != null && !newName.trim().isEmpty()) {
            budget.setName(newName);
        }
        budget.setEndDate(newEndDate);
        budget.setStatus(BudgetStatus.ACTIVE); // Ensure it remains active
        budgetRepository.save(budget);

        // Step 3: Roll over Envelopes (keep existing funds and conditions)
        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budgetId);
        System.out.println("Rolling over " + envelopes.size() + " envelopes for Budget ID: " + budgetId);
        // No changes needed to envelopes since we're rolling over existing funds

        // Step 4: Log transaction
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        TransactionLog transactionLog = new TransactionLog(
                user.getId(),
                budgetId,
                null, // No source envelope
                null, // No target envelope
                null, // No external account
                BigDecimal.ZERO, // No amount
                BigDecimal.ZERO, // No fee
                BUDGET_EXTENSION,
                "Extended budget end date to " + newEndDate
        );
        transactionLog.setCreatedAt(now);
        transactionLogRepository.save(transactionLog);
        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
    }

    // Placeholder for getTimeBasedGreeting
    private String getTimeBasedGreeting(String name) {
        int hour = LocalDateTime.now().getHour();
        String timeOfDay = hour < 12 ? "Morning" : hour < 17 ? "Afternoon" : "Evening";
        return String.format("Good %s, %s!", timeOfDay, name);
    }
    public Map<String, Object> getDashboard(String email) {
        User user = userService.findByEmail(email);
        String name = user.getName() != null ? user.getName() : user.getEmail().split("@")[0];
        String greeting = getTimeBasedGreeting(name);

        List<Object[]> rows = budgetRepository.findDashboardSummariesByUserId(user.getId());
        Map<String, List<Map<String, Object>>> budgetMap = new HashMap<>();
        budgetMap.put("active", new ArrayList<>());
        budgetMap.put("completed", new ArrayList<>());

        LocalDate today = LocalDate.now();
        for (Object[] row : rows) {
            Long budgetId = ((Number) row[0]).longValue();
            String budgetName = (String) row[1];
            BudgetStatus status = (BudgetStatus) row[2];
            LocalDate startDate = (LocalDate) row[3];
            LocalDate endDate = (LocalDate) row[4];
            BigDecimal allocatedAmount = (BigDecimal) row[5];
            int envelopeCount = ((Number) row[6]).intValue();
            BigDecimal remainingAmount = row[7] != null
                    ? (BigDecimal) row[7]
                    : allocatedAmount; // default: nothing spent yet
            BigDecimal spentAmount = allocatedAmount.subtract(remainingAmount)
                    .max(BigDecimal.ZERO);

            Map<String, Object> budgetSummary = new HashMap<>();
            budgetSummary.put("id", budgetId);
            budgetSummary.put("name", budgetName);
            budgetSummary.put("status", status.name());
            budgetSummary.put("startDate", startDate.toString());
            budgetSummary.put("endDate", endDate != null ? endDate.toString() : startDate.toString());
            budgetSummary.put("allocatedAmount", allocatedAmount);
            budgetSummary.put("envelopeCount", envelopeCount);
            budgetSummary.put("remainingAmount", remainingAmount);
            budgetSummary.put("spentAmount", spentAmount);

            if (status == BudgetStatus.ACTIVE && endDate != null && !endDate.isBefore(today)) {
                // Quick-spend rail: attach the budget's envelopes sorted spendable-first so the
                // dashboard can render tap-to-spend links without a second round-trip. Cap a little
                // above the 3 the UI shows for headroom. Active budgets only — keeps the payload
                // small and leaves the (additive) completed summaries untouched.
                budgetSummary.put("spendableEnvelopes",
                        envelopeService.getDashboardEnvelopes(budgetId, 5));
                budgetMap.get("active").add(budgetSummary);
            } else {
                budgetMap.get("completed").add(budgetSummary);
            }
        }

        return Map.of(
                "greeting", greeting,
                "budgets", budgetMap
        );
    }

//    private BudgetResponse mapToResponse(Budget budget) {
//        BudgetResponse response = new BudgetResponse(
//                budget.getId(),
//                budget.getName(),
//                budget.getTotalAmount(),
//                budget.getAllocatedAmount(),
//                budget.getDurationDays(),
//                budget.getStartDate(),
//                budget.getEndDate(),
//                budget.getStatus(),
//                budget.getCreatedAt(),
//                budget.getUser().getId(),
//                budget.getLastTopupTime()
//        );
//
//        // Map Envelopes to EnvelopeResponse
//        List<EnvelopeResponse> envelopeResponses = (budget.getEnvelopes() != null)
//                ? budget.getEnvelopes().stream()
//                .map(envelope -> new EnvelopeResponse(
//                        envelope.getId(),
//                        budget.getId(),
////                        envelope.getBudget().getId(),
//                        envelope.getName(),
//                        envelope.getAmount(),
//                        envelope.getRemainingAmount(),
//
//                        // New fields
//                        envelope.getAmount(),                    // ← initialAmount
//                        envelope.getTotalRemainingAmount(),      // ← totalRemaining
//                        envelope.getRemainingAmount(),           // ← periodRemaining
////                        getPeriodLimit(envelope),                // ← periodLimit
//                        calculatePeriodLimit(envelope, budget),
//                        getUsedThisPeriod(envelope),             // ← usedThisPeriod
//
//                        envelope.getConditions(),
//                        envelope.getCreatedAt(),
//                        envelope.getLastDisbursedAt(),
//                        envelope.getNextDisbursementAt()
//                ))
//                .collect(Collectors.toList()): new ArrayList<>();
//        response.setEnvelopes(envelopeResponses);
//        return response;
//    }

    private BudgetResponse mapToResponse(Budget budget) {
        BudgetResponse response = new BudgetResponse(
                budget.getId(),
                budget.getName(),
                budget.getTotalAmount(),
                budget.getAllocatedAmount(),
                budget.getDurationDays(),
                budget.getStartDate(),
                budget.getEndDate(),
                budget.getStatus(),
                budget.getCreatedAt(),
                budget.getUser().getId(),
                budget.getLastTopupTime()
        );

        String email = budget.getUser().getEmail();

        List<EnvelopeResponse> envelopeResponses = budget.getEnvelopes() != null
                ? budget.getEnvelopes()
                .stream()
                .map(envelope -> mapEnvelopeToResponse(envelope, budget, email))
                .collect(Collectors.toList())
                : new ArrayList<>();

        response.setEnvelopes(envelopeResponses);
        response.setOriginalAmount(budget.getOriginalAmount());
        response.setFeeAmount(budget.getFeeAmount());

        return response;
    }

    @Transactional
    public EnvelopeResponse lockEnvelope(
            Long envelopeId,
            String lockType,
            Integer durationDays,
            BigDecimal interestRate,
            String email
    ) {
        User user = userService.findByEmail(email);
        Envelope envelope = envelopeRepository.findById(envelopeId)
                .orElseThrow(() -> new IllegalArgumentException("Envelope not found"));

        if (!envelope.getBudget().getUser().getId().equals(user.getId())) {
            throw new SecurityException("Unauthorized");
        }

        Map<String, Object> conditions = envelope.getConditions();
        if (conditions.containsKey("locked")) {
            throw new IllegalArgumentException("Envelope already locked");
        }

        conditions.put("type", lockType.toLowerCase());
        conditions.put("lockDurationDays", durationDays);
        conditions.put("interestRate", interestRate);
        conditions.put("lockStartDate", LocalDate.now().toString());

        // For StrictLock, deduct fee
        if ("STRICT_LOCK".equals(lockType)) {
            BigDecimal fee = envelope.getRemainingAmount()
                    .multiply(new BigDecimal("0.02")); // 2% fee
            walletService.deductBalance(user.getId(), fee);
        }

        envelope.setConditions(conditions);
        envelopeRepository.save(envelope);
        Budget budget = budgetRepository.findById(envelope.getBudget().getId())
                .orElseThrow(() -> new IllegalStateException("Budget not found for envelope " + envelope.getId()));

        return new EnvelopeResponse(
                envelope.getId(),
                budget.getId(), // ✅ Now works
                envelope.getName(),
                envelope.getRemainingAmount(),
                envelope.getRemainingAmount(),
                // New fields
                envelope.getAmount(),                    // ← initialAmount
                envelope.getTotalRemainingAmount(),      // ← totalRemaining
                envelope.getRemainingAmount(),           // ← periodRemaining
                getPeriodLimit(envelope),                // ← periodLimit
                getUsedThisPeriod(envelope),             // ← usedThisPeriod

                envelope.getHeldAmount() != null ? envelope.getHeldAmount() : BigDecimal.ZERO,

                envelope.getConditions(),
                envelope.getCreatedAt(),
                envelope.getLastDisbursedAt(),
                envelope.getNextDisbursementAt()
        );
    }

    private long countActiveDays(LocalDate start, LocalDate end, List<String> days) {
        return start.datesUntil(end.plusDays(1))
                .filter(d -> days.contains(d.getDayOfWeek().name()))
                .count();
    }

    private BigDecimal getPeriodLimit(Envelope envelope) {
        Map<String, Object> conditions = envelope.getConditions();
        if (conditions == null || !conditions.containsKey("limit")) {
            return BigDecimal.ZERO;
        }
        Object limit = conditions.get("limit");
        return limit instanceof Number ? new BigDecimal(((Number) limit).doubleValue()) : BigDecimal.ZERO;
    }

    // CALCULATE THE PERIODLIMIT
    private BigDecimal calculatePeriodLimit(Envelope envelope, Budget budget) {
        Map<String, Object> cond = envelope.getConditions();
        if (cond == null || !cond.containsKey("type")) return envelope.getAmount();

        String type = ((String) cond.get("type")).toLowerCase();
        BigDecimal amount = envelope.getAmount();
        LocalDate start = budget.getStartDate();
        LocalDate end = budget.getEndDate();

        return switch (type) {
            case "daily" -> amount.divide(BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end) + 1), 2, RoundingMode.HALF_UP);
            case "weekly" -> amount.divide(BigDecimal.valueOf((ChronoUnit.DAYS.between(start, end) + 6) / 7), 2, RoundingMode.HALF_UP);
            case "dynamic" -> {
                List<String> days = (List<String>) cond.get("days");
                long active = countActiveDays(start, end, days);
                yield active > 0 ? amount.divide(BigDecimal.valueOf(active), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            }
            default -> BigDecimal.ZERO;
        };
    }

    private void validateEnvelopeLimit(EnvelopeRequest req, BigDecimal envelopeAmount, LocalDate start, LocalDate end) {
        Map<String, Object> cond = req.getConditions();
        if (cond == null || !cond.containsKey("limit")) return;

        String type = ((String) cond.get("type")).toLowerCase();
        if (List.of("emergency", "strict_lock", "safe_lock", "savings_sweep").contains(type)) return;

        BigDecimal userLimit = new BigDecimal(cond.get("limit").toString());
        if (userLimit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    String.format("'%s' envelope: Limit must be positive. You set ₦%,.2f.", req.getName(), userLimit)
            );
        }

        long activePeriods = switch (type) {
            case "daily" -> ChronoUnit.DAYS.between(start, end) + 1;
            case "weekly" -> (ChronoUnit.DAYS.between(start, end) + 6) / 7;
            case "dynamic" -> {
                List<String> days = (List<String>) cond.get("days");
                if (days == null || days.isEmpty()) {
                    throw new IllegalArgumentException(
                            String.format("'%s' envelope: Dynamic type requires 'days' array.", req.getName())
                    );
                }
                yield countActiveDays(start, end, days);
            }
            default -> 1;
        };

        BigDecimal maxAllowed = envelopeAmount;
        BigDecimal maxFromLimit = userLimit.multiply(BigDecimal.valueOf(activePeriods));
        BigDecimal maxSafeLimit = maxAllowed.divide(BigDecimal.valueOf(activePeriods), 2, RoundingMode.HALF_UP);
        BigDecimal minNeededAllocation = maxFromLimit;

        if (maxFromLimit.compareTo(maxAllowed) > 0) {
            String message = String.format(
                    "'%s' envelope: Your limit of ₦%,.2f is too high.\n" +
                            "• With %d active %s, total allowed = ₦%,.2f\n" +
                            "• But you only allocated ₦%,.2f\n\n" +
                            "Fix it by:\n" +
                            "1. Reduce limit to ≤ ₦%,.2f per %s, or\n" +
                            "2. Increase envelope allocation to ≥ ₦%,.2f",
                    req.getName(),
                    userLimit,
                    activePeriods, activePeriods == 1 ? "period" : "periods",
                    maxFromLimit,
                    maxAllowed,
                    maxSafeLimit, type.equals("dynamic") ? "disbursement" : type,
                    minNeededAllocation
            );
            throw new IllegalArgumentException(message);
        }
    }

//    @Transactional
//    public void deductBudgetCreationFee(Long userId) {
//        BigDecimal fee = new BigDecimal("200.00");
//
//        // 1. Deduct from user's wallet
//        Wallet userWallet = walletRepository.findByUserId(userId)
//                .orElseThrow(() -> new IllegalArgumentException("User wallet not found"));
//
//        if (userWallet.getBalance().compareTo(fee) < 0) {
//            throw new InsufficientFundsException("Add ₦200+ to your wallet to create a budget.");
//        }
//
//        userWallet.setBalance(userWallet.getBalance().subtract(fee));
//        walletRepository.save(userWallet);
//
//        // 2. Credit platform revenue wallet
//        Wallet revenueWallet = walletRepository.findByIsRevenueWalletTrue()
//                .orElseThrow(() -> new RuntimeException("Revenue wallet not configured"));
//
//        revenueWallet.setBalance(revenueWallet.getBalance().add(fee));
//        walletRepository.save(revenueWallet);
//
//        // 3. Log revenue
//        RevenueLog revenueLog = new RevenueLog(
//                userId,
//                "budget_creation_fee",
//                fee,
//                "Budget creation fee deducted"
//        );
//        revenueLog.setCreatedAt(LocalDateTime.now());
//        revenueLogRepository.save(revenueLog);
//
//        // ✅ Event for Fee Deduction
//        Map<String, Object> feeParams = Map.of(
//                "amount", String.format("%,.2f", fee),
//                "reason", "Budget Creation Fee"
//        );
//
//        eventPublisher.publishEvent(new GenericNotificationEvent(
//                this,
//                userId.toString(),
//                NotificationType.BUDGET_CREATION_FEE,
//                feeParams,
//                null, null, null
//        ));
//
//        logger.info("₦200 budget creation fee collected from user {} → platform revenue", userId);
//    }

    private void deductBudgetCreationFee(Long userId, BigDecimal feeAmount) {
        if (feeAmount == null || feeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            // Fee is zero (disabled via system_config) — nothing to deduct
            logger.debug("[Budget] Budget creation fee is 0 — skipping deduction for user {}", userId);
            return;
        }

        walletService.deductBalance(userId, feeAmount);
        logger.info("[Budget] Budget creation fee of ₦{} deducted from user {}", feeAmount, userId);
    }

    private EnvelopeResponse mapEnvelopeToResponse(Envelope envelope, Budget budget, String email) {
        envelopeService.getRemainingLimit(envelope.getId(), email);

        Envelope freshEnvelope = envelopeRepository.findById(envelope.getId())
                .orElseThrow(() -> new IllegalStateException("Envelope not found after refresh"));

        BigDecimal periodLimit = getLimitFromConditions(freshEnvelope);
        BigDecimal periodRemaining = freshEnvelope.getRemainingAmount() != null
                ? freshEnvelope.getRemainingAmount()
                : BigDecimal.ZERO;

        BigDecimal usedThisPeriod = periodLimit
                .subtract(periodRemaining)
                .max(BigDecimal.ZERO);

        BigDecimal heldAmt = freshEnvelope.getHeldAmount() != null
                ? freshEnvelope.getHeldAmount()
                : BigDecimal.ZERO;

        return new EnvelopeResponse(
                freshEnvelope.getId(),
                budget.getId(),
                freshEnvelope.getName(),

                freshEnvelope.getAmount(),
                periodRemaining,

                freshEnvelope.getInitialAmount(),
                freshEnvelope.getTotalRemainingAmount(),
                periodRemaining,
                periodLimit,
                usedThisPeriod,

                heldAmt,

                freshEnvelope.getConditions(),
                freshEnvelope.getCreatedAt(),
                freshEnvelope.getLastDisbursedAt(),
                freshEnvelope.getNextDisbursementAt()
        );
    }

}



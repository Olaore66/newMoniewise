package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.controller.BudgetController;
import com.moniewise.moniewise_backend.dto.request.BudgetRequest;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.request.SpendEnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.BudgetResponse;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.RevenueLogRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BudgetService {

    private final EnvelopeRepository envelopeRepository;
    private final BudgetRepository budgetRepository;
    private final RevenueLogRepository revenueLogRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionLogRepository transactionLogRepository;
    private final NotificationService notificationService;
    private final WalletService walletService;

//    public BudgetService(WalletService walletService, EnvelopeRepository envelopeRepository, BudgetRepository budgetRepository, RevenueLogRepository revenueLogRepository, UserService userService, TransactionLogRepository transactionLogRepository, NotificationService notificationService, WalletService walletService1) {
//        this.envelopeRepository = envelopeRepository;
//        this.budgetRepository = budgetRepository;
//        this.revenueLogRepository = revenueLogRepository;
//        this.userService = userService;
//        this.transactionLogRepository = transactionLogRepository;
//        this.notificationService = notificationService;
//        this.walletService = walletService;
//    }
//

    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    public BudgetService(
            EnvelopeRepository envelopeRepository,
            BudgetRepository budgetRepository,
            RevenueLogRepository revenueLogRepository,
            UserService userService,
            TransactionLogRepository transactionLogRepository,
            NotificationService notificationService,
            WalletService walletService) {
        this.envelopeRepository = envelopeRepository;
        this.budgetRepository = budgetRepository;
        this.revenueLogRepository = revenueLogRepository;
        this.userService = userService;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.walletService = walletService; // Assign walletService1 as in original
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
        String sql = "SELECT NOW()";
        return jdbcTemplate.queryForObject(sql, LocalDateTime.class);
    }

    @Transactional
    public BudgetResponse createBudget(BudgetRequest request, String email) {
        User user = userService.findByEmail(email);
        logger.debug("Starting budget creation for {}", email);
        logger.debug("User found: {}", user.getId());

        // Fetch current date/time from Postgres
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        // Validate dates
        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new IllegalArgumentException("Start date must be before end date");
        }

        long durationDays = ChronoUnit.DAYS.between(
                request.getStartDate(),
                request.getEndDate()
        );

        // Validate duration
        if (durationDays <= 0 || durationDays > 90) {
            notificationService.sendNotification(user.getId().toString(),
                    "Budget creation failed: Duration cannot exceed 90 days.");
            throw new IllegalArgumentException("Budget duration must be between 1 and 90 days");
        }

        // Calculate fee and budget amounts
        int feeIntervals = (int) Math.ceil((double) durationDays / 30);
        BigDecimal fee = new BigDecimal("100").multiply(BigDecimal.valueOf(feeIntervals)); // ₦100
        BigDecimal originalAmount = request.getTotalAmount(); // e.g., ₦250,000
        BigDecimal actualBudgetAmount = originalAmount.subtract(fee); // e.g., ₦249,900

        // Validate envelope percentages
        BigDecimal totalPercentage = request.getEnvelopes().stream()
                .map(EnvelopeRequest::getPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add); // e.g., 95%
        if (totalPercentage.compareTo(new BigDecimal("50")) < 0) {
            throw new IllegalArgumentException("Envelope percentages must sum to at least 50%");
        }
        if (totalPercentage.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Envelope percentages cannot exceed 100%");
        }

        // Calculate allocation sum
        BigDecimal allocationSum = request.getEnvelopes().stream()
                .map(envelope -> actualBudgetAmount.multiply(envelope.getPercentage())
                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add); // e.g., ₦237,405

        // Validate allocation
        BigDecimal minimumAllocation = actualBudgetAmount.multiply(new BigDecimal("50"))
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP); // e.g., ₦124,950
        if (allocationSum.compareTo(minimumAllocation) < 0) {
            throw new IllegalStateException(
                    "Envelope allocations (₦" + allocationSum + ") must be at least 50% of budget total (₦" + minimumAllocation + ")"
            );
        }
        BigDecimal tolerance = new BigDecimal("0.01");
        if (allocationSum.compareTo(actualBudgetAmount) > 0 &&
                allocationSum.subtract(actualBudgetAmount).abs().compareTo(tolerance) > 0) {
            throw new IllegalStateException(
                    "Envelope allocations (₦" + allocationSum + ") cannot exceed budget total (₦" + actualBudgetAmount + ")"
            );
        }

        // Check wallet balance for allocation sum
        BigDecimal walletBalance = walletService.checkBalance(user.getId());
        if (walletBalance.compareTo(allocationSum) < 0) {
            String message = String.format(
                    "Transaction failed: Your wallet has insufficient funds. At least ₦%.2f is required for allocation, but your current balance is ₦%.2f. Please fund your wallet.",
                    allocationSum, walletBalance
            );
            notificationService.sendNotification(user.getId().toString(), message);
            throw new IllegalArgumentException(message);
        }

        // Validate envelope conditions
        Set<String> conditionTypes = new HashSet<>();
//        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//            Map<String, Object> conditions = envelopeRequest.getConditions();
//            if (conditions == null) {
//                throw new IllegalArgumentException("Envelope conditions cannot be null");
//            }
//            String type = (String) conditions.get("type");
//            if (!Arrays.asList("daily", "weekly", "dynamic", "safe_lock", "strict_lock", "emergency").contains(type)) {
//                throw new IllegalArgumentException("Invalid condition type: " + type);
//            }
//            if ("daily".equals(type) || "weekly".equals(type) || "dynamic".equals(type)) {
//                Object limit = conditions.get("limit");
//                if (limit == null || !(limit instanceof Number) || ((Number) limit).doubleValue() <= 0) {
//                    throw new IllegalArgumentException(type + " envelope requires a positive limit");
//                }
//            }
//            if ("dynamic".equals(type)) {
//                envelopeRequest.validateDynamicConditions();
//                @SuppressWarnings("unchecked")
//                List<String> days = (List<String>) conditions.getOrDefault("days", List.of());
//                if (days.isEmpty()) {
//                    throw new IllegalArgumentException("Dynamic envelope requires at least one day");
//                }
//                // Validate days
//                for (String day : days) {
//                    try {
//                        DayOfWeek.valueOf(day.toUpperCase());
//                    } catch (IllegalArgumentException e) {
//                        throw new IllegalArgumentException("Invalid day in dynamic condition: " + day);
//                    }
//                }
//                Object disbursementTime = conditions.get("disbursementTime");
//                if (disbursementTime == null || !disbursementTime.toString().matches("\\d{2}:\\d{2}")) {
//                    throw new IllegalArgumentException("Dynamic envelope requires valid disbursementTime (HH:mm)");
//                }
//            }
//            if ("emergency".equals(type)) {
//                conditions.putIfAbsent("used", false);
//            }
//            conditionTypes.add(type);
//        }

        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
            Map<String, Object> conditions = envelopeRequest.getConditions();
            if (conditions == null) {
                throw new IllegalArgumentException("Envelope conditions cannot be null");
            }
            String type = (String) conditions.get("type");
            if (!Arrays.asList("daily", "weekly", "dynamic", "safe_lock", "strict_lock", "emergency").contains(type)) {
                throw new IllegalArgumentException("Invalid condition type: " + type);
            }
            if ("daily".equals(type) || "weekly".equals(type) || "dynamic".equals(type)) {
                Object limit = conditions.get("limit");
                if (limit == null || !(limit instanceof Number) || ((Number) limit).doubleValue() <= 0) {
                    throw new IllegalArgumentException(type + " envelope requires a positive limit");
                }
            }
            if ("dynamic".equals(type)) {
                envelopeRequest.validateDynamicConditions(); // Rely on EnvelopeRequest validation
            }
            if ("emergency".equals(type)) {
                conditions.putIfAbsent("used", false);
            }
            conditionTypes.add(type);
        }

        BudgetStatus status = BudgetStatus.valueOf(request.getStatus());
        if (conditionTypes.size() <= 1 && status == BudgetStatus.ACTIVE) {
            notificationService.sendNotification(user.getId().toString(),
                    "Budget creation failed: Add a different condition (e.g., Daily, Weekly, or Strict).");
            throw new IllegalArgumentException("Add a different condition (e.g., Daily, Weekly, or Strict)");
        }

        // Create budget entity
        Budget budget = new Budget();
        budget.setUser(user);
        budget.setName(request.getName());
        budget.setOriginalAmount(originalAmount); // ₦250,000
        budget.setFeeAmount(fee); // e.g., ₦200 for 31 days
        budget.setTotalAmount(actualBudgetAmount); // ₦249,900
        budget.setAllocatedAmount(allocationSum); // ₦237,405
        budget.setStartDate(request.getStartDate());
        budget.setEndDate(request.getEndDate());
        budget.setDurationDays((int) durationDays);
        budget.setStatus(status);
        budget.setCreatedAt(now); // Use DB date
        budget.setLastTopupTime(null);

        // Create envelopes
        List<Envelope> envelopes = new ArrayList<>();
        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
            Envelope envelope = new Envelope();
            envelope.setBudget(budget);
            envelope.setName(envelopeRequest.getName());
            BigDecimal percentage = envelopeRequest.getPercentage();
            BigDecimal amount = actualBudgetAmount
                    .multiply(percentage)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            envelope.setAmount(amount);
            envelope.setRemainingAmount(amount);
            envelope.setConditions(envelopeRequest.getConditions());
            envelope.setCreatedAt(now); // Use DB date
            envelopes.add(envelope);
        }
        budget.setEnvelopes(envelopes);

        // Deduct allocation from user wallet
        walletService.deductBalance(user.getId(), allocationSum);

        // Transfer fee to revenue wallet
        walletService.fundWallet(revenueWalletUserId, fee,
                String.format("₦%.2f received as budget creation fee.", fee));

        // Refund unallocated amount
        BigDecimal unallocatedAmount = actualBudgetAmount.subtract(allocationSum); // ₦12,495
        if (unallocatedAmount.compareTo(BigDecimal.ZERO) > 0) {
            walletService.fundWallet(user.getId(), unallocatedAmount,
                    String.format("₦%.2f refunded to wallet from unallocated budget funds.", unallocatedAmount));
            TransactionLog refundLog = new TransactionLog();
            refundLog.setUserId(user.getId());
            refundLog.setBudgetId(null);
            refundLog.setAmount(unallocatedAmount);
            refundLog.setTransactionType("budget_unallocated_refunded");
            refundLog.setCreatedAt(now); // Use DB date
            transactionLogRepository.save(refundLog);
        }

        // Save budget
        try {
            Budget savedBudget = budgetRepository.save(budget);
            logger.debug("Budget saved with ID: {}", savedBudget.getId());

            // Log transactions
            TransactionLog budgetLog = new TransactionLog();
            budgetLog.setUserId(user.getId());
            budgetLog.setBudgetId(savedBudget.getId());
            budgetLog.setAmount(allocationSum);
            budgetLog.setTransactionType("budget_allocation");
            budgetLog.setCreatedAt(now); // Use DB date
            transactionLogRepository.save(budgetLog);

            TransactionLog feeLog = new TransactionLog();
            feeLog.setUserId(user.getId());
            feeLog.setBudgetId(savedBudget.getId());
            feeLog.setAmount(fee);
            feeLog.setTransactionType("budget_creation_fee");
            feeLog.setCreatedAt(now); // Use DB date
            transactionLogRepository.save(feeLog);

            RevenueLog revenueLog = new RevenueLog();
            revenueLog.setUserId(revenueWalletUserId);
            revenueLog.setType("budget_creation");
            revenueLog.setAmount(fee);
            revenueLog.setDescription("Budget fee for " + durationDays + " days");
            revenueLog.setCreatedAt(now); // Use DB date
            revenueLogRepository.save(revenueLog);

            // Send notification
            String message = String.format(
                    "Budget '%s' created! ₦%.2f allocated (₦%.2f fee applied, ₦%.2f refunded to wallet).",
                    savedBudget.getName(), allocationSum, fee, unallocatedAmount
            );
            notificationService.sendNotification(user.getId().toString(), message);

            // Return response
            return new BudgetResponse(
                    savedBudget.getId(),
                    savedBudget.getName(),
                    savedBudget.getTotalAmount(),
                    savedBudget.getAllocatedAmount(),
                    savedBudget.getDurationDays(),
                    savedBudget.getStartDate(),
                    savedBudget.getEndDate(),
                    savedBudget.getStatus(),
                    savedBudget.getCreatedAt(),
                    user.getId(),
                    savedBudget.getLastTopupTime(),
                    savedBudget.getEnvelopes().stream()
                            .map(e -> new EnvelopeResponse(
                                    e.getId(),
                                    savedBudget.getId(),
                                    e.getName(),
                                    e.getAmount(),
                                    e.getRemainingAmount(),
                                    e.getConditions(),
                                    e.getCreatedAt()
                            ))
                            .collect(Collectors.toList()),
                    savedBudget.getOriginalAmount(),
                    savedBudget.getFeeAmount()
            );
        } catch (Exception e) {
            logger.error("Failed to save budget", e);
            throw e;
        }
    }

//    @Transactional
//    public BudgetResponse createBudget(BudgetRequest request, String email) {
//        User user = userService.findByEmail(email);
//        logger.debug("Starting budget creation for {}", email);
//        logger.debug("User found: {}", user.getId());
//
//        // Validate dates
//        if (request.getStartDate().isAfter(request.getEndDate())) {
//            throw new IllegalArgumentException("Start date must be before end date");
//        }
//
//        long durationDays = ChronoUnit.DAYS.between(
//                request.getStartDate(),
//                request.getEndDate()
//        );
//
//        // Validate duration
//        if (durationDays <= 0 || durationDays > 90) {
//            notificationService.sendNotification(user.getId().toString(),
//                    "Budget creation failed: Duration cannot exceed 90 days.");
//            throw new IllegalArgumentException("Budget duration must be between 1 and 90 days");
//        }
//
//        // Calculate fee and budget amounts
//        int feeIntervals = (int) Math.ceil((double) durationDays / 30);
//        BigDecimal fee = new BigDecimal("100").multiply(BigDecimal.valueOf(feeIntervals)); // ₦100
//        BigDecimal originalAmount = request.getTotalAmount(); // e.g., ₦2,000,000
//        BigDecimal actualBudgetAmount = originalAmount.subtract(fee); // e.g., ₦1,999,900
//
//        // Validate envelope percentages
//        BigDecimal totalPercentage = request.getEnvelopes().stream()
//                .map(EnvelopeRequest::getPercentage)
//                .reduce(BigDecimal.ZERO, BigDecimal::add); // e.g., 80%
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
//                .reduce(BigDecimal.ZERO, BigDecimal::add); // e.g., ₦1,599,920
//
//        // Validate allocation
//        BigDecimal minimumAllocation = actualBudgetAmount.multiply(new BigDecimal("50"))
//                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP); // e.g., ₦999,950
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
//        // Check wallet balance for allocation sum
//        BigDecimal walletBalance = walletService.checkBalance(user.getId());
//        if (walletBalance.compareTo(allocationSum) < 0) {
//            String message = String.format(
//                    "Transaction failed: Your wallet has insufficient funds. At least ₦%.2f is required for allocation, but your current balance is ₦%.2f. Please fund your wallet.",
//                    allocationSum, walletBalance
//            );
//            notificationService.sendNotification(user.getId().toString(), message);
//            throw new IllegalArgumentException(message);
//        }
//
//        // Validate envelope conditions
//        Set<String> conditionTypes = new HashSet<>();
////        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
////            Map<String, Object> conditions = envelopeRequest.getConditions();
////            if (conditions == null) {
////                throw new IllegalArgumentException("Envelope conditions cannot be null");
////            }
////            String type = (String) conditions.get("type");
////            if (!Arrays.asList("daily", "weekly", "dynamic", "safe_lock", "strict_lock", "emergency").contains(type)) {
////                throw new IllegalArgumentException("Invalid condition type: " + type);
////            }
////
////            if ("daily".equals(type)) {
////                Object limit = conditions.get("limit");
////                if (limit == null || !(limit instanceof Number) || ((Number) limit).doubleValue() <= 0) {
////                    throw new IllegalArgumentException("Daily envelope requires a positive limit");
////                }
////            }
////            if ("weekly".equals(type)) {
////                Object limit = conditions.get("limit");
////                if (limit == null || !(limit instanceof Number) || ((Number) limit).doubleValue() <= 0) {
////                    throw new IllegalArgumentException("Weekly envelope requires a positive limit");
////                }
////            }
////            if ("dynamic".equals(type)) {
////                envelopeRequest.validateDynamicConditions();
////                int interval = Integer.parseInt(conditions.get("intervalDays").toString());
////                if (interval < 1 || interval > 30) {
////                    throw new IllegalArgumentException("Dynamic interval must be 1-30 days");
////                }
////                Object startDate = conditions.get("startDate");
////                if (startDate == null || !startDate.equals(request.getStartDate().toString())) {
////                    throw new IllegalArgumentException("Dynamic envelope startDate must match budget startDate");
////                }
////                Object disbursementTime = conditions.get("disbursementTime");
////                if (disbursementTime == null || !disbursementTime.toString().matches("\\d{2}:\\d{2}")) {
////                    throw new IllegalArgumentException("Dynamic envelope requires valid disbursementTime (HH:mm)");
////                }
////                Object limit = conditions.get("limit");
////                if (limit == null || !(limit instanceof Number) || ((Number) limit).doubleValue() <= 0) {
////                    throw new IllegalArgumentException("Dynamic envelope requires a positive limit");
////                }
////            }
////
////
//////            if ("dynamic".equals(type)) {
//////                envelopeRequest.validateDynamicConditions();
//////                int interval = Integer.parseInt(conditions.get("intervalDays").toString());
//////                if (interval < 1 || interval > 30) {
//////                    throw new IllegalArgumentException("Dynamic interval must be 1-30 days");
//////                }
//////                Object startDate = conditions.get("startDate");
//////                if (startDate == null || !startDate.equals(request.getStartDate().toString())) {
//////                    throw new IllegalArgumentException("Dynamic envelope startDate must match budget startDate");
//////                }
//////                Object disbursementTime = conditions.get("disbursementTime");
//////                if (disbursementTime == null || !disbursementTime.toString().matches("\\d{2}:\\d{2}")) {
//////                    throw new IllegalArgumentException("Dynamic envelope requires valid disbursementTime (HH:mm)");
//////                }
//////            }
//////            if ("emergency".equals(type)) {
//////                conditions.putIfAbsent("used", false);
//////            }
////
////
////            conditionTypes.add(type);
////        }
//
//
//        // new modification --> 06/06/2025
//        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//            Map<String, Object> conditions = envelopeRequest.getConditions();
//            if (conditions == null) {
//                throw new IllegalArgumentException("Envelope conditions cannot be null");
//            }
//            String type = (String) conditions.get("type");
//            if (!Arrays.asList("daily", "weekly", "dynamic", "safe_lock", "strict_lock", "emergency").contains(type)) {
//                throw new IllegalArgumentException("Invalid condition type: " + type);
//            }
//            if ("daily".equals(type) || "weekly".equals(type) || "dynamic".equals(type)) {
//                Object limit = conditions.get("limit");
//                if (limit == null || !(limit instanceof Number) || ((Number) limit).doubleValue() <= 0) {
//                    throw new IllegalArgumentException(type + " envelope requires a positive limit");
//                }
//            }
//            if ("dynamic".equals(type)) {
//                envelopeRequest.validateDynamicConditions();
//                int interval = Integer.parseInt(conditions.get("intervalDays").toString());
//                if (interval < 1 || interval > 30) {
//                    throw new IllegalArgumentException("Dynamic interval must be 1-30 days");
//                }
//                Object startDate = conditions.get("startDate");
//                if (startDate == null || !startDate.equals(request.getStartDate().toString())) {
//                    throw new IllegalArgumentException("Dynamic envelope startDate must match budget startDate");
//                }
//                Object disbursementTime = conditions.get("disbursementTime");
//                if (disbursementTime == null || !disbursementTime.toString().matches("\\d{2}:\\d{2}")) {
//                    throw new IllegalArgumentException("Dynamic envelope requires valid disbursementTime (HH:mm)");
//                }
//            }
//            if ("emergency".equals(type)) {
//                conditions.putIfAbsent("used", false);
//            }
//            conditionTypes.add(type);
//        }
//
//        BudgetStatus status = BudgetStatus.valueOf(request.getStatus());
//        if (conditionTypes.size() <= 1 && status == BudgetStatus.ACTIVE) {
//            notificationService.sendNotification(user.getId().toString(),
//                    "Budget creation failed: Add a different condition (e.g., Daily, Weekly, or Strict).");
//            throw new IllegalArgumentException("Add a different condition (e.g., Daily, Weekly, or Strict)");
//        }
//
//
//        // Create budget entity
//        Budget budget = new Budget();
//        budget.setUser(user);
//        budget.setName(request.getName());
//        budget.setOriginalAmount(originalAmount); // ₦2,000,000
//        budget.setFeeAmount(fee); // ₦100
//        budget.setTotalAmount(actualBudgetAmount); // ₦1,999,900
//        budget.setAllocatedAmount(allocationSum); // ₦1,599,920
//        budget.setStartDate(request.getStartDate());
//        budget.setEndDate(request.getEndDate());
//        budget.setDurationDays((int) durationDays);
//        budget.setStatus(status);
//        budget.setCreatedAt(LocalDateTime.now());
//        budget.setLastTopupTime(null);
//
//        // Create envelopes
//        List<Envelope> envelopes = new ArrayList<>();
//        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//            Envelope envelope = new Envelope();
//            envelope.setBudget(budget);
//            envelope.setName(envelopeRequest.getName());
//            BigDecimal percentage = envelopeRequest.getPercentage();
//            BigDecimal amount = actualBudgetAmount
//                    .multiply(percentage)
//                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
//            envelope.setAmount(amount);
//            envelope.setRemainingAmount(amount);
//            envelope.setConditions(envelopeRequest.getConditions());
//            envelopes.add(envelope);
//        }
//        budget.setEnvelopes(envelopes);
//
//        // Deduct allocation from user wallet
//        walletService.deductBalance(user.getId(), allocationSum);
//
//        // Transfer fee to revenue wallet
//        walletService.fundWallet(revenueWalletUserId, fee,
//                String.format("₦%.2f received as budget creation fee.", fee));
//
//        // Refund unallocated amount
//        BigDecimal unallocatedAmount = actualBudgetAmount.subtract(allocationSum); // ₦399,980
//        if (unallocatedAmount.compareTo(BigDecimal.ZERO) > 0) {
//            walletService.fundWallet(user.getId(), unallocatedAmount,
//                    String.format("₦%.2f refunded to wallet from unallocated budget funds.", unallocatedAmount));
//            TransactionLog refundLog = new TransactionLog();
//            refundLog.setUserId(user.getId());
//            refundLog.setBudgetId(null);
//            refundLog.setAmount(unallocatedAmount);
//            refundLog.setTransactionType("budget_unallocated_refunded");
//            refundLog.setCreatedAt(LocalDateTime.now());
//            transactionLogRepository.save(refundLog);
//        }
//
//        // Save budget
//        try {
//            Budget savedBudget = budgetRepository.save(budget);
//            logger.debug("Budget saved with ID: {}", savedBudget.getId());
//
//            // Log transactions
//            TransactionLog budgetLog = new TransactionLog();
//            budgetLog.setUserId(user.getId());
//            budgetLog.setBudgetId(savedBudget.getId());
//            budgetLog.setAmount(allocationSum);
//            budgetLog.setTransactionType("budget_allocation");
//            budgetLog.setCreatedAt(LocalDateTime.now());
//            transactionLogRepository.save(budgetLog);
//
//            TransactionLog feeLog = new TransactionLog();
//            feeLog.setUserId(user.getId());
//            feeLog.setBudgetId(savedBudget.getId());
//            feeLog.setAmount(fee);
//            feeLog.setTransactionType("budget_creation_fee");
//            feeLog.setCreatedAt(LocalDateTime.now());
//            transactionLogRepository.save(feeLog);
//
//            RevenueLog revenueLog = new RevenueLog();
//            revenueLog.setUserId(revenueWalletUserId);
//            revenueLog.setType("budget_creation");
//            revenueLog.setAmount(fee);
//            revenueLog.setDescription("Budget fee for " + durationDays + " days");
//            revenueLog.setCreatedAt(LocalDateTime.now());
//            revenueLogRepository.save(revenueLog);
//
//            // Send notification
//            String message = String.format(
//                    "Budget '%s' created! ₦%.2f allocated (₦%.2f fee applied, ₦%.2f refunded to wallet).",
//                    savedBudget.getName(), allocationSum, fee, unallocatedAmount
//            );
//            notificationService.sendNotification(user.getId().toString(), message);
//
//            // Return response
//            return new BudgetResponse(
//                    savedBudget.getId(),
//                    savedBudget.getName(),
//                    savedBudget.getTotalAmount(),
//                    savedBudget.getAllocatedAmount(),
//                    savedBudget.getDurationDays(),
//                    savedBudget.getStartDate(),
//                    savedBudget.getEndDate(),
//                    savedBudget.getStatus(),
//                    savedBudget.getCreatedAt(),
//                    user.getId(),
//                    savedBudget.getLastTopupTime(),
//                    savedBudget.getEnvelopes().stream()
//                            .map(e -> new EnvelopeResponse(
//                                    e.getId(),
//                                    savedBudget.getId(),
//                                    e.getName(),
//                                    e.getAmount(),
//                                    e.getRemainingAmount(),
//                                    e.getConditions(),
//                                    e.getCreatedAt()
//                            ))
//                            .collect(Collectors.toList()),
//                    savedBudget.getOriginalAmount(),
//                    savedBudget.getFeeAmount()
//            );
//        } catch (Exception e) {
//            logger.error("Failed to save budget", e);
//            throw e;
//        }
//    }


//    @Transactional
//    public BudgetResponse createBudget(BudgetRequest request, String email) {
//        User user = userService.findByEmail(email);
//
//        logger.debug("Starting budget creation for {}", email);
//        logger.debug("User found: {}", user.getId());
//
//        // Validate dates
//        if (request.getStartDate().isAfter(request.getEndDate())) {
//            throw new IllegalArgumentException("Start date must be before end date");
//        }
//
//        long durationDays = ChronoUnit.DAYS.between(
//                request.getStartDate(),
//                request.getEndDate()
//        );
//
//        // Validate duration ≤ 90 days
//        if (durationDays <= 0 || durationDays > 90) {
//            notificationService.sendNotification(user.getId().toString(),
//                    "Budget creation failed: Duration cannot exceed 90 days.");
//            throw new IllegalArgumentException("Budget duration must be between 1 and 90 days");
//        }
//
//        int feeIntervals = (int) Math.ceil((double) durationDays / 30);
//        BigDecimal fee = new BigDecimal("100").multiply(BigDecimal.valueOf(feeIntervals));
//        BigDecimal budgetAmount = request.getTotalAmount(); // e.g., ₦100,000
//        BigDecimal actualBudgetAmount = budgetAmount.subtract(fee); // e.g., ₦99,900
//
//        // Check wallet balance for minimum allocation + fee
//        BigDecimal minimumAllocation = actualBudgetAmount.multiply(new BigDecimal("50"))
//                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
//        BigDecimal minimumDeduction = minimumAllocation.add(fee);
//        BigDecimal walletBalance = walletService.checkBalance(user.getId());
//        if (walletBalance.compareTo(minimumDeduction) < 0) {
//            String message = String.format(
//                    "Transaction failed: Your wallet has insufficient funds. At least ₦%.2f is required (₦%.2f minimum budget + ₦%.2f fee), but your current balance is ₦%.2f. Please fund your wallet.",
//                    minimumDeduction, minimumAllocation, fee, walletBalance
//            );
//            notificationService.sendNotification(user.getId().toString(), message);
//            throw new IllegalArgumentException(message);
//        }
//
//        // Validate envelope percentages sum to at least 50%
//        BigDecimal totalPercentage = request.getEnvelopes().stream()
//                .map(EnvelopeRequest::getPercentage)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//        BigDecimal minimumPercentage = new BigDecimal("50");
//        if (totalPercentage.compareTo(minimumPercentage) < 0) {
//            throw new IllegalArgumentException("Envelope percentages must sum to at least 50%");
//        }
//        if (totalPercentage.compareTo(new BigDecimal("100")) > 0) {
//            throw new IllegalArgumentException("Envelope percentages cannot exceed 100%");
//        }
//
//        // Validate envelope conditions (replaced with enhanced validation)
//        Set<String> conditionTypes = new HashSet<>();
//        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//            Map<String, Object> conditions = envelopeRequest.getConditions();
//            String type = (String) conditions.get("type");
//            if (!Arrays.asList("daily", "weekly", "dynamic", "safe_lock", "strict_lock", "emergency").contains(type)) {
//                throw new IllegalArgumentException("Invalid condition type: " + type);
//            }
//            if ("daily".equals(type)) {
//                Object limit = conditions.get("limit");
//                if (limit == null || !(limit instanceof Number) || ((Number) limit).doubleValue() <= 0) {
//                    throw new IllegalArgumentException("Daily envelope requires a positive limit");
//                }
//            }
//            if ("dynamic".equals(type)) {
//                envelopeRequest.validateDynamicConditions();
//                int interval = Integer.parseInt(conditions.get("intervalDays").toString());
//                if (interval < 1 || interval > 30) {
//                    throw new IllegalArgumentException("Dynamic interval must be 1-30 days");
//                }
//                Object startDate = conditions.get("startDate");
//                if (startDate == null || !startDate.equals(request.getStartDate().toString())) {
//                    throw new IllegalArgumentException("Dynamic envelope startDate must match budget startDate");
//                }
//                Object disbursementTime = conditions.get("disbursementTime");
//                if (disbursementTime == null || !disbursementTime.toString().matches("\\d{2}:\\d{2}")) {
//                    throw new IllegalArgumentException("Dynamic envelope requires valid disbursementTime (HH:mm)");
//                }
//            }
//            if ("emergency".equals(type)) {
//                conditions.putIfAbsent("used", false);
//            }
//            conditionTypes.add(type);
//        }
//        BudgetStatus status = BudgetStatus.valueOf(request.getStatus());
//        if (conditionTypes.size() <= 1 && status == BudgetStatus.ACTIVE) {
//            notificationService.sendNotification(user.getId().toString(),
//                    "Budget creation failed: Add a different condition (e.g., Daily, Weekly, or Strict).");
//            throw new IllegalArgumentException("Add a different condition (e.g., Daily, Weekly, or Strict)");
//        }
//
//        // Create budget entity
//        Budget budget = new Budget();
//        budget.setUser(user);
//        budget.setName(request.getName());
//        budget.setStartDate(request.getStartDate());
//        budget.setEndDate(request.getEndDate());
//        budget.setDurationDays((int) durationDays);
//        budget.setStatus(status);
//        budget.setCreatedAt(LocalDateTime.now());
//        budget.setLastTopupTime(null);
//        budget.setTotalAmount(actualBudgetAmount); // Post-fee amount
//
//        // Create and validate envelopes
//        List<Envelope> envelopes = new ArrayList<>();
//        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//            Envelope envelope = new Envelope();
//            envelope.setBudget(budget);
//            envelope.setName(envelopeRequest.getName());
//            BigDecimal percentage = envelopeRequest.getPercentage();
//            BigDecimal amount = actualBudgetAmount
//                    .multiply(percentage)
//                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
//            envelope.setAmount(amount);
//            envelope.setRemainingAmount(amount);
//            envelope.setConditions(envelopeRequest.getConditions());
//            envelopes.add(envelope);
//        }
//
//        // Set allocatedAmount = envelope sum
//        BigDecimal allocationSum = envelopes.stream()
//                .map(Envelope::getAmount)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//        budget.setAllocatedAmount(allocationSum);
//
//        // Validate allocation sum is at least 50% of actualBudgetAmount
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
//        // Calculate unallocated amount and refund to wallet
//        BigDecimal unallocatedAmount = actualBudgetAmount.subtract(allocationSum);
//        if (unallocatedAmount.compareTo(BigDecimal.ZERO) > 0) {
//            walletService.fundWallet(user.getId(), unallocatedAmount,
//                    String.format("₦%.2f refunded to wallet from unallocated budget funds.", unallocatedAmount));
//            TransactionLog refundLog = new TransactionLog();
//            refundLog.setUserId(user.getId());
//            refundLog.setBudgetId(null);
//            refundLog.setAmount(unallocatedAmount);
//            refundLog.setTransactionType("budget_unallocated_refunded");
//            refundLog.setCreatedAt(LocalDateTime.now());
//            transactionLogRepository.save(refundLog);
//        }
//
//        // Save budget
//        try {
//            Budget savedBudget = budgetRepository.save(budget);
//            logger.debug("Budget saved with ID: {}", savedBudget.getId());
//
//            // Update envelopes to reference saved budget
//            envelopes.forEach(envelope -> envelope.setBudget(savedBudget));
//            budget.setEnvelopes(envelopes);
//
//            // Deduct allocated amount + fee from wallet
//            BigDecimal totalDeduction = allocationSum.add(fee);
//            walletService.deductBalance(user.getId(), totalDeduction);
//
//            // Assuming internal wallet ID is known (e.g., 1L for app's revenue wallet)
//            walletService.fundWallet(revenueWalletUserId, fee, String.format("₦%.2f received as budget creation fee.", fee));
//
//            // Log transactions
//            TransactionLog budgetLog = new TransactionLog();
//            budgetLog.setUserId(user.getId());
//            budgetLog.setBudgetId(savedBudget.getId());
//            budgetLog.setAmount(allocationSum);
//            budgetLog.setTransactionType("budget_allocation");
//            budgetLog.setCreatedAt(LocalDateTime.now());
//            transactionLogRepository.save(budgetLog);
//
//            TransactionLog feeLog = new TransactionLog();
//            feeLog.setUserId(user.getId());
//            feeLog.setBudgetId(savedBudget.getId());
//            feeLog.setAmount(fee);
//            feeLog.setTransactionType("budget_creation_fee");
//            feeLog.setCreatedAt(LocalDateTime.now());
//            transactionLogRepository.save(feeLog);
//
//            RevenueLog revenueLog = new RevenueLog();
//            revenueLog.setUserId(user.getId());
//            revenueLog.setType("budget_creation");
//            revenueLog.setAmount(fee);
//            revenueLog.setDescription("Budget fee for " + durationDays + " days");
//            revenueLog.setCreatedAt(LocalDateTime.now());
//            revenueLogRepository.save(revenueLog);
//
//            // Send notification
//            String message = String.format(
//                    "Budget '%s' created! ₦%.2f allocated (₦%.2f fee deducted, ₦%.2f refunded to wallet).",
//                    savedBudget.getName(), allocationSum, fee, unallocatedAmount
//            );
//            notificationService.sendNotification(user.getId().toString(), message);
//
//            // Return response
//            return new BudgetResponse(
//                    savedBudget.getId(),
//                    savedBudget.getName(),
//                    savedBudget.getTotalAmount(),
//                    savedBudget.getAllocatedAmount(),
//                    savedBudget.getDurationDays(),
//                    savedBudget.getStartDate(),
//                    savedBudget.getEndDate(),
//                    savedBudget.getStatus(),
//                    savedBudget.getCreatedAt(),
//                    user.getId(),
//                    savedBudget.getLastTopupTime(),
//                    savedBudget.getEnvelopes().stream()
//                            .map(e -> new EnvelopeResponse(
//                                    e.getId(),
//                                    savedBudget.getId(),
//                                    e.getName(),
//                                    e.getAmount(),
//                                    e.getRemainingAmount(),
//                                    e.getConditions(),
//                                    e.getCreatedAt()
//                            ))
//                            .collect(Collectors.toList())
//            );
//        } catch (Exception e) {
//            logger.error("Failed to save budget", e);
//            throw e;
//        }
//    }



// FAULTY - 01-06-2025
//    @Transactional
//    public BudgetResponse createBudget(BudgetRequest request, String email) {
//    User user = userService.findByEmail(email);
//
//    logger.debug("Starting budget creation for {}", email);
//    logger.debug("User found: {}", user.getId());
//
//
//        // Validate dates
//        if (request.getStartDate().isAfter(request.getEndDate())) {
//            throw new IllegalArgumentException("Start date must be before end date");
//        }
//
//        long durationDays = ChronoUnit.DAYS.between(
//                request.getStartDate(),
//                request.getEndDate()
//        );
//
//    // 2. Validate duration ≤ 90 days
//    if (durationDays <= 0 || durationDays > 90) {
//        notificationService.sendNotification(user.getId().toString(),
//                "Budget creation failed: Duration cannot exceed 90 days.");
//        throw new IllegalArgumentException("Budget duration must be between 1 and 90 days");
//    }
//
//        int feeIntervals = (int) Math.ceil((double) durationDays / 30);
//
//        BigDecimal fee = new BigDecimal("100").multiply(BigDecimal.valueOf(feeIntervals));
//
//
//        // Use original budget amount for wallet check
//        BigDecimal budgetAmount = request.getTotalAmount(); // 100,000
//        BigDecimal totalWalletDeduction = budgetAmount; // We need the full amount in wallet
//        BigDecimal actualBudgetAmount = budgetAmount.subtract(fee); // 99,900 (for envelopes)
//
//
//        // 4. Check wallet balance - verify user has the FULL budget amount
//        BigDecimal walletBalance = walletService.checkBalance(user.getId());
//        if (walletBalance.compareTo(actualBudgetAmount) < 0) {
//            String message = String.format(
//                    "Transaction failed: Your wallet has insufficient funds. ₦%.2f is required (₦%.2f budget - ₦%.2f fee), but your current balance is ₦%.2f. Please fund your wallet.",
//                    actualBudgetAmount, budgetAmount, fee, walletBalance
//            );
//            notificationService.sendNotification(user.getId().toString(), message);
//            throw new IllegalArgumentException(message);
//        }
//
//    // 5. Validate envelope percentages sum to 100%
//    BigDecimal totalPercentage = request.getEnvelopes().stream()
//            .map(EnvelopeRequest::getPercentage)
//            .reduce(BigDecimal.ZERO, BigDecimal::add);
//    if (totalPercentage.compareTo(new BigDecimal("100")) != 0) {
//        throw new IllegalArgumentException("Envelope percentages must sum to 100");
//    }
//
//    // 6. Validate envelope conditions
//    Set<String> conditionTypes = new HashSet<>();
//    for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//        Map<String, Object> conditions = envelopeRequest.getConditions();
//        String type = (String) conditions.get("type");
//        if (!Arrays.asList("daily", "weekly", "dynamic", "safe_lock", "strict_lock", "emergency").contains(type)) {
//            throw new IllegalArgumentException("Invalid condition type: " + type);
//        }
//        if ("emergency".equals(type)) {
//            conditions.putIfAbsent("used", false);
//        }
//        // Add dynamic validation
//        if ("dynamic".equals(type)) {
//            envelopeRequest.validateDynamicConditions();
//            int interval = Integer.parseInt(conditions.get("intervalDays").toString());
//            if (interval < 1 || interval > 30) {
//                throw new IllegalArgumentException("Dynamic interval must be 1-30 days");
//            }
//        }
//        conditionTypes.add(type);
//    }
//    BudgetStatus status = BudgetStatus.valueOf(request.getStatus());
//    if (conditionTypes.size() <= 1 && status == BudgetStatus.ACTIVE) {
//        notificationService.sendNotification(user.getId().toString(),
//                "Budget creation failed: Add a different condition (e.g., Daily, Weekly, or Strict).");
//        throw new IllegalArgumentException("Add a different condition (e.g., Daily, Weekly, or Strict)");
//    }
//
//
//    // 7. Create budget entity
//    Budget budget = new Budget();
//    budget.setUser(user);
//    budget.setName(request.getName());
////    budget.setTotalAmount(budgetAmount);
////    budget.setStartDate(LocalDate.now());
////    budget.setEndDate(budget.getStartDate().plusDays(durationDays));
//    budget.setStartDate(request.getStartDate());  // ✅ Use request date
//    budget.setEndDate(request.getEndDate());      // ✅ Use request date
//    budget.setDurationDays((int) durationDays);   // ✅ Set calculated duration
//    budget.setStatus(status);
//    budget.setCreatedAt(LocalDateTime.now());
//    budget.setLastTopupTime(null);
//    // Later when creating the budget
//    budget.setTotalAmount(actualBudgetAmount); // Set to 99,900
//    budget.setAllocatedAmount(actualBudgetAmount);
//
//        // 8. Create and validate envelopes
//    List<Envelope> envelopes = new ArrayList<>();
//    for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//        Envelope envelope = new Envelope();
//        envelope.setBudget(budget);
//        envelope.setName(envelopeRequest.getName());
//        BigDecimal percentage = envelopeRequest.getPercentage();
//        BigDecimal amount = budgetAmount
//                .multiply(percentage)
//                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
//        envelope.setAmount(amount);
//        envelope.setRemainingAmount(amount);
//        envelope.setConditions(envelopeRequest.getConditions());
//        envelopes.add(envelope);
//    }
//
//
//
//    // 9. Set allocatedAmount = envelope sum
//    BigDecimal allocationSum = envelopes.stream()
//            .map(Envelope::getAmount)
//            .reduce(BigDecimal.ZERO, BigDecimal::add);
//    if (allocationSum.compareTo(budgetAmount) != 0) {
//        throw new IllegalStateException(
//                "Envelope allocations (" + allocationSum + ") " +
//                        "must equal budget total (" + budgetAmount + ")"
//        );
//    }
//
//    // Allow ±₦0.01 variance for rounding
//    BigDecimal tolerance = new BigDecimal("0.01");
//    if (allocationSum.subtract(budgetAmount).abs().compareTo(tolerance) > 0) {
//        throw new IllegalStateException(
//                "Envelope allocations (" + allocationSum + ") must equal budget total (" + budgetAmount + ")"
//        );
//    }
//
//    budget.setAllocatedAmount(allocationSum);
//    budget.setEnvelopes(envelopes);
//
//    // 10. Save budget (persist with envelopes)
////    Budget savedBudget = budgetRepository.save(budget);
//    try {
//        Budget savedBudget = budgetRepository.save(budget);
//        logger.debug("Budget saved with ID: {}", savedBudget.getId());
//
//
//    // Update envelopes to reference the saved budget
//    envelopes.forEach(envelope -> envelope.setBudget(savedBudget));
//
//    //  11. Deduct FULL amount from wallet
//    walletService.deductBalance(user.getId(), budgetAmount); // Deduct 100,000
//
//
//        // 12. Log transactions
//    TransactionLog budgetLog = new TransactionLog();
//    budgetLog.setUserId(user.getId());
//    budgetLog.setBudgetId(savedBudget.getId());
//    budgetLog.setAmount(budgetAmount);
//    budgetLog.setTransactionType("budget_allocation");
//    budgetLog.setCreatedAt(LocalDateTime.now()); // ✅ Manual timestamp
//    transactionLogRepository.save(budgetLog);
//
//    TransactionLog feeLog = new TransactionLog();
//    feeLog.setUserId(user.getId());
//    feeLog.setBudgetId(savedBudget.getId());
//    feeLog.setAmount(fee);
//    feeLog.setTransactionType("budget_creation_fee");
//    feeLog.setCreatedAt(LocalDateTime.now()); // ✅ Manual timestamp
//    transactionLogRepository.save(feeLog);
//
//    RevenueLog revenueLog = new RevenueLog();
//    revenueLog.setUserId(user.getId());
//    revenueLog.setType("budget_creation");
//    revenueLog.setAmount(fee);
//    revenueLog.setDescription("Budget fee for " + durationDays + " days");
//    revenueLog.setCreatedAt(LocalDateTime.now());
//    revenueLogRepository.save(revenueLog);
//
//    // 13. Send notification
//    String message = String.format("Budget '%s' created! ₦%.2f allocated and ₦%.2f fee deducted.",
//            savedBudget.getName(), budgetAmount, fee);
//    notificationService.sendNotification(user.getId().toString(), message);
//
//    // Return response
//    return new BudgetResponse(
//            savedBudget.getId(),
//            savedBudget.getName(),
//            savedBudget.getTotalAmount(),
//            savedBudget.getAllocatedAmount(),
//            savedBudget.getDurationDays(),
//            savedBudget.getStartDate(),
//            savedBudget.getEndDate(),
//            savedBudget.getStatus(),
//            savedBudget.getCreatedAt(),
//            user.getId(),
//            savedBudget.getLastTopupTime(),
//            savedBudget.getEnvelopes().stream()
//                    .map(e -> new EnvelopeResponse(
//                            e.getId(),
//                            savedBudget.getId(),
//                            e.getName(),
//                            e.getAmount(),
//                            e.getRemainingAmount(),
//                            e.getConditions(),
//                            e.getCreatedAt()
//                    ))
//                    .collect(Collectors.toList())
//    );
//
//    } catch (Exception e) {
//        logger.error("Failed to save budget", e);
//        throw e;
//    }
//}

//    @Transactional
//    public BudgetResponse createBudget(BudgetRequest request, String email) {
//        User user = userService.findByEmail(email);
//
//        logger.debug("Starting budget creation for {}", email);
//        logger.debug("User found: {}", user.getId());
//
//        // Validate dates
//        if (request.getStartDate().isAfter(request.getEndDate())) {
//            throw new IllegalArgumentException("Start date must be before end date");
//        }
//
//        long durationDays = ChronoUnit.DAYS.between(
//                request.getStartDate(),
//                request.getEndDate()
//        );
//
//        // Validate duration ≤ 90 days
//        if (durationDays <= 0 || durationDays > 90) {
//            notificationService.sendNotification(user.getId().toString(),
//                    "Budget creation failed: Duration cannot exceed 90 days.");
//            throw new IllegalArgumentException("Budget duration must be between 1 and 90 days");
//        }
//
//        int feeIntervals = (int) Math.ceil((double) durationDays / 30);
//        BigDecimal fee = new BigDecimal("100").multiply(BigDecimal.valueOf(feeIntervals));
//        BigDecimal budgetAmount = request.getTotalAmount(); // e.g., ₦100,000
//        BigDecimal actualBudgetAmount = budgetAmount.subtract(fee); // e.g., ₦99,900
//
//        // Check wallet balance for minimum allocation + fee
//        BigDecimal minimumAllocation = actualBudgetAmount.multiply(new BigDecimal("50"))
//                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
//        BigDecimal minimumDeduction = minimumAllocation.add(fee);
//        BigDecimal walletBalance = walletService.checkBalance(user.getId());
//        if (walletBalance.compareTo(minimumDeduction) < 0) {
//            String message = String.format(
//                    "Transaction failed: Your wallet has insufficient funds. At least ₦%.2f is required (₦%.2f minimum budget + ₦%.2f fee), but your current balance is ₦%.2f. Please fund your wallet.",
//                    minimumDeduction, minimumAllocation, fee, walletBalance
//            );
//            notificationService.sendNotification(user.getId().toString(), message);
//            throw new IllegalArgumentException(message);
//        }
//
//        // Validate envelope percentages sum to at least 50%
//        BigDecimal totalPercentage = request.getEnvelopes().stream()
//                .map(EnvelopeRequest::getPercentage)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//        BigDecimal minimumPercentage = new BigDecimal("50");
//        if (totalPercentage.compareTo(minimumPercentage) < 0) {
//            throw new IllegalArgumentException("Envelope percentages must sum to at least 50%");
//        }
//        if (totalPercentage.compareTo(new BigDecimal("100")) > 0) {
//            throw new IllegalArgumentException("Envelope percentages cannot exceed 100%");
//        }
//
//        // Validate envelope conditions
//        Set<String> conditionTypes = new HashSet<>();
//        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//            Map<String, Object> conditions = envelopeRequest.getConditions();
//            String type = (String) conditions.get("type");
//            if (!Arrays.asList("daily", "weekly", "dynamic", "safe_lock", "strict_lock", "emergency").contains(type)) {
//                throw new IllegalArgumentException("Invalid condition type: " + type);
//            }
//            if ("emergency".equals(type)) {
//                conditions.putIfAbsent("used", false);
//            }
//            if ("dynamic".equals(type)) {
//                envelopeRequest.validateDynamicConditions();
//                int interval = Integer.parseInt(conditions.get("intervalDays").toString());
//                if (interval < 1 || interval > 30) {
//                    throw new IllegalArgumentException("Dynamic interval must be 1-30 days");
//                }
//            }
//            conditionTypes.add(type);
//        }
//        BudgetStatus status = BudgetStatus.valueOf(request.getStatus());
//        if (conditionTypes.size() <= 1 && status == BudgetStatus.ACTIVE) {
//            notificationService.sendNotification(user.getId().toString(),
//                    "Budget creation failed: Add a different condition (e.g., Daily, Weekly, or Strict).");
//            throw new IllegalArgumentException("Add a different condition (e.g., Daily, Weekly, or Strict)");
//        }
//
//        // Create budget entity
//        Budget budget = new Budget();
//        budget.setUser(user);
//        budget.setName(request.getName());
//        budget.setStartDate(request.getStartDate());
//        budget.setEndDate(request.getEndDate());
//        budget.setDurationDays((int) durationDays);
//        budget.setStatus(status);
//        budget.setCreatedAt(LocalDateTime.now());
//        budget.setLastTopupTime(null);
//        budget.setTotalAmount(actualBudgetAmount); // Post-fee amount
//
//        // Create and validate envelopes
//        List<Envelope> envelopes = new ArrayList<>();
//        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//            Envelope envelope = new Envelope();
//            envelope.setBudget(budget);
//            envelope.setName(envelopeRequest.getName());
//            BigDecimal percentage = envelopeRequest.getPercentage();
//            BigDecimal amount = actualBudgetAmount
//                    .multiply(percentage)
//                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
//            envelope.setAmount(amount);
//            envelope.setRemainingAmount(amount);
//            envelope.setConditions(envelopeRequest.getConditions());
//            envelopes.add(envelope);
//        }
//
//        // Set allocatedAmount = envelope sum
//        BigDecimal allocationSum = envelopes.stream()
//                .map(Envelope::getAmount)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//        budget.setAllocatedAmount(allocationSum);
//
//        // Validate allocation sum is at least 50% of actualBudgetAmount
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
//        // Calculate unallocated amount and refund to wallet using fundWallet
//        BigDecimal unallocatedAmount = actualBudgetAmount.subtract(allocationSum);
//        if (unallocatedAmount.compareTo(BigDecimal.ZERO) > 0) {
////            walletService.fundWallet(user.getId(), unallocatedAmount); // Use fundWallet for refund
//            walletService.fundWallet(user.getId(), unallocatedAmount,
//                    String.format("₦%.2f refunded to wallet from unallocated budget funds.", unallocatedAmount));
//            TransactionLog refundLog = new TransactionLog();
//            refundLog.setUserId(user.getId());
//            refundLog.setBudgetId(null);
//            refundLog.setAmount(unallocatedAmount);
//            refundLog.setTransactionType("budget_unallocated_refunded");
//            refundLog.setCreatedAt(LocalDateTime.now());
//            transactionLogRepository.save(refundLog);
//        }
//
//        // Save budget
//        try {
//            Budget savedBudget = budgetRepository.save(budget);
//            logger.debug("Budget saved with ID: {}", savedBudget.getId());
//
//            // Update envelopes to reference saved budget
//            envelopes.forEach(envelope -> envelope.setBudget(savedBudget));
//            budget.setEnvelopes(envelopes);
//
//            // Deduct allocated amount + fee from wallet
//            BigDecimal totalDeduction = allocationSum.add(fee);
//            walletService.deductBalance(user.getId(), totalDeduction);
//
//            // Log transactions
//            TransactionLog budgetLog = new TransactionLog();
//            budgetLog.setUserId(user.getId());
//            budgetLog.setBudgetId(savedBudget.getId());
//            budgetLog.setAmount(allocationSum);
//            budgetLog.setTransactionType("budget_allocation");
//            budgetLog.setCreatedAt(LocalDateTime.now());
//            transactionLogRepository.save(budgetLog);
//
//            TransactionLog feeLog = new TransactionLog();
//            feeLog.setUserId(user.getId());
//            feeLog.setBudgetId(savedBudget.getId());
//            feeLog.setAmount(fee);
//            feeLog.setTransactionType("budget_creation_fee");
//            feeLog.setCreatedAt(LocalDateTime.now());
//            transactionLogRepository.save(feeLog);
//
//            RevenueLog revenueLog = new RevenueLog();
//            revenueLog.setUserId(user.getId());
//            revenueLog.setType("budget_creation");
//            revenueLog.setAmount(fee);
//            revenueLog.setDescription("Budget fee for " + durationDays + " days");
//            revenueLog.setCreatedAt(LocalDateTime.now());
//            revenueLogRepository.save(revenueLog);
//
//            // Send notification
//            String message = String.format(
//                    "Budget '%s' created! ₦%.2f allocated (₦%.2f fee deducted, ₦%.2f refunded to wallet).",
//                    savedBudget.getName(), allocationSum, fee, unallocatedAmount
//            );
//            notificationService.sendNotification(user.getId().toString(), message);
//
//            // Return response
//            return new BudgetResponse(
//                    savedBudget.getId(),
//                    savedBudget.getName(),
//                    savedBudget.getTotalAmount(),
//                    savedBudget.getAllocatedAmount(),
//                    savedBudget.getDurationDays(),
//                    savedBudget.getStartDate(),
//                    savedBudget.getEndDate(),
//                    savedBudget.getStatus(),
//                    savedBudget.getCreatedAt(),
//                    user.getId(),
//                    savedBudget.getLastTopupTime(),
//                    savedBudget.getEnvelopes().stream()
//                            .map(e -> new EnvelopeResponse(
//                                    e.getId(),
//                                    savedBudget.getId(),
//                                    e.getName(),
//                                    e.getAmount(),
//                                    e.getRemainingAmount(),
//                                    e.getConditions(),
//                                    e.getCreatedAt()
//                            ))
//                            .collect(Collectors.toList())
//            );
//        } catch (Exception e) {
//            logger.error("Failed to save budget", e);
//            throw e;
//        }
//    }


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
    }

    private String getCurrentUserEmail() {
        return ((UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUsername();
    }

    // New: Fetch all Budgets for a user
    public List<BudgetResponse> getBudgets(String email) {
        User user = userService.findByEmail(email);
        return budgetRepository.findByUserId(user.getId())
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<EnvelopeResponse> getEnvelopesByBudget(Long budgetId, String email) {
        System.out.println("Fetching envelopes for budgetId: " + budgetId + ", email: " + email);
        User user = userService.findByEmail(email);
        System.out.println("Found user: " + user.getId());
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        System.out.println("Found budget: " + budget.getId() + ", userId: " + budget.getUser().getId());
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to view this budget");
        }
        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budgetId);
        System.out.println("Found " + envelopes.size() + " envelopes");
        return envelopes.stream()
                .map(envelope -> new EnvelopeResponse(
                        envelope.getId(),
                        envelope.getBudget().getId(),
                        envelope.getName(),
                        envelope.getAmount(),
                        envelope.getRemainingAmount(),
                        envelope.getConditions(),
                        envelope.getCreatedAt()
                ))
                .collect(Collectors.toList());
    }

    // REVENUE ACCOUNT ----- 12/04/2025 -->Simulated Moniewise revenue account ID on the payment gateway
    private static final String MONIEWISE_REVENUE_ACCOUNT = "moniewise_revenue_001";

    // Helper method to find the next valid transaction window
    private LocalDateTime findNextValidWindow(
            LocalDate searchDate,
            LocalDate budgetStartDate,
            LocalDate budgetEndDate,
            List<DayOfWeek> allowedDays,
            LocalTime disbursementTime) {
        LocalDate nextDate = searchDate;
        for (int i = 0; i <= 31; i++) { // Check up to 31 days to cover budget period
            nextDate = nextDate.plusDays(1);
            if (nextDate.isAfter(budgetEndDate)) {
                return null; // No valid windows left
            }
            if (allowedDays.contains(nextDate.getDayOfWeek()) && !nextDate.isBefore(budgetStartDate)) {
                return nextDate.atTime(disbursementTime);
            }
        }
        return null; // No valid day found (rare case)
    }


//    @Transactional
//    public void moveMoney(Long sourceId, Long targetId, Double amount, String email) {
//        // Fetch current date/time from Postgres
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//
//        // Step 1: Validate inputs and ownership
//        if (amount <= 0) {
//            throw new IllegalArgumentException("Amount must be positive");
//        }
//        User user = userService.findByEmail(email);
//        Envelope source = envelopeRepository.findById(sourceId)
//                .orElseThrow(() -> new IllegalArgumentException("Source envelope not found with ID: " + sourceId));
//        Envelope target = envelopeRepository.findById(targetId)
//                .orElseThrow(() -> new IllegalArgumentException("Target envelope not found with ID: " + targetId));
//
//        Budget sourceBudget = source.getBudget();
//        Budget targetBudget = target.getBudget();
//        System.out.println("Moving money within Budget ID: " + sourceBudget.getId() + " (" + sourceBudget.getName() + ")");
//        if (!sourceBudget.getUser().getId().equals(user.getId()) || !targetBudget.getUser().getId().equals(user.getId())) {
//            throw new SecurityException("You do not have permission to move money from/to these envelopes");
//        }
//        if (!sourceBudget.getId().equals(targetBudget.getId())) {
//            throw new IllegalArgumentException("Source and target envelopes must belong to the same budget");
//        }
//        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
//            throw new IllegalArgumentException("Budget must be active to perform transactions");
//        }
//
//        BigDecimal transferAmount = BigDecimal.valueOf(amount);
//        if (transferAmount.compareTo(source.getRemainingAmount()) > 0) {
//            throw new IllegalArgumentException("Insufficient funds in source envelope. Available: " + source.getRemainingAmount());
//        }
//
//        // Step 2: Check source envelope conditions and limits
//        Map<String, Object> sourceConditions = source.getConditions();
//        String sourceConditionType = (String) sourceConditions.get("type");
//        boolean isTransferAllowed = true;
//        BigDecimal feePercentage = BigDecimal.ZERO;
//
//        LocalDate currentDate = now.toLocalDate();
//        LocalTime currentTime = now.toLocalTime();
//
//        LocalDateTime periodStart;
//        LocalDateTime periodEnd;
//        switch (sourceConditionType) {
//            case "daily":
//                periodStart = currentDate.atStartOfDay();
//                periodEnd = periodStart.plusDays(1);
//                break;
//            case "weekly":
//                periodStart = currentDate.atStartOfDay().minusDays(currentDate.getDayOfWeek().getValue() - 1);
//                periodEnd = periodStart.plusDays(7);
//                break;
//            case "dynamic":
//                // Log conditions for debugging
//                System.out.println("Dynamic conditions: " + sourceConditions);
//
//                // Get budget start and end dates
//                LocalDate budgetStartDate = sourceBudget.getStartDate();
//                LocalDate budgetEndDate = sourceBudget.getEndDate();
//
//                // Validate budget dates
//                if (budgetStartDate == null || budgetEndDate == null) {
//                    throw new IllegalArgumentException("Budget startDate or endDate is missing");
//                }
//                if (currentDate.isBefore(budgetStartDate) || currentDate.isAfter(budgetEndDate)) {
//                    throw new IllegalArgumentException("Transfer not allowed outside budget period: " + budgetStartDate + " to " + budgetEndDate);
//                }
//
//                // Get dynamic condition fields
//                @SuppressWarnings("unchecked")
//                List<String> days = (List<String>) sourceConditions.getOrDefault("days", List.of());
//                String disbursementTimeStr = (String) sourceConditions.getOrDefault("disbursementTime", "08:00");
//                LocalTime disbursementTime = LocalTime.parse(disbursementTimeStr);
//
//                // Validate days
//                if (days.isEmpty()) {
//                    throw new IllegalArgumentException("No days specified for dynamic condition");
//                }
//                List<DayOfWeek> allowedDays = days.stream()
//                        .map(day -> DayOfWeek.valueOf(day.toUpperCase()))
//                        .collect(Collectors.toList());
//
//                // Check if today is an allowed day
//                DayOfWeek currentDayOfWeek = currentDate.getDayOfWeek();
//                if (!allowedDays.contains(currentDayOfWeek)) {
//                    // Find the next valid day
//                    LocalDate nextValidDate = currentDate;
//                    for (int i = 1; i <= 7; i++) {
//                        nextValidDate = nextValidDate.plusDays(1);
//                        if (nextValidDate.isAfter(budgetEndDate)) {
//                            throw new IllegalArgumentException("No valid transfer days left within budget period ending " + budgetEndDate);
//                        }
//                        if (allowedDays.contains(nextValidDate.getDayOfWeek())) {
//                            break;
//                        }
//                    }
//                    LocalDateTime nextValidTime = nextValidDate.atTime(disbursementTime);
//                    isTransferAllowed = false;
//                    throw new IllegalArgumentException("Transfer only allowed on " + days + " at " + disbursementTime + " (next window: " + nextValidTime + ")");
//                }
//
//                // Check time window
//                LocalDateTime validStartTime = currentDate.atTime(disbursementTime);
//                LocalDateTime validEndTime = validStartTime.plusHours(1);
//                if (now.isBefore(validStartTime) || now.isAfter(validEndTime)) {
//                    // Find the next valid day (could be today if time hasn't passed)
//                    LocalDate nextValidDate = currentDate;
//                    if (now.isAfter(validEndTime)) {
//                        for (int i = 1; i <= 7; i++) {
//                            nextValidDate = nextValidDate.plusDays(1);
//                            if (nextValidDate.isAfter(budgetEndDate)) {
//                                throw new IllegalArgumentException("No valid transfer days left within budget period ending " + budgetEndDate);
//                            }
//                            if (allowedDays.contains(nextValidDate.getDayOfWeek())) {
//                                break;
//                            }
//                        }
//                    }
//                    LocalDateTime nextValidTime = nextValidDate.atTime(disbursementTime);
//                    isTransferAllowed = false;
//                    throw new IllegalArgumentException("Transfer only allowed on " + days + " at " + disbursementTime + " (next window: " + nextValidTime + ")");
//                }
//
//                // Set period for limit check (current day)
//                periodStart = currentDate.atStartOfDay();
//                periodEnd = periodStart.plusDays(1);
//                break;
//            default:
//                periodStart = now.minusYears(1);
//                periodEnd = now.plusYears(1);
//        }
//
//        List<TransactionLog> transactions = transactionLogRepository.findBySourceEnvelopeIdAndTimeRange(sourceId, periodStart, periodEnd);
//        BigDecimal totalTransferred = transactions.stream()
//                .filter(t -> "envelope_to_envelope".equals(t.getTransactionType()) || "envelope_to_external".equals(t.getTransactionType()))
//                .map(TransactionLog::getAmount)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//
//        switch (sourceConditionType) {
//            case "daily":
//                Double dailyLimit = Double.parseDouble(sourceConditions.get("limit").toString());
//                BigDecimal remainingDaily = BigDecimal.valueOf(dailyLimit).subtract(totalTransferred);
//                if (remainingDaily.compareTo(BigDecimal.ZERO) <= 0) {
//                    throw new IllegalArgumentException("Daily limit of ₦" + dailyLimit + " exhausted until " + periodEnd);
//                }
//                if (transferAmount.compareTo(remainingDaily) > 0) {
//                    throw new IllegalArgumentException("Transfer exceeds remaining daily limit of ₦" + remainingDaily);
//                }
//                feePercentage = new BigDecimal("2");
//                break;
//
//            case "weekly":
//                Double weeklyLimit = Double.parseDouble(sourceConditions.get("limit").toString());
//                BigDecimal remainingWeekly = BigDecimal.valueOf(weeklyLimit).subtract(totalTransferred);
//                if (remainingWeekly.compareTo(BigDecimal.ZERO) <= 0) {
//                    throw new IllegalArgumentException("Weekly limit of ₦" + weeklyLimit + " exhausted until " + periodEnd);
//                }
//                if (transferAmount.compareTo(remainingWeekly) > 0) {
//                    throw new IllegalArgumentException("Transfer exceeds remaining weekly limit of ₦" + remainingWeekly);
//                }
//                feePercentage = new BigDecimal("2");
//                break;
//
//            case "safe_lock":
//                feePercentage = new BigDecimal("1");
//                break;
//
//            case "strict_lock":
//                feePercentage = new BigDecimal("5");
//                break;
//
//            case "dynamic":
//                Double dynamicLimit = Double.parseDouble(sourceConditions.get("limit").toString());
//                BigDecimal remainingDynamic = BigDecimal.valueOf(dynamicLimit).subtract(totalTransferred);
//                if (remainingDynamic.compareTo(BigDecimal.ZERO) <= 0) {
//                    throw new IllegalArgumentException("Dynamic limit of ₦" + dynamicLimit + " exhausted until " + periodEnd);
//                }
//                if (transferAmount.compareTo(remainingDynamic) > 0) {
//                    throw new IllegalArgumentException("Transfer exceeds remaining dynamic limit of ₦" + remainingDynamic);
//                }
//                feePercentage = new BigDecimal("2");
//                break;
//
//            default:
//                throw new IllegalArgumentException("Unknown condition type: " + sourceConditionType);
//        }
//
//        if (!isTransferAllowed) {
//            throw new IllegalArgumentException("Transfer not allowed due to source envelope conditions");
//        }
//
//        // Step 3: Calculate fee and update amounts
//        BigDecimal fee = transferAmount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
//        BigDecimal amountAfterFee = transferAmount.subtract(fee);
//
//        // Update remaining amounts
//        source.setRemainingAmount(source.getRemainingAmount().subtract(transferAmount));
//        target.setRemainingAmount(target.getRemainingAmount().add(amountAfterFee));
//
//        // Step 4: Save updated envelopes
//        envelopeRepository.save(source);
//        envelopeRepository.save(target);
//
//        // Step 5: Log transaction
//        TransactionLog transactionLog = new TransactionLog(
//                user.getId(),
//                sourceBudget.getId(),
//                sourceId,
//                targetId,
//                null,
//                transferAmount,
//                fee,
//                "envelope_to_envelope"
//        );
//        transactionLogRepository.save(transactionLog);
//
//        // Step 6: Log fee to revenue_logs
//        String revenueDescription = "Transfer in Budget " + sourceBudget.getId() + " from " + source.getName() + " to " + target.getName() + " (fee: " + feePercentage + "%)";
//        RevenueLog revenueLog = new RevenueLog(
//                user.getId(),
//                "envelope_transfer_fee",
//                fee,
//                revenueDescription
//        );
//        revenueLogRepository.save(revenueLog);
//
//        // Step 7: Credit Moniewise revenue account
//        creditRevenueAccount(fee, revenueDescription);
//    }



        @Transactional
        public void moveMoney(Long sourceId, Long targetId, Double amount, String email) {
            // Fetch current date/time from Postgres
            LocalDateTime now = fetchCurrentDateTimeFromDatabase();

            // Step 1: Validate inputs and ownership
            if (amount <= 0) {
                throw new IllegalArgumentException("Amount must be positive");
            }
            User user = userService.findByEmail(email);
            Envelope source = envelopeRepository.findById(sourceId)
                    .orElseThrow(() -> new IllegalArgumentException("Source envelope not found with ID: " + sourceId));
            Envelope target = envelopeRepository.findById(targetId)
                    .orElseThrow(() -> new IllegalArgumentException("Target envelope not found with ID: " + targetId));

            Budget sourceBudget = source.getBudget();
            Budget targetBudget = target.getBudget();
            logger.debug("Moving money within Budget ID: {} ({})", sourceBudget.getId(), sourceBudget.getName());

            if (!sourceBudget.getUser().getId().equals(user.getId()) || !targetBudget.getUser().getId().equals(user.getId())) {
                throw new SecurityException("You do not have permission to move money from/to these envelopes");
            }
            if (!sourceBudget.getId().equals(targetBudget.getId())) {
                throw new IllegalArgumentException("Source and target envelopes must belong to the same budget");
            }
            if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
                throw new IllegalArgumentException("Budget must be active to perform transactions");
            }

            // Check for strict_lock and emergency restrictions
            Map<String, Object> sourceConditions = source.getConditions();
            String sourceConditionType = (String) sourceConditions.get("type");
            if ("strict_lock".equals(sourceConditionType)) {
                String reason = "Strict lock envelope transfers not allowed; funds roll back after budget endDate: " + sourceBudget.getEndDate();
                logger.warn("Attempted transfer from strict_lock envelope {} by user {}: {}", sourceId, email, reason);
                TransactionLog transactionLog = new TransactionLog(
                        user.getId(), sourceBudget.getId(), sourceId, targetId, null,
                        BigDecimal.valueOf(amount), BigDecimal.ZERO, "failed_envelope_transfer", reason);
                transactionLog.setCreatedAt(now);
                transactionLogRepository.save(transactionLog);
                throw new IllegalArgumentException(reason);
            }
            if ("emergency".equals(sourceConditionType)) {
                LocalDate currentDate = now.toLocalDate();
                if (currentDate.isBefore(sourceBudget.getEndDate())) {
                    String reason = "Emergency envelope transfers not allowed before budget endDate: " + sourceBudget.getEndDate();
                    logger.warn("Attempted transfer from emergency envelope {} by user {}: {}", sourceId, email, reason);
                    TransactionLog transactionLog = new TransactionLog(
                            user.getId(), sourceBudget.getId(), sourceId, targetId, null,
                            BigDecimal.valueOf(amount), BigDecimal.ZERO, "failed_envelope_transfer", reason);
                    transactionLog.setCreatedAt(now);
                    transactionLogRepository.save(transactionLog);
                    throw new IllegalArgumentException(reason);
                }
            }

            BigDecimal transferAmount = BigDecimal.valueOf(amount);
            if (transferAmount.compareTo(source.getRemainingAmount()) > 0) {
                throw new IllegalArgumentException("Insufficient funds in source envelope. Available: ₦" + source.getRemainingAmount());
            }

            // Step 2: Check source envelope conditions and limits
//            Map<String, Object> sourceConditions = source.getConditions();
//            String sourceConditionType = (String) sourceConditions.get("type");
            boolean isTransferAllowed = true;
            BigDecimal feePercentage = BigDecimal.ZERO;

            LocalDate currentDate = now.toLocalDate();
            LocalTime currentTime = now.toLocalTime();

            LocalDateTime periodStart;
            LocalDateTime periodEnd;
            switch (sourceConditionType) {
                case "daily":
                    periodStart = currentDate.atStartOfDay();
                    periodEnd = periodStart.plusDays(1);
                    break;
                case "weekly":
                    periodStart = currentDate.atStartOfDay().minusDays(currentDate.getDayOfWeek().getValue() - 1);
                    periodEnd = periodStart.plusDays(7);
                    break;
                case "dynamic":
                    // Log conditions for debugging
                    logger.debug("Dynamic conditions for envelope {}: {}", source.getName(), sourceConditions);

                    // Get budget start and end dates
                    LocalDate budgetStartDate = sourceBudget.getStartDate();
                    LocalDate budgetEndDate = sourceBudget.getEndDate();

                    // Validate budget dates
                    if (budgetStartDate == null || budgetEndDate == null) {
                        throw new IllegalArgumentException("Budget startDate or endDate is missing");
                    }

                    // Get dynamic condition fields
                    @SuppressWarnings("unchecked")
                    List<String> days = (List<String>) sourceConditions.getOrDefault("days", List.of());
                    String disbursementTimeStr = (String) sourceConditions.getOrDefault("disbursementTime", "08:00");
                    Double dynamicLimit;
                    try {
                        dynamicLimit = Double.parseDouble(sourceConditions.get("limit").toString());
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid dynamic limit: " + sourceConditions.get("limit"));
                    }
                    LocalTime disbursementTime;
                    try {
                        disbursementTime = LocalTime.parse(disbursementTimeStr);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid disbursementTime format: " + disbursementTimeStr);
                    }

                    // Validate days
                    if (days.isEmpty()) {
                        throw new IllegalArgumentException("No days specified for dynamic condition");
                    }
                    List<DayOfWeek> allowedDays;
                    try {
                        allowedDays = days.stream()
                                .map(day -> DayOfWeek.valueOf(day.toUpperCase()))
                                .collect(Collectors.toList());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid day in dynamic condition: " + e.getMessage());
                    }

                    // Check budget period
                    if (currentDate.isBefore(budgetStartDate)) {
                        LocalDateTime nextValidWindow = findNextValidWindow(budgetStartDate.minusDays(1), budgetStartDate, budgetEndDate, allowedDays, disbursementTime);
                        if (nextValidWindow == null) {
                            throw new IllegalArgumentException(
                                    "No valid transfer windows available within budget period: " + budgetStartDate + " to " + budgetEndDate);
                        }
                        isTransferAllowed = false;
                        throw new IllegalArgumentException(
                                String.format("Transfer only allowed on %s at %s with limit ₦%.2f (next window: %s)",
                                        days, disbursementTime, dynamicLimit, nextValidWindow));
                    } else if (currentDate.isAfter(budgetEndDate)) {
                        throw new IllegalArgumentException(
                                "Budget period has ended on " + budgetEndDate + "; no further transfers allowed");
                    }

                    // Check if today is an allowed day
                    DayOfWeek currentDayOfWeek = currentDate.getDayOfWeek();
                    if (!allowedDays.contains(currentDayOfWeek)) {
                        LocalDateTime nextValidWindow = findNextValidWindow(currentDate, budgetStartDate, budgetEndDate, allowedDays, disbursementTime);
                        if (nextValidWindow == null) {
                            throw new IllegalArgumentException(
                                    "No valid transfer windows available within budget period: " + budgetStartDate + " to " + budgetEndDate);
                        }
                        isTransferAllowed = false;
                        throw new IllegalArgumentException(
                                String.format("Transfer only allowed on %s at %s with limit ₦%.2f (next window: %s)",
                                        days, disbursementTime, dynamicLimit, nextValidWindow));
                    }

                    // Check time window
                    LocalDateTime validStartTime = currentDate.atTime(disbursementTime);
                    LocalDateTime validEndTime = validStartTime.plusHours(1);
                    if (now.isBefore(validStartTime)) {
                        LocalDateTime nextValidWindow = validStartTime; // Same day, later
                        isTransferAllowed = false;
                        throw new IllegalArgumentException(
                                String.format("Transfer only allowed on %s at %s with limit ₦%.2f (next window: %s)",
                                        days, disbursementTime, dynamicLimit, nextValidWindow));
                    } else if (now.isAfter(validEndTime)) {
                        LocalDateTime nextValidWindow = findNextValidWindow(currentDate, budgetStartDate, budgetEndDate, allowedDays, disbursementTime);
                        if (nextValidWindow == null) {
                            throw new IllegalArgumentException(
                                    "No valid transfer windows available within budget period: " + budgetStartDate + " to " + budgetEndDate);
                        }
                        isTransferAllowed = false;
                        throw new IllegalArgumentException(
                                String.format("Transfer only allowed on %s at %s with limit ₦%.2f (next window: %s)",
                                        days, disbursementTime, dynamicLimit, nextValidWindow));
                    }

                    // Set period for limit check (current day)
                    periodStart = currentDate.atStartOfDay();
                    periodEnd = periodStart.plusDays(1);
                    break;
                default:
                    periodStart = now.minusYears(1);
                    periodEnd = now.plusYears(1);
            }

            List<TransactionLog> transactions = transactionLogRepository.findBySourceEnvelopeIdAndTimeRange(sourceId, periodStart, periodEnd);
            BigDecimal totalTransferred = transactions.stream()
                    .filter(t -> "envelope_to_envelope".equals(t.getTransactionType()) || "envelope_to_external".equals(t.getTransactionType()))
                    .map(TransactionLog::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            switch (sourceConditionType) {
                case "daily":
                    Double dailyLimit = Double.parseDouble(sourceConditions.get("limit").toString());
                    BigDecimal remainingDaily = BigDecimal.valueOf(dailyLimit).subtract(totalTransferred);
                    if (remainingDaily.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new IllegalArgumentException("Daily limit of ₦" + dailyLimit + " exhausted until " + periodEnd);
                    }
                    if (transferAmount.compareTo(remainingDaily) > 0) {
                        throw new IllegalArgumentException("Transfer exceeds remaining daily limit of ₦" + remainingDaily);
                    }
                    feePercentage = new BigDecimal("2");
                    break;
                case "weekly":
                    Double weeklyLimit = Double.parseDouble(sourceConditions.get("limit").toString());
                    BigDecimal remainingWeekly = BigDecimal.valueOf(weeklyLimit).subtract(totalTransferred);
                    if (remainingWeekly.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new IllegalArgumentException("Weekly limit of ₦" + weeklyLimit + " exhausted until " + periodEnd);
                    }
                    if (transferAmount.compareTo(remainingWeekly) > 0) {
                        throw new IllegalArgumentException("Transfer exceeds remaining weekly limit of ₦" + remainingWeekly);
                    }
                    feePercentage = new BigDecimal("2");
                    break;
                case "safe_lock":
                    feePercentage = new BigDecimal("1");
                    break;
                case "strict_lock":
                    feePercentage = new BigDecimal("5");
                    break;
                case "dynamic":
                    Double dynamicLimit = Double.parseDouble(sourceConditions.get("limit").toString());
                    BigDecimal remainingDynamic = BigDecimal.valueOf(dynamicLimit).subtract(totalTransferred);
                    if (remainingDynamic.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new IllegalArgumentException("Dynamic limit of ₦" + dynamicLimit + " exhausted until " + periodEnd);
                    }
                    if (transferAmount.compareTo(remainingDynamic) > 0) {
                        throw new IllegalArgumentException("Transfer exceeds remaining dynamic limit of ₦" + remainingDynamic);
                    }
                    feePercentage = new BigDecimal("2");
                    break;
                default:
                    throw new IllegalArgumentException("Unknown condition type: " + sourceConditionType);
            }

            if (!isTransferAllowed) {
                throw new IllegalArgumentException("Transfer not allowed due to source envelope conditions");
            }

            // Step 3: Calculate fee and update amounts
            BigDecimal fee = transferAmount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal amountAfterFee = transferAmount.subtract(fee);

            // Update remaining amounts
            source.setRemainingAmount(source.getRemainingAmount().subtract(transferAmount));
            target.setRemainingAmount(target.getRemainingAmount().add(amountAfterFee));

            // Step 4: Save updated envelopes
            envelopeRepository.save(source);
            envelopeRepository.save(target);

            // Step 5: Log transaction
            TransactionLog transactionLog = new TransactionLog(
                    user.getId(),
                    sourceBudget.getId(),
                    sourceId,
                    targetId,
                    null, // No external account
                    transferAmount,
                    fee,
                    "envelope_to_envelope",
                    null // No description for successful transfer
            );
            transactionLog.setCreatedAt(now);
            transactionLogRepository.save(transactionLog);

            // Step 6: Log fee to revenue_logs
            String revenueDescription = String.format("Transfer in Budget %d from %s to %s (fee: %s%%)",
                    sourceBudget.getId(), source.getName(), target.getName(), feePercentage);
            RevenueLog revenueLog = new RevenueLog(
                    user.getId(),
                    "envelope_transfer_fee",
                    fee,
                    revenueDescription
            );
            revenueLog.setCreatedAt(now); // Use Postgres date
            revenueLogRepository.save(revenueLog);

            // Step 7: Credit Moniewise revenue account
            creditRevenueAccount(fee, revenueDescription);
        }


//    @Transactional
//    public void moveMoney(Long sourceId, Long targetId, Double amount, String email) {
//        // Step 1: Validate inputs and ownership
//        if (amount <= 0) {
//            throw new IllegalArgumentException("Amount must be positive");
//        }
//        User user = userService.findByEmail(email);
//        Envelope source = envelopeRepository.findById(sourceId)
//                .orElseThrow(() -> new IllegalArgumentException("Source envelope not found with ID: " + sourceId));
//        Envelope target = envelopeRepository.findById(targetId)
//                .orElseThrow(() -> new IllegalArgumentException("Target envelope not found with ID: " + targetId));
//
//        Budget sourceBudget = source.getBudget();
//        Budget targetBudget = target.getBudget();
//        System.out.println("Moving money within Budget ID: " + sourceBudget.getId() + " (" + sourceBudget.getName() + ")");
//        if (!sourceBudget.getUser().getId().equals(user.getId()) || !targetBudget.getUser().getId().equals(user.getId())) {
//            throw new SecurityException("You do not have permission to move money from/to these envelopes");
//        }
//        if (!sourceBudget.getId().equals(targetBudget.getId())) {
//            throw new IllegalArgumentException("Source and target envelopes must belong to the same budget");
//        }
//        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
//            throw new IllegalArgumentException("Budget must be active to perform transactions");
//        }
//
//        BigDecimal transferAmount = BigDecimal.valueOf(amount);
//        if (transferAmount.compareTo(source.getRemainingAmount()) > 0) {
//            throw new IllegalArgumentException("Insufficient funds in source envelope. Available: " + source.getRemainingAmount());
//        }
//
//        // Step 2: Check source envelope conditions and limits
//        Map<String, Object> sourceConditions = source.getConditions();
//        String sourceConditionType = (String) sourceConditions.get("type");
//        boolean isTransferAllowed = true;
//        BigDecimal feePercentage = BigDecimal.ZERO;
//
//        LocalDateTime now = LocalDateTime.now();
//        DayOfWeek currentDay = now.getDayOfWeek();
//        LocalTime currentTime = now.toLocalTime();
//
//        LocalDateTime periodStart;
//        LocalDateTime periodEnd;
//        switch (sourceConditionType) {
//            case "daily":
//                periodStart = now.toLocalDate().atStartOfDay();
//                periodEnd = periodStart.plusDays(1);
//                break;
//            case "weekly":
//                periodStart = now.toLocalDate().atStartOfDay().minusDays(now.getDayOfWeek().getValue() - 1);
//                periodEnd = periodStart.plusDays(7);
//                break;
//            case "dynamic":
//                String startDateStr = (String) sourceConditions.get("startDate");
//                Integer intervalDays = Integer.parseInt(sourceConditions.get("intervalDays").toString());
//                String disbursementTimeStr = (String) sourceConditions.get("disbursementTime");
//                LocalDate startDate = LocalDate.parse(startDateStr);
//                LocalTime disbursementTime = LocalTime.parse(disbursementTimeStr);
//
//                // Calculate the current period's valid transfer date
//                LocalDate currentDate = now.toLocalDate();
//                long daysSinceStart = ChronoUnit.DAYS.between(startDate, currentDate);
//                LocalDate nextValidDate = startDate.plusDays((daysSinceStart / intervalDays) * intervalDays);
//                // Allow a 1-hour window around disbursementTime for flexibility
//                LocalDateTime validStartTime = nextValidDate.atTime(disbursementTime);
//                LocalDateTime validEndTime = validStartTime.plusHours(1);
//
//                if (!currentDate.equals(nextValidDate) || now.isBefore(validStartTime) || now.isAfter(validEndTime)) {
//                    isTransferAllowed = false;
//                    throw new IllegalArgumentException("Transfer only allowed every " + intervalDays + " days at " + disbursementTime + " (next window: " + validStartTime + ")");
//                }
//                periodStart = nextValidDate.atStartOfDay();
//                periodEnd = nextValidDate.plusDays(1).atStartOfDay();
//                break;
//            default:
//                periodStart = LocalDateTime.now().minusYears(1);
//                periodEnd = LocalDateTime.now().plusYears(1);
//        }
//
//        List<TransactionLog> transactions = transactionLogRepository.findBySourceEnvelopeIdAndTimeRange(sourceId, periodStart, periodEnd);
//        BigDecimal totalTransferred = transactions.stream()
//                .filter(t -> "envelope_to_envelope".equals(t.getTransactionType()) || "envelope_to_external".equals(t.getTransactionType()))
//                .map(TransactionLog::getAmount)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//
//        switch (sourceConditionType) {
//            case "daily":
//                Double dailyLimit = Double.parseDouble(sourceConditions.get("limit").toString());
//                BigDecimal remainingDaily = BigDecimal.valueOf(dailyLimit).subtract(totalTransferred);
//                if (remainingDaily.compareTo(BigDecimal.ZERO) <= 0) {
//                    throw new IllegalArgumentException("Daily limit of ₦" + dailyLimit + " exhausted until " + periodEnd);
//                }
//                if (transferAmount.compareTo(remainingDaily) > 0) {
//                    throw new IllegalArgumentException("Transfer exceeds remaining daily limit of ₦" + remainingDaily);
//                }
//                feePercentage = new BigDecimal("2");
//                break;
//
//            case "weekly":
//                Double weeklyLimit = Double.parseDouble(sourceConditions.get("limit").toString());
//                BigDecimal remainingWeekly = BigDecimal.valueOf(weeklyLimit).subtract(totalTransferred);
//                if (remainingWeekly.compareTo(BigDecimal.ZERO) <= 0) {
//                    throw new IllegalArgumentException("Weekly limit of ₦" + weeklyLimit + " exhausted until " + periodEnd);
//                }
//                if (transferAmount.compareTo(remainingWeekly) > 0) {
//                    throw new IllegalArgumentException("Transfer exceeds remaining weekly limit of ₦" + remainingWeekly);
//                }
//                feePercentage = new BigDecimal("2");
//                break;
//
//            case "safe_lock":
//                feePercentage = new BigDecimal("1");
//                break;
//
//            case "strict_lock":
//                feePercentage = new BigDecimal("5");
//                break;
//
//            case "dynamic":
//                Double dynamicLimit = Double.parseDouble(sourceConditions.get("limit").toString());
//                BigDecimal remainingDynamic = BigDecimal.valueOf(dynamicLimit).subtract(totalTransferred);
//                if (remainingDynamic.compareTo(BigDecimal.ZERO) <= 0) {
//                    throw new IllegalArgumentException("Dynamic limit of ₦" + dynamicLimit + " exhausted until " + periodEnd);
//                }
//                if (transferAmount.compareTo(remainingDynamic) > 0) {
//                    throw new IllegalArgumentException("Transfer exceeds remaining dynamic limit of ₦" + remainingDynamic);
//                }
//                feePercentage = new BigDecimal("2");
//                break;
//
//            default:
//                throw new IllegalArgumentException("Unknown condition type: " + sourceConditionType);
//        }
//
//        if (!isTransferAllowed) {
//            throw new IllegalArgumentException("Transfer not allowed due to source envelope conditions");
//        }
//
//        // Step 3: Calculate fee and update amounts
//        BigDecimal fee = transferAmount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
//        BigDecimal amountAfterFee = transferAmount.subtract(fee);
//
//        // Update remaining amounts
//        source.setRemainingAmount(source.getRemainingAmount().subtract(transferAmount));
//        target.setRemainingAmount(target.getRemainingAmount().add(amountAfterFee));
//
//        // Step 4: Save updated envelopes
//        envelopeRepository.save(source);
//        envelopeRepository.save(target);
//
//        // Step 5: Log transaction
//        TransactionLog transactionLog = new TransactionLog(
//                user.getId(),
//                sourceBudget.getId(),
//                sourceId,
//                targetId,
//                null,
//                transferAmount,
//                fee,
//                "envelope_to_envelope"
//        );
//        transactionLogRepository.save(transactionLog);
//
//        // Step 6: Log fee to revenue_logs
//        String revenueDescription = "Transfer in Budget " + sourceBudget.getId() + " from " + source.getName() + " to " + target.getName() + " (fee: " + feePercentage + "%)";
//        RevenueLog revenueLog = new RevenueLog(
//                user.getId(),
//                "envelope_transfer_fee",
//                fee,
//                revenueDescription
//        );
//        revenueLogRepository.save(revenueLog);
//
//        // Step 7: Credit Moniewise revenue account
//        creditRevenueAccount(fee, revenueDescription);
//    }

    // MOVE MONEY between envelopes 12/04/2025 ---
//    @Transactional
//    public void moveMoney(Long sourceId, Long targetId, Double amount, String email) {
//        // Step 1: Validate inputs and ownership
//        if (amount <= 0) {
//            throw new IllegalArgumentException("Amount must be positive");
//        }
//        User user = userService.findByEmail(email);
//        Envelope source = envelopeRepository.findById(sourceId)
//                .orElseThrow(() -> new IllegalArgumentException("Source envelope not found with ID: " + sourceId));
//        Envelope target = envelopeRepository.findById(targetId)
//                .orElseThrow(() -> new IllegalArgumentException("Target envelope not found with ID: " + targetId));
//
//        Budget sourceBudget = source.getBudget();
//        Budget targetBudget = target.getBudget();
//        System.out.println("Moving money within Budget ID: " + sourceBudget.getId() + " (" + sourceBudget.getName() + ")");
//        if (!sourceBudget.getUser().getId().equals(user.getId()) || !targetBudget.getUser().getId().equals(user.getId())) {
//            throw new SecurityException("You do not have permission to move money from/to these envelopes");
//        }
//        if (!sourceBudget.getId().equals(targetBudget.getId())) {
//            throw new IllegalArgumentException("Source and target envelopes must belong to the same budget");
//        }
//        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
//            throw new IllegalArgumentException("Budget must be active to perform transactions");
//        }
//
//        BigDecimal transferAmount = BigDecimal.valueOf(amount);
//        if (transferAmount.compareTo(source.getRemainingAmount()) > 0) {
//            throw new IllegalArgumentException("Insufficient funds in source envelope. Available: " + source.getRemainingAmount());
//        }
//
//        // Step 2: Check source envelope conditions and limits
//        Map<String, Object> sourceConditions = source.getConditions();
//        String sourceConditionType = (String) sourceConditions.get("type");
//        boolean isTransferAllowed = true;
//        BigDecimal feePercentage = BigDecimal.ZERO;
//
//        LocalDateTime now = LocalDateTime.now();
//        DayOfWeek currentDay = now.getDayOfWeek();
//        LocalTime currentTime = now.toLocalTime();
//
//        LocalDateTime periodStart;
//        LocalDateTime periodEnd;
//        switch (sourceConditionType) {
//            case "daily":
//                periodStart = now.toLocalDate().atStartOfDay();
//                periodEnd = periodStart.plusDays(1);
//                break;
//            case "weekly":
//                periodStart = now.toLocalDate().atStartOfDay().minusDays(now.getDayOfWeek().getValue() - 1);
//                periodEnd = periodStart.plusDays(7);
//                break;
//            case "dynamic":
//                String day = (String) sourceConditions.get("day");
//                DayOfWeek conditionDay = DayOfWeek.valueOf(day.toUpperCase());
//                LocalDate targetDate = now.toLocalDate();
//                while (targetDate.getDayOfWeek() != conditionDay) {
//                    targetDate = targetDate.plusDays(1);
//                }
//                String startTimeStr = (String) sourceConditions.get("start_time");
//                String endTimeStr = (String) sourceConditions.get("end_time");
//                LocalTime startTime = LocalTime.parse(startTimeStr);
//                LocalTime endTime = LocalTime.parse(endTimeStr);
//                periodStart = LocalDateTime.of(targetDate, startTime);
//                periodEnd = LocalDateTime.of(targetDate, endTime);
//                break;
//            default:
//                periodStart = LocalDateTime.now().minusYears(1);
//                periodEnd = LocalDateTime.now().plusYears(1);
//        }
//
//        List<TransactionLog> transactions = transactionLogRepository.findBySourceEnvelopeIdAndTimeRange(sourceId, periodStart, periodEnd);
//        BigDecimal totalTransferred = transactions.stream()
//                .filter(t -> "envelope_to_envelope".equals(t.getTransactionType()) || "envelope_to_external".equals(t.getTransactionType()))
//                .map(TransactionLog::getAmount)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//
//        switch (sourceConditionType) {
//            case "daily":
//                Double dailyLimit = Double.parseDouble(sourceConditions.get("limit").toString());
//                BigDecimal remainingDaily = BigDecimal.valueOf(dailyLimit).subtract(totalTransferred);
//                if (remainingDaily.compareTo(BigDecimal.ZERO) <= 0) {
//                    throw new IllegalArgumentException("Daily limit of ₦" + dailyLimit + " exhausted until " + periodEnd);
//                }
//                if (transferAmount.compareTo(remainingDaily) > 0) {
//                    throw new IllegalArgumentException("Transfer exceeds remaining daily limit of ₦" + remainingDaily);
//                }
//                feePercentage = new BigDecimal("2");
//                break;
//
//            case "weekly":
//                Double weeklyLimit = Double.parseDouble(sourceConditions.get("limit").toString());
//                BigDecimal remainingWeekly = BigDecimal.valueOf(weeklyLimit).subtract(totalTransferred);
//                if (remainingWeekly.compareTo(BigDecimal.ZERO) <= 0) {
//                    throw new IllegalArgumentException("Weekly limit of ₦" + weeklyLimit + " exhausted until " + periodEnd);
//                }
//                if (transferAmount.compareTo(remainingWeekly) > 0) {
//                    throw new IllegalArgumentException("Transfer exceeds remaining weekly limit of ₦" + remainingWeekly);
//                }
//                feePercentage = new BigDecimal("2");
//                break;
//
//            case "safe_lock":
//                feePercentage = new BigDecimal("1");
//                break;
//
//            case "strict_lock":
//                feePercentage = new BigDecimal("5");
//                break;
//
//            case "dynamic":
//                String day = (String) sourceConditions.get("day");
//                String startTimeStr = (String) sourceConditions.get("start_time");
//                String endTimeStr = (String) sourceConditions.get("end_time");
//                LocalTime startTime = LocalTime.parse(startTimeStr);
//                LocalTime endTime = LocalTime.parse(endTimeStr);
//                DayOfWeek conditionDay = DayOfWeek.valueOf(day.toUpperCase());
//
//                if (!(currentDay == conditionDay && currentTime.isAfter(startTime) && currentTime.isBefore(endTime))) {
//                    isTransferAllowed = false;
//                    throw new IllegalArgumentException("Transfer not allowed outside of " + day + " " + startTime + " to " + endTime);
//                }
//                feePercentage = new BigDecimal("2");
//                break;
//
//            default:
//                throw new IllegalArgumentException("Unknown condition type: " + sourceConditionType);
//        }
//
//        if (!isTransferAllowed) {
//            throw new IllegalArgumentException("Transfer not allowed due to source envelope conditions");
//        }
//
//        // Step 3: Calculate fee and update amounts
//        BigDecimal fee = transferAmount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
//        BigDecimal amountAfterFee = transferAmount.subtract(fee);
//
//        // Update remaining amounts
//        source.setRemainingAmount(source.getRemainingAmount().subtract(transferAmount));
//        target.setRemainingAmount(target.getRemainingAmount().add(amountAfterFee));
//
//        // Step 4: Save updated envelopes
//        envelopeRepository.save(source);
//        envelopeRepository.save(target);
//
//        // Step 5: Log transaction
//        TransactionLog transactionLog = new TransactionLog(
//                user.getId(),
//                sourceBudget.getId(),
//                sourceId,
//                targetId,
//                null,
//                transferAmount,
//                fee,
//                "envelope_to_envelope"
//        );
//        transactionLogRepository.save(transactionLog);
//
//        // Step 6: Log fee to revenue_logs
//        String revenueDescription = "Transfer in Budget " + sourceBudget.getId() + " from " + source.getName() + " to " + target.getName() + " (fee: " + feePercentage + "%)";
//        RevenueLog revenueLog = new RevenueLog(
//                user.getId(),
//                "envelope_transfer_fee",
//                fee,
//                revenueDescription
//        );
//        revenueLogRepository.save(revenueLog);
//
//        // Step 7: Credit Moniewise revenue account
//        creditRevenueAccount(fee, revenueDescription);
//    }



//    @Transactional
//    public void transferToExternal(Long sourceId, String externalAccount, Double amount, String email) {
//        if (amount <= 0) {
//            throw new IllegalArgumentException("Amount must be positive");
//        }
//        User user = userService.findByEmail(email);
//        Envelope source = envelopeRepository.findById(sourceId)
//                .orElseThrow(() -> new IllegalArgumentException("Source envelope not found with ID: " + sourceId));
//
//        Budget sourceBudget = source.getBudget();
//        System.out.println("Transferring to external from Budget ID: " + sourceBudget.getId() + " (" + sourceBudget.getName() + ")");
//        if (!sourceBudget.getUser().getId().equals(user.getId())) {
//            throw new SecurityException("You do not have permission to transfer from this envelope");
//        }
//        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
//            throw new IllegalArgumentException("Budget must be active to perform transactions");
//        }
//
//        BigDecimal transferAmount = BigDecimal.valueOf(amount);
//        if (transferAmount.compareTo(source.getRemainingAmount()) > 0) {
//            throw new IllegalArgumentException("Insufficient funds in source envelope. Available: " + source.getRemainingAmount());
//        }
//
//        Map<String, Object> sourceConditions = source.getConditions();
//        String sourceConditionType = (String) sourceConditions.get("type");
//        boolean isTransferAllowed = true;
//        BigDecimal feePercentage = BigDecimal.ZERO;
//
////        LocalDateTime now = LocalDateTime.now();
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//        DayOfWeek currentDay = now.getDayOfWeek();
//        LocalTime currentTime = now.toLocalTime();
//
//        LocalDateTime periodStart;
//        LocalDateTime periodEnd;
//        switch (sourceConditionType) {
//            case "daily":
//                periodStart = now.toLocalDate().atStartOfDay();
//                periodEnd = periodStart.plusDays(1);
//                break;
//            case "weekly":
//                periodStart = now.toLocalDate().atStartOfDay().minusDays(now.getDayOfWeek().getValue() - 1);
//                periodEnd = periodStart.plusDays(7);
//                break;
//            case "dynamic":
//                String day = (String) sourceConditions.get("day");
//                DayOfWeek conditionDay = DayOfWeek.valueOf(day.toUpperCase());
//                LocalDate targetDate = now.toLocalDate();
//                while (targetDate.getDayOfWeek() != conditionDay) {
//                    targetDate = targetDate.plusDays(1);
//                }
//                String startTimeStr = (String) sourceConditions.get("start_time");
//                String endTimeStr = (String) sourceConditions.get("end_time");
//                LocalTime startTime = LocalTime.parse(startTimeStr);
//                LocalTime endTime = LocalTime.parse(endTimeStr);
//                periodStart = LocalDateTime.of(targetDate, startTime);
//                periodEnd = LocalDateTime.of(targetDate, endTime);
//                break;
//            default:
//                periodStart = LocalDateTime.now().minusYears(1);
//                periodEnd = LocalDateTime.now().plusYears(1);
//        }
//
//        List<TransactionLog> transactions = transactionLogRepository.findBySourceEnvelopeIdAndTimeRange(sourceId, periodStart, periodEnd);
//        BigDecimal totalTransferred = transactions.stream()
//                .filter(t -> "envelope_to_envelope".equals(t.getTransactionType()) || "envelope_to_external".equals(t.getTransactionType()))
//                .map(TransactionLog::getAmount)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//
//        switch (sourceConditionType) {
//            case "daily":
//                Double dailyLimit = Double.parseDouble(sourceConditions.get("limit").toString());
//                BigDecimal remainingDaily = BigDecimal.valueOf(dailyLimit).subtract(totalTransferred);
//                if (remainingDaily.compareTo(BigDecimal.ZERO) <= 0) {
//                    throw new IllegalArgumentException("Daily limit of ₦" + dailyLimit + " exhausted until " + periodEnd);
//                }
//                if (transferAmount.compareTo(remainingDaily) > 0) {
//                    throw new IllegalArgumentException("Transfer exceeds remaining daily limit of ₦" + remainingDaily);
//                }
//                feePercentage = new BigDecimal("2");
//                break;
//
//            case "weekly":
//                Double weeklyLimit = Double.parseDouble(sourceConditions.get("limit").toString());
//                BigDecimal remainingWeekly = BigDecimal.valueOf(weeklyLimit).subtract(totalTransferred);
//                if (remainingWeekly.compareTo(BigDecimal.ZERO) <= 0) {
//                    throw new IllegalArgumentException("Weekly limit of ₦" + weeklyLimit + " exhausted until " + periodEnd);
//                }
//                if (transferAmount.compareTo(remainingWeekly) > 0) {
//                    throw new IllegalArgumentException("Transfer exceeds remaining weekly limit of ₦" + remainingWeekly);
//                }
//                feePercentage = new BigDecimal("2");
//                break;
//
//            case "safe_lock":
//                feePercentage = new BigDecimal("1");
//                break;
//
//            case "strict_lock":
//                feePercentage = new BigDecimal("5");
//                break;
//
//            case "dynamic":
//                String day = (String) sourceConditions.get("day");
//                String startTimeStr = (String) sourceConditions.get("start_time");
//                String endTimeStr = (String) sourceConditions.get("end_time");
//                LocalTime startTime = LocalTime.parse(startTimeStr);
//                LocalTime endTime = LocalTime.parse(endTimeStr);
//                DayOfWeek conditionDay = DayOfWeek.valueOf(day.toUpperCase());
//
//                if (!(currentDay == conditionDay && currentTime.isAfter(startTime) && currentTime.isBefore(endTime))) {
//                    isTransferAllowed = false;
//                    throw new IllegalArgumentException("Transfer not allowed outside of " + day + " " + startTime + " to " + endTime);
//                }
//                feePercentage = new BigDecimal("2");
//                break;
//
//            default:
//                throw new IllegalArgumentException("Unknown condition type: " + sourceConditionType);
//        }
//
//        if (!isTransferAllowed) {
//            throw new IllegalArgumentException("Transfer not allowed due to source envelope conditions");
//        }
//
//        BigDecimal fee = transferAmount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
//        BigDecimal amountAfterFee = transferAmount.subtract(fee);
//
//        source.setRemainingAmount(source.getRemainingAmount().subtract(transferAmount));
//        envelopeRepository.save(source);
//
//        // TODO: Integrate with Paystack/Flutterwave to transfer 'amountAfterFee' to 'externalAccount'
//        System.out.println("Simulating transfer of ₦" + amountAfterFee + " to external account: " + externalAccount);
//
//        TransactionLog transactionLog = new TransactionLog(
//                user.getId(),
//                sourceBudget.getId(),
//                sourceId,
//                null,
//                externalAccount,
//                transferAmount,
//                fee,
//                "envelope_to_external"
//        );
//        transactionLogRepository.save(transactionLog);
//
//        String revenueDescription = "Transfer in Budget " + sourceBudget.getId() + " from " + source.getName() + " to external account " + externalAccount + " (fee: " + feePercentage + "%)";
//        RevenueLog revenueLog = new RevenueLog(
//                user.getId(),
//                "envelope_transfer_fee",
//                fee,
//                revenueDescription
//        );
//        revenueLogRepository.save(revenueLog);
//
//        // Credit Moniewise revenue account
//        creditRevenueAccount(fee, revenueDescription);
//    }

    // New Transfer to external account code --- 08/06/2025

    @Transactional
    public void transferToExternal(Long sourceId, BudgetController.ExternalAccount externalAccount, Double amount, String email) {
        Logger logger = LoggerFactory.getLogger(BudgetService.class);
        logger.debug("Transferring ₦{} to external account {} from envelope {} for user {}",
                amount, externalAccount, sourceId, email);

        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        User user = userService.findByEmail(email);
        Envelope source = envelopeRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Source envelope not found with ID: " + sourceId));

        Budget sourceBudget = source.getBudget();
        logger.debug("Transferring from Budget ID: {} ({})", sourceBudget.getId(), sourceBudget.getName());
        if (!sourceBudget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to transfer from this envelope");
        }
        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Budget must be active to perform transactions");
        }

        BigDecimal transferAmount = BigDecimal.valueOf(amount);
        if (transferAmount.compareTo(source.getRemainingAmount()) > 0) {
            throw new IllegalArgumentException("Insufficient funds in source envelope. Available: ₦" + source.getRemainingAmount());
        }

        Map<String, Object> sourceConditions = source.getConditions();
        String sourceConditionType = (String) sourceConditions.get("type");
        if (sourceConditionType == null) {
            throw new IllegalArgumentException("Envelope condition type is missing");
        }
        boolean isTransferAllowed = true;
        BigDecimal feePercentage = BigDecimal.ZERO;

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        logger.debug("Server time fetched: {} (Africa/Lagos)", now);
        LocalDate currentDate = now.toLocalDate();
        LocalTime currentTime = now.toLocalTime();

        LocalDateTime periodStart;
        LocalDateTime periodEnd;
        switch (sourceConditionType) {
            case "daily":
                periodStart = currentDate.atStartOfDay();
                periodEnd = periodStart.plusDays(1);
                break;
            case "weekly":
                periodStart = currentDate.atStartOfDay().minusDays(currentDate.getDayOfWeek().getValue() - 1);
                periodEnd = periodStart.plusDays(7);
                break;
            case "emergency":
                periodStart = currentDate.atStartOfDay();
                periodEnd = sourceBudget.getEndDate().atTime(23, 59, 59);
                // Apply 10% fee before endDate, 0% on/after
                feePercentage = currentDate.isBefore(sourceBudget.getEndDate()) ? new BigDecimal("10") : BigDecimal.ZERO;
                // Update conditions.used to true
                sourceConditions.put("used", true);
                source.setConditions(sourceConditions);
                envelopeRepository.save(source);
                break;
            case "dynamic":
                logger.debug("Dynamic conditions for envelope {}: {}", source.getName(), sourceConditions);

                LocalDate budgetStartDate = sourceBudget.getStartDate();
                LocalDate budgetEndDate = sourceBudget.getEndDate();
                if (budgetStartDate == null || budgetEndDate == null) {
                    throw new IllegalArgumentException("Budget startDate or endDate is missing");
                }

                @SuppressWarnings("unchecked")
                List<String> days = (List<String>) sourceConditions.getOrDefault("days", List.of());
                String disbursementTimeStr = (String) sourceConditions.getOrDefault("disbursementTime", "08:00");
                Double dynamicLimit;
                try {
                    dynamicLimit = Double.parseDouble(sourceConditions.get("limit").toString());
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid dynamic limit: " + sourceConditions.get("limit"), e);
                }
                LocalTime disbursementTime;
                try {
                    disbursementTime = LocalTime.parse(disbursementTimeStr);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid disbursementTime format: " + disbursementTimeStr, e);
                }

                if (days.isEmpty()) {
                    throw new IllegalArgumentException("No days specified for dynamic condition");
                }
                List<DayOfWeek> allowedDays;
                try {
                    allowedDays = days.stream()
                            .map(day -> DayOfWeek.valueOf(day.toUpperCase()))
                            .collect(Collectors.toList());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid day in dynamic condition: " + e.getMessage(), e);
                }

                periodStart = currentDate.atStartOfDay();
                periodEnd = periodStart.plusDays(1);

                if (currentDate.isBefore(budgetStartDate)) {
                    LocalDateTime nextValidWindow = findNextValidWindow(budgetStartDate.minusDays(1), budgetStartDate, budgetEndDate, allowedDays, disbursementTime);
                    isTransferAllowed = false;
                    String reason = "Outside budget period: currentDate=" + currentDate + ", startDate=" + budgetStartDate;
                    logger.warn("Attempted transfer outside budget period for envelope {} by user {}: {}", sourceId, email, reason);
                    TransactionLog transactionLog = new TransactionLog(
                            user.getId(), sourceBudget.getId(), sourceId, null, externalAccount.getAccountNumber(),
                            transferAmount, BigDecimal.ZERO, "failed_external_transfer", reason);
                    transactionLog.setCreatedAt(now);
                    transactionLogRepository.save(transactionLog);
                    throw new IllegalArgumentException(
                            String.format("Transfer only allowed on %s at %s with limit ₦%.2f (next window: %s)",
                                    days, disbursementTime, dynamicLimit, nextValidWindow != null ? nextValidWindow : "none"));
                } else if (currentDate.isAfter(budgetEndDate)) {
                    String reason = "After budget period: currentDate=" + currentDate + ", endDate=" + budgetEndDate;
                    logger.warn("Attempted transfer after budget period for envelope {} by user {}: {}", sourceId, email, reason);
                    TransactionLog transactionLog = new TransactionLog(
                            user.getId(), sourceBudget.getId(), sourceId, null, externalAccount.getAccountNumber(),
                            transferAmount, BigDecimal.ZERO, "failed_external_transfer", reason);
                    transactionLog.setCreatedAt(now);
                    transactionLogRepository.save(transactionLog);
                    throw new IllegalArgumentException(
                            "Budget period has ended on " + budgetEndDate + "; no further transfers allowed");
                }

                DayOfWeek currentDayOfWeek = currentDate.getDayOfWeek();
                if (!allowedDays.contains(currentDayOfWeek)) {
                    LocalDateTime nextValidWindow = findNextValidWindow(currentDate, budgetStartDate, budgetEndDate, allowedDays, disbursementTime);
                    isTransferAllowed = false;
                    String reason = "Invalid day: " + currentDayOfWeek;
                    logger.warn("Attempted transfer on invalid day for envelope {} by user {}: {}", sourceId, email, reason);
                    TransactionLog transactionLog = new TransactionLog(
                            user.getId(), sourceBudget.getId(), sourceId, null, externalAccount.getAccountNumber(),
                            transferAmount, BigDecimal.ZERO, "failed_external_transfer", reason);
                    transactionLog.setCreatedAt(now);
                    transactionLogRepository.save(transactionLog);
                    throw new IllegalArgumentException(
                            String.format("Transfer only allowed on %s at %s with limit ₦%.2f (next window: %s)",
                                    days, disbursementTime, dynamicLimit, nextValidWindow != null ? nextValidWindow : "none"));
                }

                LocalDateTime validStartTime = currentDate.atTime(disbursementTime);
                LocalDateTime validEndTime = validStartTime.plusHours(1);
                if (now.isBefore(validStartTime) || now.isAfter(validEndTime)) {
                    LocalDateTime nextValidWindow = now.isBefore(validStartTime) ? validStartTime :
                            findNextValidWindow(currentDate, budgetStartDate, budgetEndDate, allowedDays, disbursementTime);
                    isTransferAllowed = false;
                    String reason = "Outside time window: currentTime=" + currentTime + ", validWindow=" + validStartTime + " to " + validEndTime;
                    logger.warn("Attempted transfer outside time window for envelope {} by user {}: {}", sourceId, email, reason);
                    TransactionLog transactionLog = new TransactionLog(
                            user.getId(), sourceBudget.getId(), sourceId, null, externalAccount.getAccountNumber(),
                            transferAmount, BigDecimal.ZERO, "failed_external_transfer", reason);
                    transactionLog.setCreatedAt(now);
                    transactionLogRepository.save(transactionLog);
                    throw new IllegalArgumentException(
                            String.format("Transfer only allowed on %s at %s with limit ₦%.2f (next window: %s)",
                                    days, disbursementTime, dynamicLimit, nextValidWindow != null ? nextValidWindow : "none"));
                }
                break;
            default:
                periodStart = now.minusYears(1);
                periodEnd = now.plusYears(1);
        }

        List<TransactionLog> transactions = transactionLogRepository.findBySourceEnvelopeIdAndTimeRange(sourceId, periodStart, periodEnd);
        BigDecimal totalTransferred = transactions.stream()
                .filter(t -> "envelope_to_envelope".equals(t.getTransactionType()) || "envelope_to_external".equals(t.getTransactionType()))
                .map(TransactionLog::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        switch (sourceConditionType) {
            case "daily":
                Double dailyLimit = Double.parseDouble(sourceConditions.get("limit").toString());
                BigDecimal remainingDaily = BigDecimal.valueOf(dailyLimit).subtract(totalTransferred);
                if (remainingDaily.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Daily limit of ₦" + dailyLimit + " exhausted until " + periodEnd);
                }
                if (transferAmount.compareTo(remainingDaily) > 0) {
                    throw new IllegalArgumentException("Transfer exceeds remaining daily limit of ₦" + remainingDaily);
                }
                feePercentage = new BigDecimal("2");
                break;
            case "weekly":
                Double weeklyLimit = Double.parseDouble(sourceConditions.get("limit").toString());
                BigDecimal remainingWeekly = BigDecimal.valueOf(weeklyLimit).subtract(totalTransferred);
                if (remainingWeekly.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Weekly limit of ₦" + weeklyLimit + " exhausted until " + periodEnd);
                }
                if (transferAmount.compareTo(remainingWeekly) > 0) {
                    throw new IllegalArgumentException("Transfer exceeds remaining weekly limit of ₦" + remainingWeekly);
                }
                feePercentage = new BigDecimal("2");
                break;
            case "safe_lock":
                feePercentage = new BigDecimal("1");
                break;
            case "strict_lock":
                String reason = "Strict lock envelope transfers not allowed; funds roll back after budget endDate: " + sourceBudget.getEndDate();
                logger.warn("Attempted transfer from strict_lock envelope {} by user {}: {}", sourceId, email, reason);
                TransactionLog transactionLog = new TransactionLog(
                        user.getId(), sourceBudget.getId(), sourceId, null, externalAccount.getAccountNumber(),
                        transferAmount, BigDecimal.ZERO, "failed_external_transfer", reason);
                transactionLog.setCreatedAt(now);
                transactionLogRepository.save(transactionLog);
                throw new IllegalArgumentException(reason);
            case "dynamic":
                Double dynamicLimit = Double.parseDouble(sourceConditions.get("limit").toString());
                BigDecimal remainingDynamic = BigDecimal.valueOf(dynamicLimit).subtract(totalTransferred);
                if (remainingDynamic.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Dynamic limit of ₦" + dynamicLimit + " exhausted until " + periodEnd);
                }
                if (transferAmount.compareTo(remainingDynamic) > 0) {
                    throw new IllegalArgumentException("Transfer exceeds remaining dynamic limit of ₦" + remainingDynamic);
                }
                feePercentage = new BigDecimal("2");
                break;
            case "emergency":
                // Fee already set in first switch (10% before endDate, 0% on/after); no limit checks needed
                break;
            default:
                throw new IllegalArgumentException("Unknown condition type: " + sourceConditionType);
        }

        if (!isTransferAllowed) {
            throw new IllegalArgumentException("Transfer not allowed due to source envelope conditions");
        }

        BigDecimal fee = transferAmount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal amountAfterFee = transferAmount.subtract(fee);

        source.setRemainingAmount(source.getRemainingAmount().subtract(transferAmount));
        envelopeRepository.save(source);

        // TODO: Integrate with Paystack/Flutterwave to transfer 'amountAfterFee' to 'externalAccount'
        logger.info("Simulating transfer of ₦{} to external account: {} (accountNumber: {}, bankCode: {}, recipient: {})",
                amountAfterFee, externalAccount.getBankName() != null ? externalAccount.getBankName() : "Unknown",
                externalAccount.getAccountNumber(), externalAccount.getBankCode(), externalAccount.getRecipientName());

        TransactionLog transactionLog = new TransactionLog(
                user.getId(), sourceBudget.getId(), sourceId, null, externalAccount.getAccountNumber(),
                transferAmount, fee, "envelope_to_external", null);
        transactionLog.setCreatedAt(now);
        transactionLogRepository.save(transactionLog);

        String revenueDescription = String.format("Transfer in Budget %d from %s to external account %s/%s (%s) (fee: %s%%)",
                sourceBudget.getId(), source.getName(),
                externalAccount.getBankName() != null ? externalAccount.getBankName() : "Unknown",
                externalAccount.getAccountNumber(), externalAccount.getRecipientName(), feePercentage);
        RevenueLog revenueLog = new RevenueLog(user.getId(), "envelope_transfer_fee", fee, revenueDescription);
        revenueLog.setCreatedAt(now);
        revenueLogRepository.save(revenueLog);

        creditRevenueAccount(fee, revenueDescription);
    }


//    private LocalDateTime findNextValidWindow(
//            LocalDate searchDate,
//            LocalDate budgetStartDate,
//            LocalDate budgetEndDate,
//            List<DayOfWeek> allowedDays,
//            LocalTime disbursementTime) {
//        LocalDate nextDate = searchDate;
//        for (int i = 0; i <= 31; i++) {
//            nextDate = nextDate.plusDays(1);
//            if (nextDate.isAfter(budgetEndDate)) {
//                return null;
//            }
//            if (allowedDays.contains(nextDate.getDayOfWeek()) && !nextDate.isBefore(budgetStartDate)) {
//                return nextDate.atTime(disbursementTime);
//            }
//        }
//        return null;
//    }

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
                "wallet_to_budget",
                "Topped up budget from user wallet"
        );
        transactionLog.setCreatedAt(now);
        transactionLogRepository.save(transactionLog);
    }

    // 13/04/2025 -----> New: Check Budget end date
    public void checkBudgetEnd(Long budgetId) {
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        LocalDate today = LocalDate.now();
        LocalDate endDate = budget.getEndDate();

        // Check if Budget has ended
        if (today.isAfter(endDate)) {
            System.out.println("Budget ID " + budgetId + " has ended on " + endDate + ". Current status: " + budget.getStatus());
            if (budget.getStatus() != BudgetStatus.COMPLETED) {
                budget.setStatus(BudgetStatus.COMPLETED);
                budgetRepository.save(budget);
                System.out.println("Updated Budget ID " + budgetId + " status to ENDED");
                // Roll back strict_lock funds if any
                rollbackStrictLock(budgetId, budget.getUser().getEmail());
            }
        } else if (today.plusDays(3).isAfter(endDate) && today.isBefore(endDate.plusDays(1))) {
            // Warn if Budget is ending within 3 days
            System.out.println("Budget ID " + budgetId + " is nearing its end date (" + endDate + "). Consider extending.");
        } else {
            System.out.println("Budget ID " + budgetId + " is active until " + endDate);
        }
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
                "budget_extension",
                "Extended budget end date to " + newEndDate
        );
        transactionLog.setCreatedAt(now);
        transactionLogRepository.save(transactionLog);
    }

    // New: Rollback strict_lock funds to Wallet
    @Transactional
    public void rollbackStrictLock(Long budgetId, String email) {
        User user = userService.findByEmail(email);
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to access this budget");
        }

        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budgetId);
        BigDecimal totalRollbackAmount = BigDecimal.ZERO;

        for (Envelope envelope : envelopes) {
            Map<String, Object> conditions = envelope.getConditions();
            String conditionType = (String) conditions.get("type");
            if ("strict_lock".equals(conditionType)) {
                BigDecimal remainingAmount = envelope.getRemainingAmount();
                if (remainingAmount.compareTo(BigDecimal.ZERO) > 0) {
                    totalRollbackAmount = totalRollbackAmount.add(remainingAmount);
                    // Reset envelope
                    envelope.setRemainingAmount(BigDecimal.ZERO);
                    envelopeRepository.save(envelope);

                    // P.S: If "user_wallet" is an internal Moniewise wallet (e.g., another envelope),
                    // use targetEnvelopeId instead of external_account_id
                    LocalDateTime now = fetchCurrentDateTimeFromDatabase();
                    TransactionLog transactionLog = new TransactionLog(
                            user.getId(),
                            budgetId,
                            envelope.getId(),
                            null, // No target envelope
                            "user_wallet", // Destination is user's wallet (MVP placeholder)
                            remainingAmount,
                            BigDecimal.ZERO, // No fee
                            "strict_lock_rollback",
                            "Rollback due to strict lock expiration"
                    );
                    transactionLog.setCreatedAt(now);

                    transactionLogRepository.save(transactionLog);
                }
            }
        }

        if (totalRollbackAmount.compareTo(BigDecimal.ZERO) > 0) {
            // TODO: Integrate with Paystack/Flutterwave to credit 'totalRollbackAmount' to user's Wallet
            // - Credit wallet: gateway.creditToWallet(user.getWalletId(), totalRollbackAmount)
            System.out.println("Simulating credit of ₦" + totalRollbackAmount + " from strict_lock envelopes to user's Wallet for Budget ID: " + budgetId);
        }
    }


//    // GET DASHBOARD DETAILS
//    public Map<String, Object> getDashboard(String email) {
//        User user = userService.findByEmail(email);
//        String greeting = getTimeBasedGreeting(user.getEmail().split("@")[0]);
//
//        List<Budget> budgets = budgetRepository.findByUserId(user.getId());
//        Map<String, List<BudgetResponse>> budgetMap = new HashMap<>();
//        budgetMap.put("active", new ArrayList<>());
//        budgetMap.put("completed", new ArrayList<>());
//
//        LocalDate today = LocalDate.now();
//        for (Budget budget : budgets) {
//            BigDecimal remaining = budget.getEnvelopes().stream()
//                    .map(Envelope::getRemainingAmount)
//                    .reduce(BigDecimal.ZERO, BigDecimal::add);
//            BudgetResponse response = new BudgetResponse(
//                    budget.getId(),
//                    budget.getName(),
//                    budget.getTotalAmount(),
//                    remaining,
//                    (int) ChronoUnit.DAYS.between(budget.getStartDate(), budget.getEndDate()),
//                    budget.getStartDate(),
//                    budget.getEndDate(),
//                    budget.getStatus(),
//                    budget.getCreatedAt(),
//                    user.getId(),
//                    budget.getLastTopupTime(),
//                    budget.getEnvelopes().stream()
//                            .map(e -> new EnvelopeResponse(
//                                    e.getId(),
//                                    budget.getId(),
//                                    e.getName(),
//                                    e.getRemainingAmount(),
//                                    e.getAmount(),
//                                    e.getConditions(),
//                                    e.getCreatedAt()
//                            ))
//                            .collect(Collectors.toList())
//            );
//            if (budget.getStatus() == BudgetStatus.ACTIVE && budget.getEndDate().isAfter(today)) {
//                budgetMap.get("active").add(response);
//            } else {
//                budgetMap.get("completed").add(response);
//            }
//        }
//
//        budgetMap.get("active").sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
//        budgetMap.get("completed").sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
//
//        return Map.of(
//                "greeting", greeting,
//                "budgets", budgetMap
//        );
//    }

//    private String getTimeBasedGreeting(String name) {
//        int hour = LocalDateTime.now().getHour();
//        if (hour < 12) return "Good Morning, " + name + "!";
//        if (hour < 17) return "Good Afternoon, " + name + "!";
//        return "Good Evening, " + name + "!";
//    }


    // Placeholder for getTimeBasedGreeting
    private String getTimeBasedGreeting(String name) {
        int hour = LocalDateTime.now().getHour();
        String timeOfDay = hour < 12 ? "Morning" : hour < 17 ? "Afternoon" : "Evening";
        return String.format("Good %s, %s!", timeOfDay, name);
    }

    public Map<String, Object> getDashboard(String email) {
        User user = userService.findByEmail(email);
        // Use name from profile_data, fallback to email prefix
        String name = user.getName() != null ? user.getName() : user.getEmail().split("@")[0];
        String greeting = getTimeBasedGreeting(name);

        List<Budget> budgets = budgetRepository.findByUserId(user.getId());
        Map<String, List<BudgetResponse>> budgetMap = new HashMap<>();
        budgetMap.put("active", new ArrayList<>());
        budgetMap.put("completed", new ArrayList<>());

        LocalDate today = LocalDate.now();
        for (Budget budget : budgets) {
            BigDecimal remaining = budget.getEnvelopes().stream()
                    .map(Envelope::getRemainingAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BudgetResponse response = new BudgetResponse(
                    budget.getId(),
                    budget.getName(),
                    budget.getTotalAmount(),
                    budget.getAllocatedAmount(),
                    remaining,
                    Integer.valueOf((int) ChronoUnit.DAYS.between(budget.getStartDate(), budget.getEndDate())),
                    budget.getStartDate(),
                    budget.getEndDate(),
                    budget.getStatus(),
                    budget.getCreatedAt(),
                    user.getId(),
                    budget.getLastTopupTime(),
                    budget.getEnvelopes().stream()
                            .map(e -> new EnvelopeResponse(
                                    e.getId(),
                                    budget.getId(),
                                    e.getName(),
                                    e.getAmount(), // Fixed: Initial amount
                                    e.getRemainingAmount(),
                                    e.getConditions(),
                                    e.getCreatedAt()
                            ))
                            .collect(Collectors.toList())
            );
            if (budget.getStatus() == BudgetStatus.ACTIVE && budget.getEndDate().isAfter(today)) {
                budgetMap.get("active").add(response);
            } else {
                budgetMap.get("completed").add(response);
            }
        }

        budgetMap.get("active").sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        budgetMap.get("completed").sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        return Map.of(
                "greeting", greeting,
                "budgets", budgetMap
        );
    }

    private void creditRevenueAccount(BigDecimal amount, String description) {
        logger.info("Mock: Credited revenue account with ₦{} for {}", amount, description);
    }

    @Transactional
    public EnvelopeResponse spendEnvelope(SpendEnvelopeRequest request, String email) {
        User user = userService.findByEmail(email);
        Envelope envelope = envelopeRepository.findById(request.getEnvelopeId())
                .orElseThrow(() -> new IllegalArgumentException("Envelope not found: " + request.getEnvelopeId()));
        Budget budget = budgetRepository.findById(envelope.getBudget().getId())
                .orElseThrow(() -> new IllegalArgumentException("Budget not found"));

        // Verify ownership
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Unauthorized access to envelope");
        }

        BigDecimal amount = request.getAmount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Spend amount must be positive");
        }

        BigDecimal fee = BigDecimal.ZERO;
        Map<String, Object> conditions = envelope.getConditions();
        String conditionType = (String) conditions.get("type");

        // Validate conditions
        LocalDateTime now = LocalDateTime.now();
        switch (conditionType) {
            case "emergency":
                Boolean used = (Boolean) conditions.getOrDefault("used", false);
                if (used) {
                    notificationService.sendNotification(user.getId().toString(), "Emergency funds already used!");
                    throw new IllegalArgumentException("Emergency funds can only be used once");
                }
                if (envelope.getRemainingAmount().compareTo(amount) < 0) {
                    String message = String.format("Insufficient emergency funds: ₦%.2f needed, ₦%.2f available", amount, envelope.getRemainingAmount());
                    notificationService.sendNotification(user.getId().toString(), message);
                    throw new IllegalArgumentException(message);
                }
                fee = amount.multiply(new BigDecimal("0.05")); // 5% fee
                conditions.put("used", true);
                break;

            case "daily":
                BigDecimal dailyLimit = new BigDecimal(conditions.get("limit").toString());
                LocalDateTime lastAccessed = envelope.getLastAccessed() != null ? envelope.getLastAccessed() : LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
                if (lastAccessed.toLocalDate().equals(now.toLocalDate())) {
                    notificationService.sendNotification(user.getId().toString(), "Daily limit already used today!");
                    throw new IllegalArgumentException("Daily limit already used today");
                }
                if (amount.compareTo(dailyLimit) > 0) {
                    String message = String.format("Amount exceeds daily limit: ₦%.2f requested, ₦%.2f allowed", amount, dailyLimit);
                    notificationService.sendNotification(user.getId().toString(), message);
                    throw new IllegalArgumentException(message);
                }
                if (envelope.getRemainingAmount().compareTo(amount) < 0) {
                    String message = String.format("Insufficient funds: ₦%.2f needed, ₦%.2f available", amount, envelope.getRemainingAmount());
                    notificationService.sendNotification(user.getId().toString(), message);
                    throw new IllegalArgumentException(message);
                }
                break;

            case "weekly":
                BigDecimal weeklyLimit = new BigDecimal(conditions.get("limit").toString());
                LocalDateTime weekStart = now.minusDays(now.getDayOfWeek().getValue() - 1);
                if (envelope.getLastAccessed() != null && envelope.getLastAccessed().isAfter(weekStart)) {
                    notificationService.sendNotification(user.getId().toString(), "Weekly limit already used this week!");
                    throw new IllegalArgumentException("Weekly limit already used this week");
                }
                if (amount.compareTo(weeklyLimit) > 0) {
                    String message = String.format("Amount exceeds weekly limit: ₦%.2f requested, ₦%.2f allowed", amount, weeklyLimit);
                    notificationService.sendNotification(user.getId().toString(), message);
                    throw new IllegalArgumentException(message);
                }
                if (envelope.getRemainingAmount().compareTo(amount) < 0) {
                    String message = String.format("Insufficient funds: ₦%.2f needed, ₦%.2f available", amount, envelope.getRemainingAmount());
                    notificationService.sendNotification(user.getId().toString(), message);
                    throw new IllegalArgumentException(message);
                }
                break;

            default:
                throw new IllegalArgumentException("Spending not supported for condition: " + conditionType);
        }

        // Deduct amount + fee
        BigDecimal totalDeduction = amount.add(fee);
        if (envelope.getRemainingAmount().compareTo(totalDeduction) < 0) {
            String message = String.format("Insufficient funds including fee: ₦%.2f needed, ₦%.2f available", totalDeduction, envelope.getRemainingAmount());
            notificationService.sendNotification(user.getId().toString(), message);
            throw new IllegalArgumentException(message);
        }

        envelope.setRemainingAmount(envelope.getRemainingAmount().subtract(totalDeduction));
        envelope.setLastAccessed(now);
        envelope.setConditions(conditions); // Update emergency.used
        envelopeRepository.save(envelope);

        // Log spend
        TransactionLog spendLog = new TransactionLog();
        spendLog.setUserId(user.getId());
        spendLog.setBudgetId(budget.getId());
        spendLog.setSourceEnvelopeId(envelope.getId());
        spendLog.setAmount(amount);
        spendLog.setFee(fee);
        spendLog.setTransactionType("envelope_spend");
        spendLog.setCreatedAt(now);
        transactionLogRepository.save(spendLog);

        // Log fee (if any)
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            TransactionLog feeLog = new TransactionLog();
            feeLog.setUserId(user.getId());
            feeLog.setBudgetId(budget.getId());
            feeLog.setSourceEnvelopeId(envelope.getId());
            feeLog.setAmount(fee);
            feeLog.setFee(BigDecimal.ZERO);
            feeLog.setTransactionType("spend_fee");
            feeLog.setCreatedAt(now);
            transactionLogRepository.save(feeLog);

            RevenueLog revenueLog = new RevenueLog();
            revenueLog.setUserId(user.getId());
            revenueLog.setType("spend_fee");
            revenueLog.setAmount(fee);
            revenueLog.setDescription("Emergency withdrawal fee for envelope " + envelope.getName());
            revenueLog.setCreatedAt(now);
            revenueLogRepository.save(revenueLog);
        }

        // Notify
        String message = String.format("₦%.2f withdrawn from %s envelope! %s", amount, envelope.getName(),
                fee.compareTo(BigDecimal.ZERO) > 0 ? String.format("₦%.2f fee applied.", fee) : "");
        notificationService.sendNotification(user.getId().toString(), message);

        return new EnvelopeResponse(
                envelope.getId(),
                budget.getId(),
                envelope.getName(),
                envelope.getRemainingAmount(),
                envelope.getRemainingAmount(),
                envelope.getConditions(),
                envelope.getCreatedAt()
        );
    }

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

        // Map Envelopes to EnvelopeResponse
        List<EnvelopeResponse> envelopeResponses = budget.getEnvelopes().stream()
                .map(envelope -> new EnvelopeResponse(
                        envelope.getId(),
                        envelope.getBudget().getId(),
                        envelope.getName(),
                        envelope.getAmount(),
                        envelope.getRemainingAmount(),
                        envelope.getConditions(),
                        envelope.getCreatedAt()
                ))
                .collect(Collectors.toList());
        response.setEnvelopes(envelopeResponses);
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
                envelope.getConditions(),
                envelope.getCreatedAt()
        );
    }
}
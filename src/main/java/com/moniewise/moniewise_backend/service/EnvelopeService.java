package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.controller.BudgetController;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.exception.EntityNotFoundException;
import com.moniewise.moniewise_backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class EnvelopeService {

    private final EnvelopeRepository envelopeRepository;
    private final BudgetRepository budgetRepository;
    private final RevenueLogRepository revenueLogRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionLogRepository transactionLogRepository;
    private final NotificationService notificationService;
    private final WalletService walletService;

    private final ScheduledTaskRepository scheduledTaskRepository;


    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    public EnvelopeService(
            EnvelopeRepository envelopeRepository,
            BudgetRepository budgetRepository,
            RevenueLogRepository revenueLogRepository,
            UserService userService,
            TransactionLogRepository transactionLogRepository,
            NotificationService notificationService,
            WalletService walletService, ScheduledTaskRepository scheduledTaskRepository) {
        this.envelopeRepository = envelopeRepository;
        this.budgetRepository = budgetRepository;
        this.revenueLogRepository = revenueLogRepository;
        this.userService = userService;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.walletService = walletService; // Assign walletService1 as in original
        this.scheduledTaskRepository = scheduledTaskRepository;
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;


    private static final Logger logger = LoggerFactory.getLogger(BudgetService.class);

    @PostConstruct
    public void init() {
        System.out.println("Revenue Wallet User ID: " + revenueWalletUserId);
    }

    private LocalDateTime fetchCurrentDateTimeFromDatabase() {
        String sql = "SELECT NOW()";
        return jdbcTemplate.queryForObject(sql, LocalDateTime.class);
    }

    private void creditRevenueAccount(BigDecimal amount, String description) {
        logger.info("Mock: Credited revenue account with ₦{} for {}", amount, description);
    }

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


    // Create envelope
    @Transactional
    public EnvelopeResponse createEnvelope(EnvelopeRequest request, String email) {
        Budget budget = budgetRepository.findByUserEmailAndStatus(email, BudgetStatus.DRAFT)
                .orElseThrow(() -> new IllegalArgumentException("No draft budget found for user"));

        BigDecimal amount = budget.getTotalAmount()
                .multiply(request.getPercentage())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        Envelope envelope = new Envelope(budget, request.getName(), amount, request.getConditions());
        envelope.setRemainingAmount(amount);
        envelope.setCreatedAt(fetchCurrentDateTimeFromDatabase());

        envelopeRepository.save(envelope);

        Map<String, Object> conditions = request.getConditions();
        if (conditions != null && "dynamic".equals(conditions.get("type"))) {
            String startDate = (String) conditions.get("startDate");
            int intervalDays = Integer.parseInt(conditions.get("intervalDays").toString());
            String disbursementTime = (String) conditions.get("disbursementTime");

            LocalDate dynamicStart = LocalDate.parse(startDate);
            LocalTime time = LocalTime.parse(disbursementTime);
            LocalDateTime now = fetchCurrentDateTimeFromDatabase();

            for (int i = 0; i < 30; i++) {
                LocalDateTime triggerTime = dynamicStart.atTime(time).plusDays(i * intervalDays);
                if (triggerTime.isAfter(now)) {
                    ScheduledTask task = new ScheduledTask();
                    task.setEnvelopeId(envelope.getId());
                    task.setTaskType("disbursement");
                    task.setTriggerTime(triggerTime);
                    task.setCreatedAt(now);
                    scheduledTaskRepository.save(task);
                }
            }
        }

        return toResponse(envelope);
    }


    // Get envelope
    public EnvelopeResponse getEnvelopeById(Long envelopeId, String email) {
        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible"));
        return toResponse(envelope);
    }

    // Update envelope conditions only
    public EnvelopeResponse updateEnvelopeConditions(Long envelopeId, EnvelopeRequest request, String email) {
        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible"));

        // Only update conditions
        envelope.setConditions(request.getConditions());

        envelopeRepository.save(envelope);
        return toResponse(envelope);
    }

    // Delete envelope
    public void deleteEnvelope(Long envelopeId, String email) {
        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible"));

        if (envelope.getBudget().getStatus() == BudgetStatus.ACTIVE) {
            throw new IllegalStateException("Cannot delete envelope from an active budget");
        }

        envelopeRepository.delete(envelope);
    }

    // Helper: convert entity → response DTO
    private EnvelopeResponse toResponse(Envelope envelope) {
        return new EnvelopeResponse(
                envelope.getId(),
                envelope.getBudget().getId(),
                envelope.getName(),
                envelope.getAmount(),
                envelope.getRemainingAmount(),
                envelope.getConditions(),
                envelope.getCreatedAt(),
                envelope.getLastDisbursedAt()
        );
    }

    // Helper method to map entity -> DT
}

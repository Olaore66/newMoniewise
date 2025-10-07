package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.config.BudgetLifeCycleManager;
import com.moniewise.moniewise_backend.controller.BudgetController;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.Status;
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
import java.time.format.DateTimeParseException;
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
    private final BudgetLifeCycleManager budgetLifeCycleManager;
    private final PendingDisbursementRepository pendingDisbursementRepository;

    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    public EnvelopeService(
            EnvelopeRepository envelopeRepository,
            BudgetRepository budgetRepository,
            RevenueLogRepository revenueLogRepository,
            UserService userService,
            TransactionLogRepository transactionLogRepository,
            NotificationService notificationService,
            WalletService walletService, ScheduledTaskRepository scheduledTaskRepository, BudgetLifeCycleManager budgetLifeCycleManager, PendingDisbursementRepository pendingDisbursementRepository) {
        this.envelopeRepository = envelopeRepository;
        this.budgetRepository = budgetRepository;
        this.revenueLogRepository = revenueLogRepository;
        this.userService = userService;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.walletService = walletService; // Assign walletService1 as in original
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.budgetLifeCycleManager = budgetLifeCycleManager;
        this.pendingDisbursementRepository = pendingDisbursementRepository;
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // In EnvelopeService.java, update line 64:
    private static final Logger logger = LoggerFactory.getLogger(EnvelopeService.class);
    @PostConstruct
    public void init() {
        System.out.println("Revenue Wallet User ID: " + revenueWalletUserId);
    }

    private LocalDateTime fetchCurrentDateTimeFromDatabase() {
        String sql = "SELECT CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Lagos'";
        return jdbcTemplate.queryForObject(sql, LocalDateTime.class);
    }

    private void creditRevenueAccount(BigDecimal amount, String description) {
        logger.info("Mock: Credited revenue account with ₦{} for {}", amount, description);
    }

    @Transactional
    public void moveMoney(Long sourceId, Long targetId, Double amount, String email) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        User user = userService.findByEmail(email);
        Envelope source = envelopeRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Source envelope not found with ID: " + sourceId));
        Envelope target = envelopeRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("Target envelope not found with ID: " + targetId));
        Budget sourceBudget = source.getBudget();
        if (!sourceBudget.getUser().getId().equals(user.getId()) || !target.getBudget().getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to move money from/to these envelopes");
        }
        if (!sourceBudget.getId().equals(target.getBudget().getId())) {
            throw new IllegalArgumentException("Source and target envelopes must belong to the same budget");
        }
        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Budget must be active to perform transactions");
        }
        BigDecimal transferAmount = BigDecimal.valueOf(amount);
        if (transferAmount.compareTo(source.getRemainingAmount()) > 0) {
            throw new IllegalArgumentException("Insufficient funds in source envelope. Available: ₦" + source.getRemainingAmount());
        }

        BigDecimal feePercentage = validateAndCalculateFee(source, sourceBudget, transferAmount, now, "envelope_transfer", email, targetId, null, transactionLogRepository);
        BigDecimal fee = transferAmount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal amountAfterFee = transferAmount.subtract(fee);

        source.setRemainingAmount(source.getRemainingAmount().subtract(transferAmount));
        target.setRemainingAmount(target.getRemainingAmount().add(amountAfterFee));
        envelopeRepository.save(source);
        envelopeRepository.save(target);

        TransactionLog transactionLog = new TransactionLog(
                user.getId(), sourceBudget.getId(), sourceId, targetId, null,
                transferAmount, fee, "envelope_to_envelope", null);
        transactionLog.setCreatedAt(now);
        transactionLogRepository.save(transactionLog);

        String revenueDescription = String.format("Transfer in Budget %d from %s to %s (fee: %s%%)",
                sourceBudget.getId(), source.getName(), target.getName(), feePercentage);
        RevenueLog revenueLog = new RevenueLog(user.getId(), "envelope_transfer_fee", fee, revenueDescription);
        revenueLog.setCreatedAt(now);
        revenueLogRepository.save(revenueLog);
        creditRevenueAccount(fee, revenueDescription);
    }
    // New Transfer to external account code --- 08/06/2025

    // Update transferToExternal:
    @Transactional
    public void transferToExternal(Long sourceId, BudgetController.ExternalAccount externalAccount, Double amount, String email) {
        Logger logger = LoggerFactory.getLogger(EnvelopeService.class);
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        logger.debug("Transferring ₦{} to external account {} from envelope {} for user {}", amount, externalAccount, sourceId, email);
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        User user = userService.findByEmail(email);
        Envelope source = envelopeRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Source envelope not found with ID: " + sourceId));
        Budget sourceBudget = source.getBudget();
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

        BigDecimal feePercentage = validateAndCalculateFee(source, sourceBudget, transferAmount, now, "external_transfer", email, null, externalAccount.getAccountNumber(), transactionLogRepository);
        BigDecimal fee = transferAmount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal amountAfterFee = transferAmount.subtract(fee);

        source.setRemainingAmount(source.getRemainingAmount().subtract(transferAmount));
        envelopeRepository.save(source);

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
    // In EnvelopeService.java, replace lines 270–317 with:
    @Transactional
    public EnvelopeResponse createEnvelope(EnvelopeRequest request, String email) {
        Budget budget = budgetRepository.findById(request.getBudgetId())
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + request.getBudgetId()));
        if (!budget.getUser().getEmail().equals(email)) {
            throw new SecurityException("Unauthorized access to budget");
        }
        if (budget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Can only add envelopes to draft budgets");
        }

        BigDecimal amount = budget.getTotalAmount()
                .multiply(request.getPercentage())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        validateEnvelopeConditions(request, amount);

        Map<String, Object> conditions = request.getConditions();
        if (conditions != null && conditions.containsKey("type")) {
            String type = (String) conditions.get("type");
            switch (type) {
                case "daily":
                    conditions.putIfAbsent("gracePeriodMinutes", 10);
                    break;
                case "weekly":
                    conditions.putIfAbsent("gracePeriodMinutes", 30);
                    break;
                case "dynamic":
                    conditions.putIfAbsent("gracePeriodMinutes", 60);
                    break;
                case "safe_lock":
                case "strict_lock":
                    conditions.putIfAbsent("gracePeriodMinutes", 1440);
                    break;
                case "emergency":
                    conditions.putIfAbsent("gracePeriodMinutes", 0);
                    break;
            }
        }

        Envelope envelope = new Envelope(budget, request.getName(), amount, conditions);
        envelope.setRemainingAmount(amount);
        envelope.setCreatedAt(fetchCurrentDateTimeFromDatabase());
        envelope.setNextDisbursementAt(budgetLifeCycleManager.calculateNextDisbursementTime(envelope));
        envelope.setHasMatured(false);
        envelopeRepository.save(envelope);

        budgetLifeCycleManager.scheduleDynamicTasks(envelope);

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

    @Transactional
    public void claimDisbursement(Long pendingDisbursementId, String email) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        PendingDisbursement pd = pendingDisbursementRepository.findById(pendingDisbursementId)
                .orElseThrow(() -> new IllegalArgumentException("Pending disbursement not found"));
        if (!pd.getStatus().equals(Status.PENDING) || now.isAfter(pd.getExpiresAt())) {
            throw new IllegalStateException("Disbursement is no longer available");
        }
        Envelope envelope = envelopeRepository.findById(pd.getEnvelopeId())
                .orElseThrow(() -> new IllegalArgumentException("Envelope not found"));
        User user = userService.findByEmail(email);
        if (!envelope.getBudget().getUser().getId().equals(user.getId())) {
            throw new SecurityException("Unauthorized access to disbursement");
        }

        walletService.fundWallet(user.getId(), pd.getAmount(), "Disbursement from envelope: " + pd.getEnvelopeName());
        pd.setStatus(Status.CLAIMED);
        pd.setWithdrawn(true);
        pd.setProcessedAt(now);
        pendingDisbursementRepository.save(pd);

        TransactionLog log = new TransactionLog(
                user.getId(), envelope.getBudget().getId(), envelope.getId(), null, null,
                pd.getAmount(), BigDecimal.ZERO, "envelope_disbursement", "Claimed pending disbursement"
        );
        log.setCreatedAt(now);
        transactionLogRepository.save(log);

        notificationService.sendNotification(
                user.getId().toString(),
                String.format("You have successfully claimed ₦%.2f from '%s'.", pd.getAmount(), pd.getEnvelopeName()),
                (NotificationType.DISBURSEMENT)
        );
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
                envelope.getLastDisbursedAt(),
                envelope.getNextDisbursementAt()
        );
    }

    // In EnvelopeService
    public void validateEnvelopeConditions(EnvelopeRequest request, BigDecimal allocatedAmount) {
        Map<String, Object> conditions = request.getConditions();
        if (conditions == null || !conditions.containsKey("type")) {
            throw new IllegalArgumentException("Envelope conditions must include 'type'");
        }
        String type = conditions.get("type").toString().toLowerCase();
        if ("daily".equals(type)) {
            if (conditions.containsKey("disbursementTime")) {
                try {
                    LocalTime.parse((String) conditions.get("disbursementTime"));
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid disbursementTime format for daily envelope: " + conditions.get("disbursementTime"));
                }
            }
        }else if ("dynamic".equals(type)) {
            List<String> days = (List<String>) conditions.get("days");
            String disbursementTime = (String) conditions.get("disbursementTime");
            if (days == null || days.isEmpty()) {
                throw new IllegalArgumentException("Dynamic envelope must have non-empty 'days'");
            }
            try {
                LocalTime.parse(disbursementTime);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid disbursementTime format: " + disbursementTime);
            }
            // ... additional validation ...
        }
        // ... validate other types ...
    }

    // In EnvelopeService.java, add:
    private BigDecimal validateAndCalculateFee(Envelope source, Budget sourceBudget, BigDecimal transferAmount,
                                         LocalDateTime now, String transactionType, String email,
                                         Long targetId, String externalAccountNumber, TransactionLogRepository transactionLogRepository) {
        Map<String, Object> conditions = source.getConditions();
        String sourceConditionType = (String) conditions.get("type");
        if (sourceConditionType == null) {
            throw new IllegalArgumentException("Envelope condition type is missing");
        }
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
                Double dailyLimit = Double.parseDouble(conditions.get("limit").toString());
                BigDecimal remainingDaily = BigDecimal.valueOf(dailyLimit).subtract(
                        transactionLogRepository.findBySourceEnvelopeIdAndTimeRange(source.getId(), periodStart, periodEnd)
                                .stream()
                                .filter(t -> "envelope_to_envelope".equals(t.getTransactionType()) || "envelope_to_external".equals(t.getTransactionType()))
                                .map(TransactionLog::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
                if (remainingDaily.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Daily limit of ₦" + dailyLimit + " exhausted until " + periodEnd);
                }
                if (transferAmount.compareTo(remainingDaily) > 0) {
                    throw new IllegalArgumentException("Transfer exceeds remaining daily limit of ₦" + remainingDaily);
                }
                feePercentage = new BigDecimal("2");
                break;
            case "weekly":
                periodStart = currentDate.atStartOfDay().minusDays(currentDate.getDayOfWeek().getValue() - 1);
                periodEnd = periodStart.plusDays(7);
                Double weeklyLimit = Double.parseDouble(conditions.get("limit").toString());
                BigDecimal remainingWeekly = BigDecimal.valueOf(weeklyLimit).subtract(
                        transactionLogRepository.findBySourceEnvelopeIdAndTimeRange(source.getId(), periodStart, periodEnd)
                                .stream()
                                .filter(t -> "envelope_to_envelope".equals(t.getTransactionType()) || "envelope_to_external".equals(t.getTransactionType()))
                                .map(TransactionLog::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
                if (remainingWeekly.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Weekly limit of ₦" + weeklyLimit + " exhausted until " + periodEnd);
                }
                if (transferAmount.compareTo(remainingWeekly) > 0) {
                    throw new IllegalArgumentException("Transfer exceeds remaining weekly limit of ₦" + remainingWeekly);
                }
                feePercentage = new BigDecimal("2");
                break;
            case "dynamic":
                LocalDate budgetStartDate = sourceBudget.getStartDate();
                LocalDate budgetEndDate = sourceBudget.getEndDate();
                if (budgetStartDate == null || budgetEndDate == null) {
                    throw new IllegalArgumentException("Budget startDate or endDate is missing");
                }
                @SuppressWarnings("unchecked")
                List<String> days = (List<String>) conditions.getOrDefault("days", List.of());
                String disbursementTimeStr = (String) conditions.getOrDefault("disbursementTime", "08:00");
                Double dynamicLimit;
                try {
                    dynamicLimit = Double.parseDouble(conditions.get("limit").toString());
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid dynamic limit: " + conditions.get("limit"), e);
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
                    allowedDays = days.stream().map(day -> DayOfWeek.valueOf(day.toUpperCase())).collect(Collectors.toList());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid day in dynamic condition: " + e.getMessage(), e);
                }
                periodStart = currentDate.atStartOfDay();
                periodEnd = periodStart.plusDays(1);
                if (currentDate.isBefore(budgetStartDate) || currentDate.isAfter(budgetEndDate)) {
                    LocalDateTime nextValidWindow = budgetLifeCycleManager.findNextValidWindow(
                            currentDate, budgetStartDate, budgetEndDate, allowedDays, disbursementTime);
                    isTransferAllowed = false;
                    String reason = currentDate.isBefore(budgetStartDate) ?
                            "Outside budget period: currentDate=" + currentDate + ", startDate=" + budgetStartDate :
                            "Budget period has ended on " + budgetEndDate;
                    TransactionLog transactionLog = new TransactionLog(
                            sourceBudget.getUser().getId(), sourceBudget.getId(), source.getId(), targetId, externalAccountNumber,
                            transferAmount, BigDecimal.ZERO, "failed_" + transactionType, reason);
                    transactionLog.setCreatedAt(now);
                    transactionLogRepository.save(transactionLog);
                    throw new IllegalArgumentException(
                            String.format("Transfer only allowed on %s at %s with limit ₦%.2f (next window: %s)",
                                    days, disbursementTime, dynamicLimit, nextValidWindow != null ? nextValidWindow : "none"));
                }
                DayOfWeek currentDayOfWeek = currentDate.getDayOfWeek();
                if (!allowedDays.contains(currentDayOfWeek)) {
                    LocalDateTime nextValidWindow = budgetLifeCycleManager.findNextValidWindow(
                            currentDate, budgetStartDate, budgetEndDate, allowedDays, disbursementTime);
                    isTransferAllowed = false;
                    String reason = "Invalid day: " + currentDayOfWeek;
                    TransactionLog transactionLog = new TransactionLog(
                            sourceBudget.getUser().getId(), sourceBudget.getId(), source.getId(), targetId, externalAccountNumber,
                            transferAmount, BigDecimal.ZERO, "failed_" + transactionType, reason);
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
                            budgetLifeCycleManager.findNextValidWindow(currentDate, budgetStartDate, budgetEndDate, allowedDays, disbursementTime);
                    isTransferAllowed = false;
                    String reason = "Outside time window: currentTime=" + currentTime + ", validWindow=" + validStartTime + " to " + validEndTime;
                    TransactionLog transactionLog = new TransactionLog(
                            sourceBudget.getUser().getId(), sourceBudget.getId(), source.getId(), targetId, externalAccountNumber,
                            transferAmount, BigDecimal.ZERO, "failed_" + transactionType, reason);
                    transactionLog.setCreatedAt(now);
                    transactionLogRepository.save(transactionLog);
                    throw new IllegalArgumentException(
                            String.format("Transfer only allowed on %s at %s with limit ₦%.2f (next window: %s)",
                                    days, disbursementTime, dynamicLimit, nextValidWindow != null ? nextValidWindow : "none"));
                }
                BigDecimal remainingDynamic = BigDecimal.valueOf(dynamicLimit).subtract(
                        transactionLogRepository.findBySourceEnvelopeIdAndTimeRange(source.getId(), periodStart, periodEnd)
                                .stream()
                                .filter(t -> "envelope_to_envelope".equals(t.getTransactionType()) || "envelope_to_external".equals(t.getTransactionType()))
                                .map(TransactionLog::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
                if (remainingDynamic.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Dynamic limit of ₦" + dynamicLimit + " exhausted until " + periodEnd);
                }
                if (transferAmount.compareTo(remainingDynamic) > 0) {
                    throw new IllegalArgumentException("Transfer exceeds remaining dynamic limit of ₦" + remainingDynamic);
                }
                feePercentage = new BigDecimal("2");
                break;
            case "emergency":
                periodStart = currentDate.atStartOfDay();
                periodEnd = sourceBudget.getEndDate().atTime(23, 59, 59);
                feePercentage = currentDate.isBefore(sourceBudget.getEndDate()) ? new BigDecimal("10") : BigDecimal.ZERO;
                conditions.put("used", true);
                source.setConditions(conditions);
                envelopeRepository.save(source);
                break;
            case "safe_lock":
                feePercentage = new BigDecimal("1");
                periodStart = now.minusYears(1);
                periodEnd = now.plusYears(1);
                break;
            case "strict_lock":
                String reason = "Strict lock envelope transfers not allowed; funds roll back after budget endDate: " + sourceBudget.getEndDate();
                logger.warn("Attempted transfer from strict_lock envelope {} by user {}: {}", source.getId(), email, reason);
                TransactionLog transactionLog = new TransactionLog(
                        sourceBudget.getUser().getId(), sourceBudget.getId(), source.getId(), targetId, externalAccountNumber,
                        transferAmount, BigDecimal.ZERO, "failed_" + transactionType, reason);
                transactionLog.setCreatedAt(now);
                transactionLogRepository.save(transactionLog);
                throw new IllegalArgumentException(reason);
            default:
                periodStart = now.minusYears(1);
                periodEnd = now.plusYears(1);
        }

        if (!isTransferAllowed) {
            throw new IllegalArgumentException("Transfer not allowed due to source envelope conditions");
        }

        // Set fee as an instance variable or return it if needed
//        source.setFeePercentage(feePercentage); // Assuming Envelope has a transient field for feePercentage
        return feePercentage;
    }

}



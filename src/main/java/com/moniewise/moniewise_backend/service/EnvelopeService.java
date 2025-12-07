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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
            JdbcTemplate jdbcTemplate) {
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
    }

    @PostConstruct
    public void init() {
        logger.info("Revenue Wallet User ID: {}", revenueWalletUserId);
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
        Envelope source = envelopeRepository.findByIdAndBudget_UserEmail(sourceId, email)
                .orElseThrow(() -> new EntityNotFoundException("Source envelope not found with ID: " + sourceId));
        Envelope target = envelopeRepository.findByIdAndBudget_UserEmail(targetId, email)
                .orElseThrow(() -> new EntityNotFoundException("Target envelope not found with ID: " + targetId));
        Budget sourceBudget = source.getBudget();
        if (!sourceBudget.getId().equals(target.getBudget().getId())) {
            throw new IllegalArgumentException("Source and target envelopes must belong to the same budget");
        }
        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Budget must be active to perform transactions");
        }
        BigDecimal transferAmount = BigDecimal.valueOf(amount);
        if (transferAmount.compareTo(source.getTotalRemainingAmount()) > 0) {
            throw new IllegalArgumentException("Insufficient funds in source envelope. Available: ₦" + source.getTotalRemainingAmount());
        }

        // CHARGE TO MOVE FROM BETWEEN ENVELOPES
//        BigDecimal feePercentage = validateAndCalculateFee(source, sourceBudget, transferAmount, now, "envelope_transfer", email, targetId, null);
//        BigDecimal fee = transferAmount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
//        BigDecimal amountAfterFee = transferAmount.subtract(fee);

        source.setTotalRemainingAmount(source.getTotalRemainingAmount().subtract(transferAmount));
        source.setRemainingAmount(getRemainingLimit(sourceId, email).subtract(transferAmount));
        target.setTotalRemainingAmount(target.getTotalRemainingAmount().add(transferAmount));
        envelopeRepository.saveAll(List.of(source, target));

        BigDecimal newBudgetRemaining = envelopeRepository.findByBudgetId(sourceBudget.getId())
                .stream()
                .map(Envelope::getTotalRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        sourceBudget.setRemainingAmount(newBudgetRemaining);
        budgetRepository.save(sourceBudget);

        // Main title – instantly tells the user what happened
        String description = String.format("From %s → %s • Budget: %s • Moved ₦%.2f",
                source.getName(),
                target.getName(),
                sourceBudget.getName(),
                transferAmount
        );

        TransactionLog transactionLog = new TransactionLog(
                user.getId(),
                sourceBudget.getId(),
                sourceId,
                targetId,
                transferAmount,
                "envelope_to_envelope",
                description
        );
        transactionLog.setCreatedAt(now);
        transactionLogRepository.save(transactionLog);

//        String revenueDescription = String.format("Transfer in Budget %d from %s to %s (fee: %s%%)",
//                sourceBudget.getId(), source.getName(), target.getName());
//        RevenueLog revenueLog = new RevenueLog(
//                user.getId(),
//                "envelope_transfer_fee",
//                fee,
//                revenueDescription
//        );
//        revenueLog.setCreatedAt(now);
//
//        revenueLogRepository.save(revenueLog);
//        creditRevenueAccount(fee, revenueDescription);

        BigDecimal remainingLimit = getRemainingLimit(sourceId, email);
        String period = source.getConditions().getOrDefault("type", "period").toString().equals("daily") ? "today" :
                source.getConditions().getOrDefault("type", "period").toString().equals("weekly") ? "this week" : "this period";

        notificationService.sendNotification(
                user.getId().toString(),
                String.format(
                        "Moved ₦%.2f from '%s' to '%s' (Budget: %s, Fee: ₦%.2f). Remaining limit %s: ₦%.2f. Total remaining: ₦%.2f.",
                        source.getName(),
                        target.getName(),
                        sourceBudget.getName(),
                        period,
                        remainingLimit,
                        source.getTotalRemainingAmount()
                ),
                NotificationType.ENVELOPE_TRANSFER,
                sourceBudget.getId(),
                sourceId,
                "VIEW_ENVELOPE",
                String.format("/budgets/%d/envelopes/%d", sourceBudget.getId(), sourceId)
        );
    }

    @Transactional
    public void transferToExternal(Long sourceId, BudgetController.ExternalAccount externalAccount, Double amount, String email) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        User user = userService.findByEmail(email);
        Envelope source = envelopeRepository.findByIdAndBudget_UserEmail(sourceId, email)
                .orElseThrow(() -> new EntityNotFoundException("Source envelope not found with ID: " + sourceId));
        Budget sourceBudget = source.getBudget();
        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Budget must be active to perform transactions");
        }
        BigDecimal transferAmount = BigDecimal.valueOf(amount);
        if (transferAmount.compareTo(source.getTotalRemainingAmount()) > 0) {
            throw new IllegalArgumentException("Insufficient funds in source envelope. Available: ₦" + source.getTotalRemainingAmount());
        }

        BigDecimal feePercentage = validateAndCalculateFee(source, sourceBudget, transferAmount, now, "external_transfer", email, null, externalAccount.getAccountNumber());
        BigDecimal fee = transferAmount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal amountAfterFee = transferAmount.subtract(fee);

        source.setTotalRemainingAmount(source.getTotalRemainingAmount().subtract(transferAmount));
        source.setRemainingAmount(getRemainingLimit(sourceId, email).subtract(transferAmount));
        envelopeRepository.save(source);

        BigDecimal newBudgetRemaining = envelopeRepository.findByBudgetId(sourceBudget.getId())
                .stream()
                .map(Envelope::getTotalRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        sourceBudget.setRemainingAmount(newBudgetRemaining);
        budgetRepository.save(sourceBudget);

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

        BigDecimal remainingLimit = getRemainingLimit(sourceId, email);
        String period = source.getConditions().getOrDefault("type", "period").toString().equals("daily") ? "today" :
                source.getConditions().getOrDefault("type", "period").toString().equals("weekly") ? "this week" : "this period";

        notificationService.sendNotification(
                user.getId().toString(),
                String.format("Transferred ₦%.2f from '%s' (Budget: %s) to external account %s/%s (Fee: ₦%.2f). Remaining limit %s: ₦%.2f. Total remaining: ₦%.2f.",
                        amountAfterFee, source.getName(), sourceBudget.getName(), externalAccount.getBankName(), externalAccount.getAccountNumber(), fee, period, remainingLimit, source.getTotalRemainingAmount()),
                NotificationType.EXTERNAL_TRANSFER,
                sourceBudget.getId(),
                sourceId,
                "VIEW_ENVELOPE",
                String.format("/budgets/%d/envelopes/%d", sourceBudget.getId(), sourceId)
        );
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

//        BigDecimal amount = budget.getTotalAmount()
//                .multiply(request.getPercentage())
//                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        BigDecimal amount;

        // Use pre-calculated exact amount if provided (from createBudget), otherwise fall back to percentage
        if (request.getExactAmount() != null && request.getExactAmount().compareTo(BigDecimal.ZERO) > 0) {
            amount = request.getExactAmount();
        } else {
            amount = budget.getTotalAmount()
                    .multiply(request.getPercentage())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }

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
        envelope.setCreatedAt(fetchCurrentDateTimeFromDatabase());
        envelope.setNextDisbursementAt(budgetLifeCycleManager.calculateNextDisbursementTime(envelope));
        envelope.setHasMatured(false);
        envelopeRepository.save(envelope);

        budgetLifeCycleManager.scheduleDynamicTasks(envelope);

        notificationService.sendNotification(
                budget.getUser().getId().toString(),
                String.format("Created envelope '%s' with ₦%.2f in budget '%s'.",
                        envelope.getName(), amount, budget.getName()),
                NotificationType.ENVELOPE_CREATED,
                budget.getId(),
                envelope.getId(),
                "VIEW_ENVELOPE",
                String.format("/budgets/%d/envelopes/%d", budget.getId(), envelope.getId())
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
                NotificationType.ENVELOPE_UPDATED,
                envelope.getBudget().getId(),
                envelopeId,
                "VIEW_ENVELOPE",
                String.format("/budgets/%d/envelopes/%d", envelope.getBudget().getId(), envelopeId)
        );

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

        BigDecimal remainingLimit = getRemainingLimit(envelope.getId(), email);
        if (pd.getAmount().compareTo(remainingLimit) > 0) {
            throw new IllegalStateException("Disbursement amount exceeds remaining limit of ₦" + remainingLimit);
        }
        if (pd.getAmount().compareTo(envelope.getTotalRemainingAmount()) > 0) {
            throw new IllegalStateException("Disbursement amount exceeds total remaining amount of ₦" + envelope.getTotalRemainingAmount());
        }

        walletService.fundWallet(user.getId(), pd.getAmount(), "Disbursement from envelope: " + pd.getEnvelopeName());
        envelope.setTotalRemainingAmount(envelope.getTotalRemainingAmount().subtract(pd.getAmount()));
        envelope.setRemainingAmount(remainingLimit.subtract(pd.getAmount()));
        envelope.setLastDisbursedAt(now);
        envelopeRepository.save(envelope);

        pd.setStatus(Status.CLAIMED);
        pd.setWithdrawn(true);
        pd.setProcessedAt(now);
        pendingDisbursementRepository.save(pd);

        BigDecimal newBudgetRemaining = envelopeRepository.findByBudgetId(envelope.getBudget().getId())
                .stream()
                .map(Envelope::getTotalRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        envelope.getBudget().setRemainingAmount(newBudgetRemaining);
        budgetRepository.save(envelope.getBudget());

        TransactionLog log = new TransactionLog(
                user.getId(), envelope.getBudget().getId(), envelope.getId(), null, null,
                pd.getAmount(), BigDecimal.ZERO, "envelope_disbursement", "Claimed pending disbursement"
        );
        log.setCreatedAt(now);
        transactionLogRepository.save(log);

        remainingLimit = getRemainingLimit(envelope.getId(), email); // Recalculate after transaction
        String period = envelope.getConditions().getOrDefault("type", "period").toString().equals("daily") ? "today" :
                envelope.getConditions().getOrDefault("type", "period").toString().equals("weekly") ? "this week" : "this period";

        notificationService.sendNotification(
                user.getId().toString(),
                String.format("Claimed ₦%.2f from '%s' (Budget: %s). Remaining limit %s: ₦%.2f. Total remaining: ₦%.2f.",
                        pd.getAmount(), pd.getEnvelopeName(), envelope.getBudget().getName(), period, remainingLimit, envelope.getTotalRemainingAmount()),
                NotificationType.DISBURSEMENT,
                envelope.getBudget().getId(),
                envelope.getId(),
                "VIEW_ENVELOPE",
                String.format("/budgets/%d/envelopes/%d", envelope.getBudget().getId(), envelope.getId())
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
                NotificationType.ENVELOPE_DELETED,
                envelope.getBudget().getId(),
                envelopeId,
                "VIEW_BUDGET",
                String.format("/budgets/%d", envelope.getBudget().getId())
        );
    }

    private EnvelopeResponse toResponse(Envelope envelope) {
        return new EnvelopeResponse(
                envelope.getId(),
                envelope.getBudget().getId(),
                envelope.getName(),

                // Legacy
                envelope.getAmount(),                    // amount
                envelope.getRemainingAmount(),           // remainingAmount

                // New
                envelope.getAmount(),                    // initialAmount
                envelope.getTotalRemainingAmount(),      // totalRemaining
                envelope.getRemainingAmount(),           // periodRemaining
                getPeriodLimit(envelope.getConditions()), // periodLimit
                budgetService.getUsedThisPeriod(envelope),             // usedThisPeriod

                envelope.getConditions(),
                envelope.getCreatedAt(),
                envelope.getLastDisbursedAt(),
                envelope.getNextDisbursementAt()
        );
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
                if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number)) {
                    throw new IllegalArgumentException("Emergency envelope must include a numeric 'limit'");
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported envelope type: " + type);
        }
    }


    private BigDecimal validateAndCalculateFee(Envelope source, Budget sourceBudget, BigDecimal transferAmount,
                                               LocalDateTime now, String transactionType, String email,
                                               Long targetId, String externalAccountNumber) {
        Map<String, Object> conditions = source.getConditions();
        if (conditions == null || !conditions.containsKey("type")) {
            throw new IllegalArgumentException("Envelope conditions must include 'type'");
        }
        String sourceConditionType = conditions.get("type").toString();
        boolean isTransferAllowed = true;
        BigDecimal feePercentage = BigDecimal.ZERO;

        LocalDate currentDate = now.toLocalDate();
        LocalDateTime periodStart;
        LocalDateTime periodEnd;

        switch (sourceConditionType) {
            case "daily":
                periodStart = currentDate.atStartOfDay();
                periodEnd = periodStart.plusDays(1);
                Double dailyLimit = Double.parseDouble(conditions.get("limit").toString());
                BigDecimal remainingDaily = source.getRemainingAmount();
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
                BigDecimal remainingWeekly = source.getRemainingAmount();
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
                Double dynamicLimit = Double.parseDouble(conditions.get("limit").toString());
                LocalTime disbursementTime;
                try {
                    disbursementTime = LocalTime.parse(disbursementTimeStr);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid disbursementTime format: " + disbursementTimeStr, e);
                }
                if (days.isEmpty()) {
                    throw new IllegalArgumentException("No days specified for dynamic condition");
                }
                List<DayOfWeek> allowedDays = days.stream().map(day -> DayOfWeek.valueOf(day.toUpperCase())).collect(Collectors.toList());
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
                    String reason = "Outside time window: currentTime=" + now.toLocalTime() + ", validWindow=" + validStartTime + " to " + validEndTime;
                    TransactionLog transactionLog = new TransactionLog(
                            sourceBudget.getUser().getId(), sourceBudget.getId(), source.getId(), targetId, externalAccountNumber,
                            transferAmount, BigDecimal.ZERO, "failed_" + transactionType, reason);
                    transactionLog.setCreatedAt(now);
                    transactionLogRepository.save(transactionLog);
                    throw new IllegalArgumentException(
                            String.format("Transfer only allowed on %s at %s with limit ₦%.2f (next window: %s)",
                                    days, disbursementTime, dynamicLimit, nextValidWindow != null ? nextValidWindow : "none"));
                }
                BigDecimal remainingDynamic = source.getRemainingAmount();
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
        return feePercentage;
    }

    private BigDecimal validateAndCalculateFee(Envelope source, Budget sourceBudget, BigDecimal transferAmount,
                                               LocalDateTime now, String transactionType, String email) {
        return validateAndCalculateFee(source, sourceBudget, transferAmount, now, transactionType, email, null, null);
    }

    public BigDecimal getRemainingLimit(Long envelopeId, String email) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible: " + envelopeId));
        Map<String, Object> conditions = envelope.getConditions();
        if (conditions == null || !conditions.containsKey("type") || !conditions.containsKey("limit")) {
            throw new IllegalArgumentException("Envelope conditions must include 'type' and 'limit'");
        }

        String type = conditions.get("type").toString();
        Object limitObj = conditions.get("limit");
        if (!(limitObj instanceof Number)) {
            throw new IllegalArgumentException("Invalid limit type for envelope " + envelopeId + ": " + limitObj);
        }
        BigDecimal limit = new BigDecimal(((Number) limitObj).doubleValue());
        LocalDateTime periodStart;
        LocalDateTime periodEnd;

        switch (type) {
            case "daily":
                periodStart = now.toLocalDate().atStartOfDay();
                periodEnd = periodStart.plusDays(1);
                break;
            case "weekly":
                LocalDate weekStart = now.toLocalDate().minusDays(now.toLocalDate().getDayOfWeek().getValue() - 1);
                periodStart = weekStart.atStartOfDay();
                periodEnd = periodStart.plusDays(7);
                break;
            case "dynamic":
                @SuppressWarnings("unchecked")
                List<String> days = (List<String>) conditions.getOrDefault("days", List.of());
                String disbursementTimeStr = (String) conditions.getOrDefault("disbursementTime", "08:00");
                LocalTime disbursementTime;
                try {
                    disbursementTime = LocalTime.parse(disbursementTimeStr);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid disbursementTime format: " + disbursementTimeStr);
                }
                if (!days.contains(now.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH))) {
                    return BigDecimal.ZERO;
                }
                periodStart = now.toLocalDate().atTime(disbursementTime);
                periodEnd = periodStart.plusHours(1);
                break;
            case "safe_lock":
            case "strict_lock":
            case "emergency":
                periodStart = now.minusYears(1);
                periodEnd = now.plusYears(1);
                return envelope.getRemainingAmount(); // Use stored value for these types
            default:
                throw new IllegalArgumentException("Unsupported envelope type: " + type);
        }

        BigDecimal spentAmount = transactionLogRepository.findBySourceEnvelopeIdAndTimeRange(envelopeId, periodStart, periodEnd)
                .stream()
                .filter(t -> List.of("envelope_to_envelope", "envelope_to_external", "envelope_disbursement").contains(t.getTransactionType()))
                .map(TransactionLog::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal remainingLimit = limit.subtract(spentAmount);
        envelope.setRemainingAmount(remainingLimit.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remainingLimit);
        envelopeRepository.save(envelope);
        return envelope.getRemainingAmount();
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
            return new BigDecimal(((Number) conditions.get("limit")).doubleValue());
        }
        return BigDecimal.ZERO;
    }
}
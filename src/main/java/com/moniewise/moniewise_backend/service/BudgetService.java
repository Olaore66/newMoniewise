package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.request.BudgetRequest;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.request.SpendEnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.BudgetResponse;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.exception.TncAcceptanceRequiredException;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.RevenueLogRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public BudgetService(WalletService walletService, EnvelopeRepository envelopeRepository, BudgetRepository budgetRepository, RevenueLogRepository revenueLogRepository, UserService userService, TransactionLogRepository transactionLogRepository, NotificationService notificationService, WalletService walletService1) {
        this.envelopeRepository = envelopeRepository;
        this.budgetRepository = budgetRepository;
        this.revenueLogRepository = revenueLogRepository;
        this.userService = userService;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.walletService = walletService1;
    }
    private static final Logger logger = LoggerFactory.getLogger(BudgetService.class);

    private static final String LATEST_TNC_VERSION = "2.0";
    private static final String LATEST_TNC_CONTENT = "MonieWise helps you budget... (your terms here)";


    @Transactional
    public BudgetResponse createBudget(BudgetRequest request, String email) {
    User user = userService.findByEmail(email);

    logger.debug("Starting budget creation for {}", email);
    logger.debug("User found: {}", user.getId());


        // Validate dates
        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new IllegalArgumentException("Start date must be before end date");
        }

        long durationDays = ChronoUnit.DAYS.between(
                request.getStartDate(),
                request.getEndDate()
        );

    // 2. Validate duration ≤ 90 days
    if (durationDays <= 0 || durationDays > 90) {
        notificationService.sendNotification(user.getId().toString(),
                "Budget creation failed: Duration cannot exceed 90 days.");
        throw new IllegalArgumentException("Budget duration must be between 1 and 90 days");
    }

        int feeIntervals = (int) Math.ceil((double) durationDays / 30);

        BigDecimal fee = new BigDecimal("100").multiply(BigDecimal.valueOf(feeIntervals));


        // Use original budget amount for wallet check
        BigDecimal budgetAmount = request.getTotalAmount(); // 100,000
        BigDecimal totalWalletDeduction = budgetAmount; // We need the full amount in wallet
        BigDecimal actualBudgetAmount = budgetAmount.subtract(fee); // 99,900 (for envelopes)


        // 4. Check wallet balance - verify user has the FULL budget amount
        BigDecimal walletBalance = walletService.checkBalance(user.getId());
        if (walletBalance.compareTo(actualBudgetAmount) < 0) {
            String message = String.format(
                    "Transaction failed: Your wallet has insufficient funds. ₦%.2f is required (₦%.2f budget - ₦%.2f fee), but your current balance is ₦%.2f. Please fund your wallet.",
                    actualBudgetAmount, budgetAmount, fee, walletBalance
            );
            notificationService.sendNotification(user.getId().toString(), message);
            throw new IllegalArgumentException(message);
        }

    // 5. Validate envelope percentages sum to 100%
    BigDecimal totalPercentage = request.getEnvelopes().stream()
            .map(EnvelopeRequest::getPercentage)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (totalPercentage.compareTo(new BigDecimal("100")) != 0) {
        throw new IllegalArgumentException("Envelope percentages must sum to 100");
    }

    // 6. Validate envelope conditions
    Set<String> conditionTypes = new HashSet<>();
    for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
        Map<String, Object> conditions = envelopeRequest.getConditions();
        String type = (String) conditions.get("type");
        if (!Arrays.asList("daily", "weekly", "dynamic", "safe_lock", "strict_lock", "emergency").contains(type)) {
            throw new IllegalArgumentException("Invalid condition type: " + type);
        }
        if ("emergency".equals(type)) {
            conditions.putIfAbsent("used", false);
        }
        // Add dynamic validation
        if ("dynamic".equals(type)) {
            envelopeRequest.validateDynamicConditions();
            int interval = Integer.parseInt(conditions.get("intervalDays").toString());
            if (interval < 1 || interval > 30) {
                throw new IllegalArgumentException("Dynamic interval must be 1-30 days");
            }
        }
        conditionTypes.add(type);
    }
    BudgetStatus status = BudgetStatus.valueOf(request.getStatus());
    if (conditionTypes.size() <= 1 && status == BudgetStatus.ACTIVE) {
        notificationService.sendNotification(user.getId().toString(),
                "Budget creation failed: Add a different condition (e.g., Daily, Weekly, or Strict).");
        throw new IllegalArgumentException("Add a different condition (e.g., Daily, Weekly, or Strict)");
    }


    // 7. Create budget entity
    Budget budget = new Budget();
    budget.setUser(user);
    budget.setName(request.getName());
//    budget.setTotalAmount(budgetAmount);
//    budget.setStartDate(LocalDate.now());
//    budget.setEndDate(budget.getStartDate().plusDays(durationDays));
    budget.setStartDate(request.getStartDate());  // ✅ Use request date
    budget.setEndDate(request.getEndDate());      // ✅ Use request date
    budget.setDurationDays((int) durationDays);   // ✅ Set calculated duration
    budget.setStatus(status);
    budget.setCreatedAt(LocalDateTime.now());
    budget.setLastTopupTime(null);
    // Later when creating the budget
    budget.setTotalAmount(actualBudgetAmount); // Set to 99,900
    budget.setAllocatedAmount(actualBudgetAmount);

        // 8. Create and validate envelopes
    List<Envelope> envelopes = new ArrayList<>();
    for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
        Envelope envelope = new Envelope();
        envelope.setBudget(budget);
        envelope.setName(envelopeRequest.getName());
        BigDecimal percentage = envelopeRequest.getPercentage();
        BigDecimal amount = budgetAmount
                .multiply(percentage)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        envelope.setAmount(amount);
        envelope.setRemainingAmount(amount);
        envelope.setConditions(envelopeRequest.getConditions());
        envelopes.add(envelope);
    }



    // 9. Set allocatedAmount = envelope sum
    BigDecimal allocationSum = envelopes.stream()
            .map(Envelope::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (allocationSum.compareTo(budgetAmount) != 0) {
        throw new IllegalStateException(
                "Envelope allocations (" + allocationSum + ") " +
                        "must equal budget total (" + budgetAmount + ")"
        );
    }

    // Allow ±₦0.01 variance for rounding
    BigDecimal tolerance = new BigDecimal("0.01");
    if (allocationSum.subtract(budgetAmount).abs().compareTo(tolerance) > 0) {
        throw new IllegalStateException(
                "Envelope allocations (" + allocationSum + ") must equal budget total (" + budgetAmount + ")"
        );
    }

    budget.setAllocatedAmount(allocationSum);
    budget.setEnvelopes(envelopes);

    // 10. Save budget (persist with envelopes)
//    Budget savedBudget = budgetRepository.save(budget);
    try {
        Budget savedBudget = budgetRepository.save(budget);
        logger.debug("Budget saved with ID: {}", savedBudget.getId());


    // Update envelopes to reference the saved budget
    envelopes.forEach(envelope -> envelope.setBudget(savedBudget));

    //  11. Deduct FULL amount from wallet
    walletService.deductBalance(user.getId(), budgetAmount); // Deduct 100,000


        // 12. Log transactions
    TransactionLog budgetLog = new TransactionLog();
    budgetLog.setUserId(user.getId());
    budgetLog.setBudgetId(savedBudget.getId());
    budgetLog.setAmount(budgetAmount);
    budgetLog.setTransactionType("budget_allocation");
    budgetLog.setCreatedAt(LocalDateTime.now()); // ✅ Manual timestamp
    transactionLogRepository.save(budgetLog);

    TransactionLog feeLog = new TransactionLog();
    feeLog.setUserId(user.getId());
    feeLog.setBudgetId(savedBudget.getId());
    feeLog.setAmount(fee);
    feeLog.setTransactionType("budget_creation_fee");
    feeLog.setCreatedAt(LocalDateTime.now()); // ✅ Manual timestamp
    transactionLogRepository.save(feeLog);

    RevenueLog revenueLog = new RevenueLog();
    revenueLog.setUserId(user.getId());
    revenueLog.setType("budget_creation");
    revenueLog.setAmount(fee);
    revenueLog.setDescription("Budget fee for " + durationDays + " days");
    revenueLog.setCreatedAt(LocalDateTime.now());
    revenueLogRepository.save(revenueLog);

    // 13. Send notification
    String message = String.format("Budget '%s' created! ₦%.2f allocated and ₦%.2f fee deducted.",
            savedBudget.getName(), budgetAmount, fee);
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
                    .collect(Collectors.toList())
    );

    } catch (Exception e) {
        logger.error("Failed to save budget", e);
        throw e;
    }
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

    // MOVE MONEY 12/04/2025 ---
    @Transactional
    public void moveMoney(Long sourceId, Long targetId, Double amount, String email) {
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
        System.out.println("Moving money within Budget ID: " + sourceBudget.getId() + " (" + sourceBudget.getName() + ")");
        if (!sourceBudget.getUser().getId().equals(user.getId()) || !targetBudget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to move money from/to these envelopes");
        }
        if (!sourceBudget.getId().equals(targetBudget.getId())) {
            throw new IllegalArgumentException("Source and target envelopes must belong to the same budget");
        }
        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Budget must be active to perform transactions");
        }

        BigDecimal transferAmount = BigDecimal.valueOf(amount);
        if (transferAmount.compareTo(source.getRemainingAmount()) > 0) {
            throw new IllegalArgumentException("Insufficient funds in source envelope. Available: " + source.getRemainingAmount());
        }

        // Step 2: Check source envelope conditions and limits
        Map<String, Object> sourceConditions = source.getConditions();
        String sourceConditionType = (String) sourceConditions.get("type");
        boolean isTransferAllowed = true;
        BigDecimal feePercentage = BigDecimal.ZERO;

        LocalDateTime now = LocalDateTime.now();
        DayOfWeek currentDay = now.getDayOfWeek();
        LocalTime currentTime = now.toLocalTime();

        LocalDateTime periodStart;
        LocalDateTime periodEnd;
        switch (sourceConditionType) {
            case "daily":
                periodStart = now.toLocalDate().atStartOfDay();
                periodEnd = periodStart.plusDays(1);
                break;
            case "weekly":
                periodStart = now.toLocalDate().atStartOfDay().minusDays(now.getDayOfWeek().getValue() - 1);
                periodEnd = periodStart.plusDays(7);
                break;
            case "dynamic":
                String day = (String) sourceConditions.get("day");
                DayOfWeek conditionDay = DayOfWeek.valueOf(day.toUpperCase());
                LocalDate targetDate = now.toLocalDate();
                while (targetDate.getDayOfWeek() != conditionDay) {
                    targetDate = targetDate.plusDays(1);
                }
                String startTimeStr = (String) sourceConditions.get("start_time");
                String endTimeStr = (String) sourceConditions.get("end_time");
                LocalTime startTime = LocalTime.parse(startTimeStr);
                LocalTime endTime = LocalTime.parse(endTimeStr);
                periodStart = LocalDateTime.of(targetDate, startTime);
                periodEnd = LocalDateTime.of(targetDate, endTime);
                break;
            default:
                periodStart = LocalDateTime.now().minusYears(1);
                periodEnd = LocalDateTime.now().plusYears(1);
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
                String day = (String) sourceConditions.get("day");
                String startTimeStr = (String) sourceConditions.get("start_time");
                String endTimeStr = (String) sourceConditions.get("end_time");
                LocalTime startTime = LocalTime.parse(startTimeStr);
                LocalTime endTime = LocalTime.parse(endTimeStr);
                DayOfWeek conditionDay = DayOfWeek.valueOf(day.toUpperCase());

                if (!(currentDay == conditionDay && currentTime.isAfter(startTime) && currentTime.isBefore(endTime))) {
                    isTransferAllowed = false;
                    throw new IllegalArgumentException("Transfer not allowed outside of " + day + " " + startTime + " to " + endTime);
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
        BigDecimal fee = transferAmount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
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
                null,
                transferAmount,
                fee,
                "envelope_to_envelope"
        );
        transactionLogRepository.save(transactionLog);

        // Step 6: Log fee to revenue_logs
        String revenueDescription = "Transfer in Budget " + sourceBudget.getId() + " from " + source.getName() + " to " + target.getName() + " (fee: " + feePercentage + "%)";
        RevenueLog revenueLog = new RevenueLog(
                user.getId(),
                "envelope_transfer_fee",
                fee,
                revenueDescription
        );
        revenueLogRepository.save(revenueLog);

        // Step 7: Credit Moniewise revenue account
        creditRevenueAccount(fee, revenueDescription);
    }

    @Transactional
    public void transferToExternal(Long sourceId, String externalAccount, Double amount, String email) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        User user = userService.findByEmail(email);
        Envelope source = envelopeRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Source envelope not found with ID: " + sourceId));

        Budget sourceBudget = source.getBudget();
        System.out.println("Transferring to external from Budget ID: " + sourceBudget.getId() + " (" + sourceBudget.getName() + ")");
        if (!sourceBudget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to transfer from this envelope");
        }
        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Budget must be active to perform transactions");
        }

        BigDecimal transferAmount = BigDecimal.valueOf(amount);
        if (transferAmount.compareTo(source.getRemainingAmount()) > 0) {
            throw new IllegalArgumentException("Insufficient funds in source envelope. Available: " + source.getRemainingAmount());
        }

        Map<String, Object> sourceConditions = source.getConditions();
        String sourceConditionType = (String) sourceConditions.get("type");
        boolean isTransferAllowed = true;
        BigDecimal feePercentage = BigDecimal.ZERO;

        LocalDateTime now = LocalDateTime.now();
        DayOfWeek currentDay = now.getDayOfWeek();
        LocalTime currentTime = now.toLocalTime();

        LocalDateTime periodStart;
        LocalDateTime periodEnd;
        switch (sourceConditionType) {
            case "daily":
                periodStart = now.toLocalDate().atStartOfDay();
                periodEnd = periodStart.plusDays(1);
                break;
            case "weekly":
                periodStart = now.toLocalDate().atStartOfDay().minusDays(now.getDayOfWeek().getValue() - 1);
                periodEnd = periodStart.plusDays(7);
                break;
            case "dynamic":
                String day = (String) sourceConditions.get("day");
                DayOfWeek conditionDay = DayOfWeek.valueOf(day.toUpperCase());
                LocalDate targetDate = now.toLocalDate();
                while (targetDate.getDayOfWeek() != conditionDay) {
                    targetDate = targetDate.plusDays(1);
                }
                String startTimeStr = (String) sourceConditions.get("start_time");
                String endTimeStr = (String) sourceConditions.get("end_time");
                LocalTime startTime = LocalTime.parse(startTimeStr);
                LocalTime endTime = LocalTime.parse(endTimeStr);
                periodStart = LocalDateTime.of(targetDate, startTime);
                periodEnd = LocalDateTime.of(targetDate, endTime);
                break;
            default:
                periodStart = LocalDateTime.now().minusYears(1);
                periodEnd = LocalDateTime.now().plusYears(1);
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
                String day = (String) sourceConditions.get("day");
                String startTimeStr = (String) sourceConditions.get("start_time");
                String endTimeStr = (String) sourceConditions.get("end_time");
                LocalTime startTime = LocalTime.parse(startTimeStr);
                LocalTime endTime = LocalTime.parse(endTimeStr);
                DayOfWeek conditionDay = DayOfWeek.valueOf(day.toUpperCase());

                if (!(currentDay == conditionDay && currentTime.isAfter(startTime) && currentTime.isBefore(endTime))) {
                    isTransferAllowed = false;
                    throw new IllegalArgumentException("Transfer not allowed outside of " + day + " " + startTime + " to " + endTime);
                }
                feePercentage = new BigDecimal("2");
                break;

            default:
                throw new IllegalArgumentException("Unknown condition type: " + sourceConditionType);
        }

        if (!isTransferAllowed) {
            throw new IllegalArgumentException("Transfer not allowed due to source envelope conditions");
        }

        BigDecimal fee = transferAmount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
        BigDecimal amountAfterFee = transferAmount.subtract(fee);

        source.setRemainingAmount(source.getRemainingAmount().subtract(transferAmount));
        envelopeRepository.save(source);

        // TODO: Integrate with Paystack/Flutterwave to transfer 'amountAfterFee' to 'externalAccount'
        System.out.println("Simulating transfer of ₦" + amountAfterFee + " to external account: " + externalAccount);

        TransactionLog transactionLog = new TransactionLog(
                user.getId(),
                sourceBudget.getId(),
                sourceId,
                null,
                externalAccount,
                transferAmount,
                fee,
                "envelope_to_external"
        );
        transactionLogRepository.save(transactionLog);

        String revenueDescription = "Transfer in Budget " + sourceBudget.getId() + " from " + source.getName() + " to external account " + externalAccount + " (fee: " + feePercentage + "%)";
        RevenueLog revenueLog = new RevenueLog(
                user.getId(),
                "envelope_transfer_fee",
                fee,
                revenueDescription
        );
        revenueLogRepository.save(revenueLog);

        // Credit Moniewise revenue account
        creditRevenueAccount(fee, revenueDescription);
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
        LocalDateTime now = LocalDateTime.now();
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
                null, // No source envelope (from Wallet)
                null, // No target envelope (to Budget)
                null, // No external account
                topupAmount,
                BigDecimal.ZERO, // No fee
                "wallet_to_budget"
        );
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
        TransactionLog transactionLog = new TransactionLog(
                user.getId(),
                budgetId,
                null, // No source envelope
                null, // No target envelope
                null, // No external account
                BigDecimal.ZERO, // No amount (just an extension)
                BigDecimal.ZERO, // No fee
                "budget_extension"
        );
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

                    // Log transaction
                    TransactionLog transactionLog = new TransactionLog(
                            user.getId(),
                            budgetId,
                            envelope.getId(),
                            null, // No target envelope
                            "user_wallet", // Destination is user's Wallet
                            remainingAmount,
                            BigDecimal.ZERO, // No fee for rollback
                            "strict_lock_rollback"
                    );
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


    // GET DASHBOARD DETAILS
    public Map<String, Object> getDashboard(String email) {
        User user = userService.findByEmail(email);
        String greeting = getTimeBasedGreeting(user.getEmail().split("@")[0]);

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
                    remaining,
                    (int) ChronoUnit.DAYS.between(budget.getStartDate(), budget.getEndDate()),
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
                                    e.getRemainingAmount(),
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

    private String getTimeBasedGreeting(String name) {
        int hour = LocalDateTime.now().getHour();
        if (hour < 12) return "Good Morning, " + name + "!";
        if (hour < 17) return "Good Afternoon, " + name + "!";
        return "Good Evening, " + name + "!";
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
package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.config.BudgetLifeCycleManager;
import com.moniewise.moniewise_backend.dto.request.BudgetRequest;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.request.SpendEnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.BudgetResponse;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static com.moniewise.moniewise_backend.enums.TransactionType.*;

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

    private final ScheduledTaskRepository scheduledTaskRepository;
    private final EnvelopeService envelopeService;

    private final BudgetLifeCycleManager budgetLifeCycleManager;


    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    public BudgetService(
            EnvelopeRepository envelopeRepository,
            BudgetRepository budgetRepository,
            RevenueLogRepository revenueLogRepository,
            UserService userService,
            TransactionLogRepository transactionLogRepository,
            NotificationService notificationService,
            WalletService walletService, ScheduledTaskRepository scheduledTaskRepository, @Lazy EnvelopeService envelopeService, BudgetLifeCycleManager budgetLifeCycleManager) {
        this.envelopeRepository = envelopeRepository;
        this.budgetRepository = budgetRepository;
        this.revenueLogRepository = revenueLogRepository;
        this.userService = userService;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.walletService = walletService; // Assign walletService1 as in original
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.envelopeService = envelopeService;
        this.budgetLifeCycleManager = budgetLifeCycleManager;
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
    @Transactional
    public BudgetResponse createBudget(BudgetRequest request, String email) {
        User user = userService.findByEmail(email);
        logger.debug("Starting budget creation for {}", email);
        logger.debug("User found: {}", user.getId());

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        // Validate dates
        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new IllegalArgumentException("Start date must be before end date");
        }

        long durationDays = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate());
        if (durationDays <= 0) durationDays = 1;

        // Validate duration
        if (durationDays > 90) {
            notificationService.sendNotification(user.getId().toString(),
                    "Budget creation failed: Duration cannot exceed 90 days.", NotificationType.BUDGET_CREATION);
            throw new IllegalArgumentException("Budget duration must be between 1 and 90 days");
        }

        // Calculate fee and budget amounts
        int feeIntervals = (int) Math.ceil((double) durationDays / 30);
        BigDecimal fee = new BigDecimal("100").multiply(BigDecimal.valueOf(feeIntervals));
        BigDecimal originalAmount = request.getTotalAmount();
        BigDecimal actualBudgetAmount = originalAmount.subtract(fee);

        // === ADD THIS NEW BLOCK (Option 3 implementation) ===
        List<EnvelopeRequest> envelopeRequests = request.getEnvelopes();
        Map<EnvelopeRequest, BigDecimal> finalAmounts = new LinkedHashMap<>();

        BigDecimal sumOfRoundedAmounts = BigDecimal.ZERO;

        for (EnvelopeRequest env : envelopeRequests) {
            BigDecimal percentage = env.getPercentage();
            BigDecimal calculated = actualBudgetAmount
                    .multiply(percentage)
                    .divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP); // keep precision

            BigDecimal rounded = calculated.setScale(2, RoundingMode.HALF_UP);
            finalAmounts.put(env, rounded);
            sumOfRoundedAmounts = sumOfRoundedAmounts.add(rounded);
        }

        // Calculate the rounding error (usually between -0.99 and +0.99)
        BigDecimal roundingError = actualBudgetAmount.subtract(sumOfRoundedAmounts);

        // Distribute the error to the largest envelope(s) – this makes sum EXACT
        if (roundingError.compareTo(BigDecimal.ZERO) != 0) {
            // Strategy: give all the difference to the envelope with highest percentage
            EnvelopeRequest largest = envelopeRequests.stream()
                    .max(Comparator.comparing(EnvelopeRequest::getPercentage))
                    .orElse(envelopeRequests.get(0));

            BigDecimal oldAmount = finalAmounts.get(largest);
            BigDecimal newAmount = oldAmount.add(roundingError);
            finalAmounts.put(largest, newAmount);

            logger.info("Adjusted envelope '{}' by ₦{} due to rounding. New amount: ₦{}",
                    largest.getName(), roundingError, newAmount);
        }




        // Validate envelope percentages
        BigDecimal totalPercentage = request.getEnvelopes().stream()
                .map(EnvelopeRequest::getPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
                .reduce(BigDecimal.ZERO, BigDecimal::add);


        // Validate allocation
        BigDecimal minimumAllocation = actualBudgetAmount.multiply(new BigDecimal("50"))
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
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

        // ADD THIS LOOP: VALIDATE EACH ENVELOPE LIMIT
        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//            BigDecimal envelopeAmount = actualBudgetAmount
//                    .multiply(envelopeRequest.getPercentage())
//                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            BigDecimal envelopeAmount = finalAmounts.get(envelopeRequest); // ← THIS IS NOW GUARANTEED TO SUM CORRECTLY

            validateEnvelopeLimit(envelopeRequest, envelopeAmount, request.getStartDate(), request.getEndDate());
        }

        // Check wallet balance for allocation sum
        BigDecimal walletBalance = walletService.checkBalance(user.getId());
        if (walletBalance.compareTo(allocationSum) < 0) {
            String message = String.format(
                    "Transaction failed: Your wallet has insufficient funds. At least ₦%.2f is required for allocation, but your current balance is ₦%.2f. Please fund your wallet.",
                    allocationSum, walletBalance
            );

            notificationService.sendNotification(user.getId().toString(), message, NotificationType.BUDGET_CREATION);
            
            throw new IllegalArgumentException(message);
        }

        // Create and save budget entity
        Budget budget = new Budget();
        budget.setUser(user);
        budget.setName(request.getName());
        budget.setOriginalAmount(originalAmount);
        budget.setFeeAmount(fee);
        budget.setTotalAmount(actualBudgetAmount);
        budget.setAllocatedAmount(allocationSum);
        budget.setStartDate(request.getStartDate());
        budget.setEndDate(request.getEndDate());
        budget.setDurationDays((int) durationDays);
        budget.setStatus(request.getStatus());
        budget.setCreatedAt(now);
        budget.setRemainingAmount(request.getTotalAmount().subtract(budget.getFeeAmount()));
        Budget savedBudget = budgetRepository.save(budget);


        // Create envelopes using EnvelopeService
        List<Envelope> envelopes = new ArrayList<>();
        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {

            BigDecimal correctAmount = finalAmounts.get(envelopeRequest);
            envelopeRequest.setExactAmount(correctAmount);

            envelopeRequest.setBudgetId(savedBudget.getId()); // Set budget ID

            EnvelopeResponse envelopeResponse = envelopeService.createEnvelope(envelopeRequest, email);
            Envelope envelope = envelopeRepository.findById(envelopeResponse.getId())
                    .orElseThrow(() -> new IllegalStateException("Failed to retrieve created envelope"));
            envelopes.add(envelope);
        }

        savedBudget.clearEnvelopes();
        savedBudget.addAllEnvelopes(envelopes);

        // Deduct allocation from user wallet
        walletService.deductBalance(user.getId(), allocationSum);

        // Transfer fee to revenue wallet
        walletService.fundWallet(revenueWalletUserId, fee,
                String.format("₦%.2f received as budget creation fee.", fee));

        // Refund unallocated amount
        BigDecimal unallocatedAmount = actualBudgetAmount.subtract(allocationSum);
        if (unallocatedAmount.compareTo(BigDecimal.ZERO) > 0) {
            walletService.fundWallet(user.getId(), unallocatedAmount,
                    String.format("₦%.2f refunded to wallet from unallocated budget funds.", unallocatedAmount));
            TransactionLog refundLog = new TransactionLog();
            refundLog.setUserId(user.getId());
            refundLog.setBudgetId(savedBudget.getId());
            refundLog.setAmount(unallocatedAmount);
            refundLog.setTransactionType(BUDGET_UNALLOCATED_REFUNDED);
            refundLog.setCreatedAt(now);
            transactionLogRepository.save(refundLog);
        }

        // Log transactions
        TransactionLog budgetLog = new TransactionLog();
        budgetLog.setUserId(user.getId());
        budgetLog.setBudgetId(savedBudget.getId());
        budgetLog.setAmount(allocationSum);
        budgetLog.setTransactionType(BUDGET_ALLOCATION);
        budgetLog.setCreatedAt(now);
        transactionLogRepository.save(budgetLog);

        TransactionLog feeLog = new TransactionLog();
        feeLog.setUserId(user.getId());
        feeLog.setBudgetId(savedBudget.getId());
        feeLog.setAmount(fee);
        feeLog.setTransactionType(BUDGET_CREATION_FEE);
        feeLog.setCreatedAt(now);
        transactionLogRepository.save(feeLog);

        RevenueLog revenueLog = new RevenueLog();
        revenueLog.setUserId(revenueWalletUserId);
        revenueLog.setType("budget_creation");
        revenueLog.setAmount(fee);
        revenueLog.setDescription("Budget fee for " + durationDays + " days");
        revenueLog.setCreatedAt(now);
        revenueLogRepository.save(revenueLog);

        // Send notification
        String message = String.format(
                "Budget '%s' created! ₦%.2f allocated (₦%.2f fee applied, ₦%.2f refunded to wallet).",
                savedBudget.getName(), allocationSum, fee, unallocatedAmount
        );
        notificationService.sendNotification(user.getId().toString(), message, NotificationType.BUDGET_CREATION);

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

                                e.getAmount(),
                                e.getTotalRemainingAmount(),
                                e.getRemainingAmount(),
//                                getLimitFromConditions(e),
                                calculatePeriodLimit(e, savedBudget),
                                getUsedThisPeriod(e),

                                e.getConditions(),
                                e.getCreatedAt(),
                                e.getLastDisbursedAt(),
                                e.getNextDisbursementAt()
                        ))
                        .collect(Collectors.toList()),
                savedBudget.getOriginalAmount(),
                savedBudget.getFeeAmount()
        );
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
                        // New fields
                        envelope.getAmount(),                    // ← initialAmount
                        envelope.getTotalRemainingAmount(),      // ← totalRemaining
                        envelope.getRemainingAmount(),           // ← periodRemaining
//                        getPeriodLimit(envelope),                // ← periodLimit
                        calculatePeriodLimit(envelope, budget),
                        getUsedThisPeriod(envelope),

                        envelope.getRemainingAmount(),
                        envelope.getConditions(),
                        envelope.getCreatedAt(),
                        envelope.getLastDisbursedAt(),
                        envelope.getNextDisbursementAt()
                ))
                .collect(Collectors.toList());
    }

    // REVENUE ACCOUNT ----- 12/04/2025 -->Simulated Moniewise revenue account ID on the payment gateway
    private static final String MONIEWISE_REVENUE_ACCOUNT = "moniewise_revenue_001";

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
                BUDGET_EXTENSION,
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
                            STRICT_LOCK_ROLLBACK,
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

                                    // New fields
                                    e.getAmount(),                    // ← initialAmount
                                    e.getTotalRemainingAmount(),      // ← totalRemaining
                                    e.getRemainingAmount(),           // ← periodRemaining
//                                    getPeriodLimit(e),                // ← periodLimit
                                    calculatePeriodLimit(e, budget),
                                    getUsedThisPeriod(e),             // ← usedThisPeriod

                                    e.getConditions(),
                                    e.getCreatedAt(),
                                    e.getLastDisbursedAt(),
                                    e.getNextDisbursementAt()
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
    public Map<String, Object> spendEnvelope(SpendEnvelopeRequest request, String email) {
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
                    notificationService.sendNotification(user.getId().toString(), "Emergency funds already used!", NotificationType.GENERAL);
                    throw new IllegalArgumentException("Emergency funds can only be used once");
                }
                if (envelope.getRemainingAmount().compareTo(amount) < 0) {
                    String message = String.format("Insufficient emergency funds: ₦%.2f needed, ₦%.2f available", amount, envelope.getRemainingAmount());
                    notificationService.sendNotification(user.getId().toString(), message, NotificationType.INSUFFICIENT_BALANCE);
                    throw new IllegalArgumentException(message);
                }
                fee = amount.multiply(new BigDecimal("0.05")); // 5% fee
                conditions.put("used", true);
                break;

            case "daily":
                BigDecimal dailyLimit = new BigDecimal(conditions.get("limit").toString());
                LocalDateTime lastAccessed = envelope.getLastAccessed() != null ? envelope.getLastAccessed() : LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
                if (lastAccessed.toLocalDate().equals(now.toLocalDate())) {
                    notificationService.sendNotification(user.getId().toString(), "Daily limit already used today!", NotificationType.GENERAL);
                    throw new IllegalArgumentException("Daily limit already used today");
                }
                if (amount.compareTo(dailyLimit) > 0) {
                    String message = String.format("Amount exceeds daily limit: ₦%.2f requested, ₦%.2f allowed", amount, dailyLimit);
                    notificationService.sendNotification(user.getId().toString(), message, NotificationType.GENERAL);
                    throw new IllegalArgumentException(message);
                }
                if (envelope.getRemainingAmount().compareTo(amount) < 0) {
                    String message = String.format("Insufficient funds: ₦%.2f needed, ₦%.2f available", amount, envelope.getRemainingAmount());
                    notificationService.sendNotification(user.getId().toString(), message, NotificationType.INSUFFICIENT_BALANCE);
                    throw new IllegalArgumentException(message);
                }
                break;

            case "weekly":
                BigDecimal weeklyLimit = new BigDecimal(conditions.get("limit").toString());
                LocalDateTime weekStart = now.minusDays(now.getDayOfWeek().getValue() - 1);
                if (envelope.getLastAccessed() != null && envelope.getLastAccessed().isAfter(weekStart)) {
                    notificationService.sendNotification(user.getId().toString(), "Weekly limit already used this week!", NotificationType.GENERAL);
                    throw new IllegalArgumentException("Weekly limit already used this week");
                }
                if (amount.compareTo(weeklyLimit) > 0) {
                    String message = String.format("Amount exceeds weekly limit: ₦%.2f requested, ₦%.2f allowed", amount, weeklyLimit);
                    notificationService.sendNotification(user.getId().toString(), message, NotificationType.GENERAL);
                    throw new IllegalArgumentException(message);
                }
                if (envelope.getRemainingAmount().compareTo(amount) < 0) {
                    String message = String.format("Insufficient funds: ₦%.2f needed, ₦%.2f available", amount, envelope.getRemainingAmount());
                    notificationService.sendNotification(user.getId().toString(), message, NotificationType.GENERAL);
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
            notificationService.sendNotification(user.getId().toString(), message, NotificationType.GENERAL);
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
        spendLog.setTransactionType(ENVELOPE_DISBURSEMENT);
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
            feeLog.setTransactionType(ENVELOPE_DISBURSEMENT);
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
        notificationService.sendNotification(user.getId().toString(), message, NotificationType.GENERAL);

        // Return Map for consistency with BudgetController
        Map<String, Object> response = new HashMap<>();
        response.put("id", envelope.getId());
        response.put("budgetId", budget.getId());
        response.put("name", envelope.getName());
        response.put("amount", envelope.getAmount());
        response.put("remainingAmount", envelope.getRemainingAmount());
        response.put("conditions", envelope.getConditions());
        response.put("createdAt", envelope.getCreatedAt());
        response.put("lastDisbursedAt", envelope.getLastDisbursedAt());
        return response;
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

                        // New fields
                        envelope.getAmount(),                    // ← initialAmount
                        envelope.getTotalRemainingAmount(),      // ← totalRemaining
                        envelope.getRemainingAmount(),           // ← periodRemaining
//                        getPeriodLimit(envelope),                // ← periodLimit
                        calculatePeriodLimit(envelope, budget),
                        getUsedThisPeriod(envelope),             // ← usedThisPeriod

                        envelope.getConditions(),
                        envelope.getCreatedAt(),
                        envelope.getLastDisbursedAt(),
                        envelope.getNextDisbursementAt()
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
                // New fields
                envelope.getAmount(),                    // ← initialAmount
                envelope.getTotalRemainingAmount(),      // ← totalRemaining
                envelope.getRemainingAmount(),           // ← periodRemaining
                getPeriodLimit(envelope),                // ← periodLimit
                getUsedThisPeriod(envelope),             // ← usedThisPeriod

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
        if (List.of("emergency", "strict_lock", "safe_lock").contains(type)) return;

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
}
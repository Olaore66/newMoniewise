package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class SavingsService {

    private static final Logger logger = LoggerFactory.getLogger(SavingsService.class);
    private final SavingsGoalRepository savingsGoalRepository;
    private final UserRepository userRepository;
    private final WalletService walletService; // To deduct initial deposits
    private final TransactionLogRepository transactionLogRepository;

    public SavingsService(SavingsGoalRepository savingsGoalRepository,
                          UserRepository userRepository,
                          WalletService walletService,
                          TransactionLogRepository transactionLogRepository) {
        this.savingsGoalRepository = savingsGoalRepository;
        this.userRepository = userRepository;
        this.walletService = walletService;
        this.transactionLogRepository = transactionLogRepository;
    }

    /**
     * Phase 1: Creating the pot and making the initial deposit
     */
    @Transactional(rollbackFor = Exception.class)
    public SavingsGoal createSavingsGoal(Long userId, String name, BigDecimal targetAmount, 
                                         BigDecimal initialDeposit, LocalDate maturityDate, BigDecimal interestRate) {
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 1. Safety Check: Prevent duplicate names
        if (savingsGoalRepository.existsByUserIdAndNameIgnoreCase(userId, name)) {
            throw new IllegalArgumentException("You already have a savings goal named '" + name + "'");
        }

        // 2. Create the Pot
        SavingsGoal goal = new SavingsGoal();
        goal.setUser(user);
        goal.setName(name);
        goal.setTargetAmount(targetAmount);
        goal.setStartDate(LocalDate.now(ZoneId.of("Africa/Lagos")));
        goal.setMaturityDate(maturityDate);
        goal.setInterestRate(interestRate != null ? interestRate : BigDecimal.ZERO);
        goal.setStatus(SavingsStatus.ACTIVE);
        
        // 3. Handle Initial Deposit (e.g., the 800k)
        if (initialDeposit != null && initialDeposit.compareTo(BigDecimal.ZERO) > 0) {
            // Deduct from Main Wallet
            walletService.debitWalletForWithdrawal(userId, initialDeposit);

            goal.setCurrentBalance(initialDeposit);

            // Log the Transaction
            TransactionLog log = new TransactionLog();
            log.setUserId(userId);
            log.setAmount(initialDeposit);
            // NOTE: Add SAVINGS_DEPOSIT to your TransactionType Enum!
            log.setTransactionType(TransactionType.SAVINGS_DEPOSIT); 
            log.setDescription("Funded Savings Goal: " + name);
            log.setStatus(TransactionStatus.COMPLETED);
            log.setReference("SAVE-" + UUID.randomUUID().toString());
            log.setCreatedAt(LocalDateTime.now());
            transactionLogRepository.save(log);
        } else {
            goal.setCurrentBalance(BigDecimal.ZERO);
        }

        logger.info("Created Savings Goal '{}' for User {} with initial balance ₦{}", name, userId, goal.getCurrentBalance());
        return savingsGoalRepository.save(goal);
    }

    /**
     * Phase 2: The "Instant Sweep" (Called when creating a budget)
     * This moves money from the active budget straight into the locked pot.
     */
    @Transactional(rollbackFor = Exception.class)
    public void sweepEnvelopeToSavings(Long userId, Long savingsGoalId, BigDecimal amount,
                                       String envelopeName, Long budgetId, Long envelopeId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return;

        SavingsGoal goal = savingsGoalRepository.findById(savingsGoalId)
                .orElseThrow(() -> new RuntimeException("Savings Goal not found"));

        if (!goal.getUser().getId().equals(userId)) {
            throw new SecurityException("Savings goal does not belong to this user.");
        }

        if (goal.getStatus() != SavingsStatus.ACTIVE) {
            throw new IllegalStateException("Cannot sweep money into an inactive savings pot.");
        }

        goal.setCurrentBalance(goal.getCurrentBalance().add(amount));
        savingsGoalRepository.save(goal);

        TransactionLog log = new TransactionLog();
        log.setUserId(userId);
        log.setBudgetId(budgetId);
        log.setSourceEnvelopeId(envelopeId);
        log.setAmount(amount);
        log.setTransactionType(TransactionType.SAVINGS_DEPOSIT);
        log.setDescription("Swept from Budget Envelope: " + envelopeName);
        log.setStatus(TransactionStatus.COMPLETED);
        log.setReference("SWEEP-" + UUID.randomUUID().toString());
        log.setCreatedAt(LocalDateTime.now());
        transactionLogRepository.save(log);

        logger.info("Swept ₦{} from envelope '{}' (budget={}) into Savings Goal {}", amount, envelopeName, budgetId, savingsGoalId);
    }

    public List<SavingsGoal> getActiveSavingsForUser(Long userId) {
        return savingsGoalRepository.findByUserIdAndStatus(userId, SavingsStatus.ACTIVE);
    }

    public List<SavingsGoal> getAllSavingsForUser(Long userId) {
        return savingsGoalRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public SavingsGoal withdrawSavings(Long userId, Long savingsGoalId) {
        SavingsGoal goal = savingsGoalRepository.findById(savingsGoalId)
                .orElseThrow(() -> new IllegalArgumentException("Savings goal not found"));

        if (!goal.getUser().getId().equals(userId)) {
            throw new SecurityException("You do not have permission to withdraw this savings goal.");
        }

        if (goal.getStatus() != SavingsStatus.MATURED) {
            throw new IllegalStateException("Only matured savings goals can be withdrawn. Current status: " + goal.getStatus());
        }

        BigDecimal principal = goal.getCurrentBalance() != null ? goal.getCurrentBalance() : BigDecimal.ZERO;
        BigDecimal interest  = goal.getAccruedInterest() != null ? goal.getAccruedInterest() : BigDecimal.ZERO;
        BigDecimal totalPayout = principal.add(interest);

        if (totalPayout.compareTo(BigDecimal.ZERO) > 0) {
            String msg = String.format("Savings withdrawal: %s (₦%,.2f principal + ₦%,.2f interest)",
                    goal.getName(), principal, interest);
            walletService.fundWallet(userId, totalPayout, msg, false);
        }

        TransactionLog log = new TransactionLog();
        log.setUserId(userId);
        log.setAmount(totalPayout);
        log.setTransactionType(TransactionType.SAVINGS_WITHDRAWAL);
        log.setDescription("Withdrawal from matured savings: " + goal.getName());
        log.setStatus(TransactionStatus.COMPLETED);
        log.setReference("SAVE-OUT-" + UUID.randomUUID().toString());
        log.setCreatedAt(LocalDateTime.now());
        transactionLogRepository.save(log);

        goal.setCurrentBalance(BigDecimal.ZERO);
        goal.setAccruedInterest(BigDecimal.ZERO);
        goal.setStatus(SavingsStatus.WITHDRAWN);
        SavingsGoal saved = savingsGoalRepository.save(goal);

        logger.info("User {} withdrew ₦{} from savings goal {} ({})", userId, totalPayout, savingsGoalId, goal.getName());
        return saved;
    }

    /**
     * Phase 4: Manual "Ad-Hoc" Top-Up directly from Wallet
     */
    @Transactional(rollbackFor = Exception.class)
    public SavingsGoal manualTopUp(Long userId, Long savingsGoalId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Top-up amount must be greater than zero.");
        }

        SavingsGoal goal = savingsGoalRepository.findById(savingsGoalId)
                .orElseThrow(() -> new IllegalArgumentException("Savings Goal not found"));

        // Security check: Make sure they own this pot!
        if (!goal.getUser().getId().equals(userId)) {
            throw new SecurityException("You do not have permission to fund this savings goal.");
        }

        // Logic check: Can't fund a closed pot
        if (goal.getStatus() != SavingsStatus.ACTIVE) {
            throw new IllegalStateException("Cannot add funds to a matured or closed savings pot.");
        }

        // 1. Deduct silently from Wallet
        walletService.debitWalletForWithdrawal(userId, amount);

        // 2. Add to Savings Pot
        goal.setCurrentBalance(goal.getCurrentBalance().add(amount));
        SavingsGoal updatedGoal = savingsGoalRepository.save(goal);

        // 3. Log the transaction
        TransactionLog log = new TransactionLog();
        log.setUserId(userId);
        log.setAmount(amount);
        log.setTransactionType(TransactionType.SAVINGS_DEPOSIT);
        log.setDescription("Manual top-up to Savings: " + goal.getName());
        log.setStatus(TransactionStatus.COMPLETED);
        log.setReference("SAVE-TOPUP-" + UUID.randomUUID().toString());
        log.setCreatedAt(LocalDateTime.now());
        transactionLogRepository.save(log);

        logger.info("User {} manually topped up ₦{} into Savings Goal {}", userId, amount, savingsGoalId);

        // 4. (Optional UX Bonus) Check if they just hit their target!
        if (updatedGoal.getCurrentBalance().compareTo(updatedGoal.getTargetAmount()) >= 0) {
            logger.info("🎉 User {} just hit their savings target for {}!", userId, goal.getName());
            // You can trigger a push notification here if you want:
            // notificationService.sendNotification(userId.toString(), "🎉 You hit your " + goal.getName() + " target!", NotificationType.SAVINGS_TARGET_REACHED);
        }

        return updatedGoal;
    }
}
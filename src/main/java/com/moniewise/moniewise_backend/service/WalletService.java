package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.thirdParty.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;

import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEDUCTION;
import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEPOSIT;

@Service
public class WalletService {

    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;  // -1


    private static final Logger logger = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final PaymentGateway paymentGateway;

    public WalletService(WalletRepository walletRepository,
                         TransactionLogRepository transactionLogRepository,
                         NotificationService notificationService, UserRepository userRepository, PaymentGateway paymentGateway) {
        this.walletRepository = walletRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;

        this.userRepository = userRepository;
        this.paymentGateway = paymentGateway;
    }

    @PostConstruct
    public void ensureRevenueWalletExists() {
        // Check by flag — safest possible way
        if (walletRepository.findByIsRevenueWalletTrue().isPresent()) {
            logger.info("Platform revenue wallet already exists");
            return;
        }

        User revenueUser = userRepository.findById(revenueWalletUserId)
                .orElseThrow(() -> new RuntimeException("System revenue user not found! Run SQL to create user ID -1"));

        Wallet revenueWallet = new Wallet();
        revenueWallet.setUser(revenueUser);
        revenueWallet.setBalance(BigDecimal.ZERO);
        revenueWallet.setCurrency("NGN");
        revenueWallet.setStatus(WalletStatus.ACTIVE);
        revenueWallet.setIsRevenueWallet(true);
        revenueWallet.setUpdatedAt(LocalDateTime.now());

        walletRepository.save(revenueWallet);
        logger.info("Created platform revenue wallet for system user ID {}", revenueWalletUserId);
    }

    /**
     * Checks if the user's wallet has sufficient balance.
     *
     * @param userId User ID
     * @return Current balance
     * @throws IllegalArgumentException if wallet not found
     */
    public BigDecimal checkBalance(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));
        return wallet.getBalance();
    }


    public User findById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
    }

    /**
     * Deducts an amount from the user's wallet, logs transaction, and notifies.
     *
     * @param userId User ID
     * @param amount Amount to deduct
     * @throws IllegalArgumentException if insufficient balance or wallet not found
     */
    @Transactional
    public void deductBalance(Long userId, BigDecimal amount) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));

        if (wallet.getBalance().compareTo(amount) < 0) {
            String message = String.format("Insufficient wallet balance: ₦%.2f needed, ₦%.2f available",
                    amount, wallet.getBalance());
            notificationService.sendNotification(userId.toString(), message, NotificationType.INSUFFICIENT_BALANCE);
            throw new IllegalArgumentException(message);
        }

        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);

        TransactionLog transactionLog = new TransactionLog();
        transactionLog.setUserId(userId);
        transactionLog.setBudgetId(null);
        transactionLog.setAmount(amount);
        transactionLog.setFee(BigDecimal.ZERO);
        transactionLog.setTransactionType(WALLET_DEDUCTION);
        transactionLog.setCreatedAt(LocalDateTime.now());
        transactionLogRepository.save(transactionLog);

        String message = String.format("₦%.2f deducted from wallet for budget creation.", amount);
        notificationService.sendNotification(userId.toString(), message, NotificationType.BUDGET_CREATION_FEE);

        logger.info("Deducted ₦{} from wallet for user {}", amount, userId);
    }

    // WalletService
    public Map<String, String> createVirtualAccount(Long userId) {
        // Mock Paystack virtual account
        String accountNumber = "Tunde/Moniewise-" + userId;
        logger.info("Mock: Created virtual account {} for user {}", accountNumber, userId);
        return new HashMap<>(Map.of(
                "accountNumber", accountNumber,
                "bank", "Moniewise Bank"
        ));
    }

    @PostConstruct
    public void initExistingUsersWallets() {
        List<User> users = userRepository.findAll();
        users.forEach(user -> {
            if (!walletRepository.existsByUserId(user.getId())) {
                Wallet wallet = new Wallet();
                wallet.setId(user.getId());
                wallet.setBalance(BigDecimal.ZERO);
                walletRepository.save(wallet);
                logger.info("Created wallet for existing user {}", user.getId());
            }
        });
    }

    @Transactional
    public Wallet createWalletForUser(User user) {
        // Verify user is managed (has ID)
        if (user.getId() == null) {
            throw new IllegalStateException("User must be saved before creating wallet");
        }

        if (walletRepository.existsByUser(user)) { // Changed to check by User
            throw new IllegalStateException("Wallet already exists");
        }

        Wallet wallet = new Wallet();
        wallet.setUser(user); // Use the managed user
        wallet.setBalance(BigDecimal.ZERO);

        wallet.setCurrency("NGN");
        wallet.setStatus(WalletStatus.ACTIVE);
        wallet.setUpdatedAt(LocalDateTime.now());

        // Generate virtual account (example using Paystack)
        Map<String, String> virtualAccount = paymentGateway.createVirtualAccount(user);
        wallet.setAccountNumber(virtualAccount.get("accountNumber"));
        wallet.setBankName(virtualAccount.get("bank"));

        return walletRepository.save(wallet);
    }

//    @Transactional
//    public void fundWallet(Long userId, BigDecimal amount) {
//        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
//            throw new IllegalArgumentException("Funding amount must be positive");
//        }
//
//        Wallet wallet = walletRepository.findByUserId(userId)
//                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));
//        wallet.setBalance(wallet.getBalance().add(amount));
//        walletRepository.save(wallet);
//
//        TransactionLog transactionLog = new TransactionLog();
//        transactionLog.setUserId(userId);
//        transactionLog.setBudgetId(null);
//        transactionLog.setAmount(amount);
//        transactionLog.setFee(BigDecimal.ZERO);
//        transactionLog.setTransactionType("wallet_deposit");
//        transactionLog.setCreatedAt(LocalDateTime.now());
//        transactionLogRepository.save(transactionLog);
//
//        String message = String.format("Account funded with ₦%.2f!", amount);
//        notificationService.sendNotification(userId.toString(), message);
//
//        logger.info("Funded wallet with ₦{} for user {}", amount, userId);
//    }



    @Transactional
    public void fundWallet(Long userId, BigDecimal amount, String notificationMessage) {
        Optional<Wallet> walletOpt = walletRepository.findByUserId(userId);
        if (walletOpt.isEmpty()) {
            logger.error("Revenue wallet not found for user ID: {}. Fee of ₦{} not credited.", userId, amount);
            return;
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Funding amount must be positive");
        }
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));
        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);
        TransactionLog transactionLog = new TransactionLog();
        transactionLog.setUserId(userId);
        transactionLog.setBudgetId(null);
        transactionLog.setAmount(amount);
        transactionLog.setFee(BigDecimal.ZERO);
        transactionLog.setTransactionType(WALLET_DEPOSIT);
        transactionLog.setCreatedAt(LocalDateTime.now());
        transactionLogRepository.save(transactionLog);
        String message = notificationMessage != null
                ? notificationMessage
                : String.format("Account funded with ₦%.2f!", amount);
        notificationService.sendNotification(userId.toString(), message, NotificationType.WALLET_FUNDED);
        logger.info("Funded wallet with ₦{} for user {}", amount, userId);
    }



    // PaystackService (new)
    public Map<String, String> createDedicatedAccount(Long userId) {
        // Call Paystack API: POST /dedicated_account
        // Return { "account_number": "Tunde/Paystack-Titan", "bank": "Titan Bank" }
        return null;
    }

}
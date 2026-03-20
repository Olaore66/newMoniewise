package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.request.UpdateBankDetailsRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.externalTransfers.PaymentProvider;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEDUCTION;
import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEPOSIT;

@Service
public class WalletService {

    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    // You defined this manually, so we must use "logger" everywhere, not "log"
    private static final Logger logger = LoggerFactory.getLogger(WalletService.class);
    private final WalletRepository walletRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final PaymentProvider paymentProvider;

    private final UserService userService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    @Lazy // Prevents Circular Dependency
    private WalletService self; // 👈 Inject yourself

    public WalletService(WalletRepository walletRepository,
                         TransactionLogRepository transactionLogRepository,
                         NotificationService notificationService, UserRepository userRepository, PaymentProvider paymentProvider, @Lazy UserService userService) {
        this.walletRepository = walletRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.paymentProvider = paymentProvider;
        this.userService = userService;
    }

    public Wallet getWalletByUserId(Long userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));
    }

    @PostConstruct
    @Transactional
    public void ensureRevenueWalletExists() {
        if (walletRepository.findByIsRevenueWalletTrue().isPresent()) {
            return;
        }

        User revenueUser = userRepository.findById(revenueWalletUserId)
                .orElseGet(() -> {
                    logger.info("Creating System Revenue User...");
                    User sysUser = new User();
                    sysUser.setId(revenueWalletUserId);
                    sysUser.setEmail("revenue@moniewise.com");

                    Map<String, Object> profile = new HashMap<>();
                    profile.put("name", "Wisemonie Revenue");
                    sysUser.setProfileData(profile);

                    sysUser.setPassword("SYSTEM_ACCOUNT_LOCKED");
                    return userRepository.save(sysUser);
                });

        Wallet revenueWallet = new Wallet();
        revenueWallet.setUser(revenueUser);
        revenueWallet.setBalance(BigDecimal.ZERO);
        revenueWallet.setCurrency("NGN");
        revenueWallet.setStatus(WalletStatus.ACTIVE);
        revenueWallet.setIsRevenueWallet(true);
        revenueWallet.setUpdatedAt(LocalDateTime.now());

        walletRepository.save(revenueWallet);
        logger.info("Created platform revenue wallet.");
    }

    public BigDecimal checkBalance(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));
        return wallet.getBalance();
    }

    public User findById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
    }

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
        transactionLog.setReference("W-DEC-" + System.currentTimeMillis() + "-" + userId);
        transactionLog.setStatus(TransactionStatus.COMPLETED);
        transactionLog.setCreatedAt(LocalDateTime.now());
        transactionLogRepository.save(transactionLog);

        String message = String.format("₦%.2f deducted from wallet for budget creation.", amount);
        notificationService.sendNotification(
                userId.toString(),
                message,
                NotificationType.BUDGET_CREATION_FEE,
                null,
                null,
                null,
                null
        );

        logger.info("Deducted ₦{} from wallet for user {}", amount, userId);
    }

    @PostConstruct
    public void initExistingUsersWallets() {
        List<User> users = userRepository.findAll();
        users.forEach(user -> {
            if (!walletRepository.existsByUser(user)) {
                try {
                    createWalletForUser(user);
                    logger.info("Initialized missing wallet for user {}", user.getId());
                } catch (Exception e) {
                    logger.error("Failed to init wallet for user {}: {}", user.getId(), e.getMessage());
                }
            }
        });
    }

//     MONNIFY HOW CREATE VIRTUAL ACCOUNT..... SWITCHING TO SECUREWAVE NG SERVICES
    @Transactional
    public Wallet createWalletForUser(User user) {
        if (user.getId() == null) {
            throw new IllegalStateException("User must be saved before creating wallet");
        }

        if (walletRepository.existsByUser(user)) {
            throw new IllegalStateException("Wallet already exists");
        }

        Wallet wallet = new Wallet();
        wallet.setUser(user);
        wallet.setBalance(BigDecimal.ZERO);
        wallet.setCurrency("NGN");
        wallet.setStatus(WalletStatus.ACTIVE);
        wallet.setUpdatedAt(LocalDateTime.now());

        Map<String, String> virtualAccount = paymentProvider.createVirtualAccount(user);
        wallet.setAccountNumber(virtualAccount.get("accountNumber"));
        wallet.setBankName(virtualAccount.get("bank"));

        return walletRepository.save(wallet);
    }


    // 1. The MASTER Method (Does the actual work)
    @Transactional
    public void fundWallet(Long userId, BigDecimal amount, String notificationMessage, boolean suppressLogAndNotification) {

        // --- PART A: Always Run (The Money Move) ---
        Optional<Wallet> walletOpt = walletRepository.findByUserId(userId);
        if (walletOpt.isEmpty()) {
            logger.error("Wallet not found for user ID: {}. Fee of ₦{} not credited.", userId, amount);
            return;
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Funding amount must be positive");
        }

        Wallet wallet = walletOpt.get();
        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        // --- PART B: Conditional (The "Noise") ---
        if (!suppressLogAndNotification) {

            // 1. Create Generic Log
            TransactionLog transactionLog = new TransactionLog();
            transactionLog.setUserId(userId);
            transactionLog.setAmount(amount);
            transactionLog.setFee(BigDecimal.ZERO);
            transactionLog.setTransactionType(WALLET_DEPOSIT);
            transactionLog.setReference("W-DEP-" + System.currentTimeMillis() + "-" + userId);
            transactionLog.setStatus(TransactionStatus.COMPLETED);
            transactionLog.setCreatedAt(LocalDateTime.now());
            transactionLogRepository.save(transactionLog);

            // 2. Send Notification
            String message = notificationMessage != null
                    ? notificationMessage
                    : String.format("Account funded with ₦%.2f!", amount);

            CompletableFuture.runAsync(() -> {
                notificationService.sendNotification(
                        userId.toString(),
                        message,
                        NotificationType.WALLET_FUNDED,
                        null,
                        null,
                        "VIEW_WALLET",
                        "/wallet"
                );
            });
        }

        logger.info("Funded wallet with ₦{} for user {} (Silent: {})", amount, userId, suppressLogAndNotification);
    }

    // 2. The Overload (For Backward Compatibility)
// Keeps existing code working without changing every single call
    public void fundWallet(Long userId, BigDecimal amount, String notificationMessage) {
        fundWallet(userId, amount, notificationMessage, false); // Default: Not Silent
    }

    @Transactional
    public void fundWalletByEmail(String email, BigDecimal amount, String narration) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found with email: " + email));
        fundWallet(user.getId(), amount, narration);
    }

    // 🔴 THIS IS THE FIXED METHOD 🔴

//    @Transactional
//    public void fundWalletFromWebhook(String payloadJson) {
//        try {
//            JsonNode root = objectMapper.readTree(payloadJson);
//            String eventType = root.path("eventType").asText();
//
//            if ("SUCCESSFUL_TRANSACTION".equals(eventType)) { //
//                JsonNode data = root.path("eventData");
//
//                String email = data.path("customer").path("email").asText();
//                BigDecimal amountPaid = data.path("amountPaid").decimalValue();
//                String transactionReference = data.path("transactionReference").asText();
//                String paymentDescription = data.path("paymentDescription").asText(); //
//
//                // 🛡️ DATE PARSING FIX (Matches Monnify Doc: "17/11/2021 3:48:10 PM")
//                LocalDateTime transactionTime = LocalDateTime.now();
//                try {
//                    String paidOn = data.path("paidOn").asText();
//                    if (paidOn != null && !paidOn.isEmpty()) {
//                        try {
//                            // Try Format 1 (Simulator): "2026-01-01 12:00:00.0"
//                            DateTimeFormatter formatter1 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");
//                            transactionTime = LocalDateTime.parse(paidOn, formatter1);
//                        } catch (Exception e1) {
//                            // Try Format 2 (Documentation): "17/11/2021 3:48:10 PM"
//                            DateTimeFormatter formatter2 = DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm:ss a", Locale.ENGLISH);
//                            transactionTime = LocalDateTime.parse(paidOn, formatter2);
//                        }
//                    }
//                } catch (Exception e) {
//                    logger.warn("⚠️ Date parsing failed completely for '{}', using current time.", data.path("paidOn").asText());
//                }
//
//                logger.info("💰 Funding Wallet: User={} Amount={}", email, amountPaid);
//
//                // 1. Find User
//                User user = userRepository.findByEmail(email)
//                        .orElseThrow(() -> new RuntimeException("User not found: " + email));
//
//                // 2. Find/Create Wallet
//                Wallet wallet = walletRepository.findByUser(user)
//                        .orElseGet(() -> createWalletForUser(user));
//
//                // 3. Duplicate Check (Optional but Recommended in Doc)
//                if (transactionLogRepository.existsByReference(transactionReference)) { //
//                    logger.info("⚠️ Transaction {} already processed. Skipping.", transactionReference);
//                    return;
//                }
//
//                // 4. Update Balance
//                wallet.setBalance(wallet.getBalance().add(amountPaid));
//                walletRepository.save(wallet);
//
//                // 5. Create Log
//                TransactionLog transactionLog = new TransactionLog();
//                transactionLog.setUserId(user.getId());
//                transactionLog.setAmount(amountPaid);
//                transactionLog.setTransactionType(com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEPOSIT);
//                transactionLog.setReference(transactionReference);
//                transactionLog.setDescription(paymentDescription);
//                transactionLog.setStatus(com.moniewise.moniewise_backend.enums.TransactionStatus.COMPLETED);
//                transactionLog.setCreatedAt(transactionTime);
//
//                transactionLogRepository.save(transactionLog);
//
//                // 6. Notify
////                notificationService.sendNotification(
////                        user.getId().toString(),
////                        "Wallet funded with ₦" + amountPaid,
////                        NotificationType.WALLET_FUNDED
////                );
//
//                // 6. Notify (FIX: Run Async + Add Route)
//                CompletableFuture.runAsync(() -> {
//                    try {
//                        notificationService.sendNotification(
//                                user.getId().toString(),
//                                String.format("Wallet funded with ₦%.2f", amountPaid),
//                                NotificationType.WALLET_FUNDED,
//                                null,
//                                null,
//                                "VIEW_WALLET", // Ensure the app knows where to go
//                                "/wallet"
//                        );
//                    } catch (Exception e) {
//                        logger.error("Failed to send webhook notification async", e);
//                    }
//                });
//
//                logger.info("✅ Wallet Funded Successfully!");
//            }
//        } catch (Exception e) {
//            logger.error("❌ WEBHOOK CRASHED: ", e);
//            throw new RuntimeException("Webhook failed", e);
//        }
//    }

    // Inside WalletService.java

    // Remove @Transactional from the top-level method
    public void fundWalletFromWebhook(String payloadJson) {
        try {
            // 1. 🏗️ HEAVY LIFTING (Parsing) - Do this OUTSIDE the transaction
            JsonNode root = objectMapper.readTree(payloadJson);
            String eventType = root.path("eventType").asText();

            if (!"SUCCESSFUL_TRANSACTION".equals(eventType)) {
                return;
            }

            JsonNode data = root.path("eventData");
            String email = data.path("customer").path("email").asText();
            BigDecimal amountPaid = data.path("amountPaid").decimalValue();
            String transactionReference = data.path("transactionReference").asText();
            String paymentDescription = data.path("paymentDescription").asText();

            // Date parsing logic...
            LocalDateTime transactionTime = parseTransactionDate(data.path("paidOn").asText());

            // 2. ⚡ ATOMIC TRANSACTION (Fast DB Write)
            // We call a separate private method to handle the DB lock strictly
            this.processSuccessfulFunding(email, amountPaid, transactionReference, paymentDescription, transactionTime);

        } catch (Exception e) {
            logger.error("❌ WEBHOOK CRASHED: ", e);
            throw new RuntimeException("Webhook failed", e);
        }
    }

    // Add this helper method to WalletService.java

    private LocalDateTime parseTransactionDate(String paidOn) {
        if (paidOn == null || paidOn.isEmpty()) {
            return LocalDateTime.now();
        }
        try {
            // Try Format 1 (Simulator/ISO): "2026-01-01 12:00:00.0"
            DateTimeFormatter formatter1 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");
            return LocalDateTime.parse(paidOn, formatter1);
        } catch (Exception e1) {
            try {
                // Try Format 2 (Documentation/Monnify): "17/11/2021 3:48:10 PM"
                DateTimeFormatter formatter2 = DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm:ss a", Locale.ENGLISH);
                return LocalDateTime.parse(paidOn, formatter2);
            } catch (Exception e2) {
                logger.warn("⚠️ Date parsing failed for '{}', using current time.", paidOn);
                return LocalDateTime.now();
            }
        }
    }

    // This method is short, fast, and transactional
    @Transactional
    public void processSuccessfulFunding(String email, BigDecimal amount, String ref, String desc, LocalDateTime time) {
        // 1. Duplicate Check
        if (transactionLogRepository.existsByReference(ref)) {
            logger.info("⚠️ Transaction {} already processed.", ref);
            return;
        }

        // 2. Update Balance
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));

        Wallet wallet = walletRepository.findByUser(user)
                .orElseGet(() -> createWalletForUser(user)); // Note: This might still block if creating wallet, but acceptable for webhook

        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        // 3. Log
        TransactionLog log = new TransactionLog();
        log.setUserId(user.getId());
        log.setAmount(amount);
        log.setTransactionType(WALLET_DEPOSIT);
        log.setReference(ref);
        log.setDescription(desc);
        log.setStatus(TransactionStatus.COMPLETED);
        log.setCreatedAt(time);
        transactionLogRepository.save(log);

        // 4. Notify (Async - Do not block the transaction commit!)
        CompletableFuture.runAsync(() -> {
            notificationService.sendNotification(
                    user.getId().toString(),
                    String.format("Wallet funded with ₦%.2f", amount),
                    NotificationType.WALLET_FUNDED,
                    null, null, "VIEW_WALLET", "/wallet"
            );
        });
    }
    @Transactional
    public Wallet updateSettlementAccount(Long userId, UpdateBankDetailsRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        // 1. 🛡️ SECURITY: Fetch the real account name directly from the bank network
        String resolvedAccountName = paymentProvider.resolveAccount(request.getBankCode(), request.getAccountNumber());

        // (Optional Anti-Fraud Check): You could check if `resolvedAccountName` somewhat matches `user.getName()` here

        // 2. 🌐 Push the verified data to SecureWave
        boolean isUpdated = paymentProvider.updateWithdrawalBankInfo(
                user.getEmail(),
                request.getBankName(),
                resolvedAccountName,
                request.getBankCode(),
                request.getAccountNumber()
        );

        if (!isUpdated) {
            throw new RuntimeException("Payment provider rejected the bank details.");
        }

        // 3. 💾 Save to our database
        wallet.setSettlementAccountNumber(request.getAccountNumber());
        wallet.setSettlementBankCode(request.getBankCode());
        wallet.setSettlementBankName(request.getBankName());
        wallet.setSettlementAccountName(resolvedAccountName); // The verified name!

        logger.info("Successfully updated settlement account for user {}", user.getEmail());

        return walletRepository.save(wallet);
    }

    @Transactional
    public TransactionLog processWithdrawal(Long userId, WithdrawalRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        // 1. 🛡️ VERIFY PIN
        if (!userService.verifyTransactionPin(user, request.getTransactionPin())) {
            throw new IllegalArgumentException("Invalid transaction PIN");
        }

        // 2. 🏦 ENSURE BANK IS SETUP
        if (wallet.getSettlementAccountNumber() == null || wallet.getSettlementBankCode() == null) {
            throw new IllegalStateException("Please link a withdrawal bank account before withdrawing funds.");
        }

        // 3. 💰 CHECK BALANCE
        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new IllegalArgumentException("Insufficient wallet balance.");
        }

        // 4. 🌐 CALL SECUREWAVE API
        String narration = "Wisemonie Withdrawal to " + wallet.getSettlementBankName();
        String secureWaveRef = paymentProvider.initiateWithdrawal(
                user.getEmail(),
                request.getAmount(),
                narration
        );

        // 5. 📉 DEDUCT BALANCE
        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        walletRepository.save(wallet);

        // 6. 📝 RECORD TRANSACTION (Using your exact TransactionLog!)
        TransactionLog logEntry = TransactionLog.builder()
                .userId(userId)
                // Note: budgetId and envelopeId are left null because this is a wallet-level withdrawal
                .externalAccountId(wallet.getSettlementAccountNumber()) // Perfect place to store the destination account!
                .amount(request.getAmount())
                .fee(BigDecimal.ZERO) // Update this if you charge users a withdrawal fee
                .reference(secureWaveRef)
                .status(TransactionStatus.PROCESSING)
                .transactionType(TransactionType.WALLET_WITHDRAWAL) // Ensure WITHDRAWAL is in your TransactionType Enum!
                .description(narration)
                .createdAt(LocalDateTime.now())
                .build();

        // Make sure you inject TransactionLogRepository into your WalletService!
        return transactionLogRepository.save(logEntry);
    }
    @Transactional
    public void debitWalletForWithdrawal(Long userId, BigDecimal amount) {
        Wallet wallet = walletRepository.findByUserId(userId)
                // 👇 It uses IllegalArgumentException here
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds in wallet for this transaction.");
        }
        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);
    }
}


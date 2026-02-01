package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.exception.EntityNotFoundException;
import com.moniewise.moniewise_backend.externalTransfers.PaymentProvider;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.thirdParty.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
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
    private final PaymentGateway paymentGateway;

    private final PaymentProvider paymentProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public WalletService(WalletRepository walletRepository,
                         TransactionLogRepository transactionLogRepository,
                         NotificationService notificationService, UserRepository userRepository, PaymentGateway paymentGateway, PaymentProvider paymentProvider) {
        this.walletRepository = walletRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.paymentGateway = paymentGateway;
        this.paymentProvider = paymentProvider;
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
                    profile.put("name", "Moniewise Revenue");
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

        Map<String, String> virtualAccount = paymentGateway.createVirtualAccount(user);
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

    @Transactional
    public void fundWalletFromWebhook(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            String eventType = root.path("eventType").asText();

            if ("SUCCESSFUL_TRANSACTION".equals(eventType)) { //
                JsonNode data = root.path("eventData");

                String email = data.path("customer").path("email").asText();
                BigDecimal amountPaid = data.path("amountPaid").decimalValue();
                String transactionReference = data.path("transactionReference").asText();
                String paymentDescription = data.path("paymentDescription").asText(); //

                // 🛡️ DATE PARSING FIX (Matches Monnify Doc: "17/11/2021 3:48:10 PM")
                LocalDateTime transactionTime = LocalDateTime.now();
                try {
                    String paidOn = data.path("paidOn").asText();
                    if (paidOn != null && !paidOn.isEmpty()) {
                        try {
                            // Try Format 1 (Simulator): "2026-01-01 12:00:00.0"
                            DateTimeFormatter formatter1 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");
                            transactionTime = LocalDateTime.parse(paidOn, formatter1);
                        } catch (Exception e1) {
                            // Try Format 2 (Documentation): "17/11/2021 3:48:10 PM"
                            DateTimeFormatter formatter2 = DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm:ss a", Locale.ENGLISH);
                            transactionTime = LocalDateTime.parse(paidOn, formatter2);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("⚠️ Date parsing failed completely for '{}', using current time.", data.path("paidOn").asText());
                }

                logger.info("💰 Funding Wallet: User={} Amount={}", email, amountPaid);

                // 1. Find User
                User user = userRepository.findByEmail(email)
                        .orElseThrow(() -> new RuntimeException("User not found: " + email));

                // 2. Find/Create Wallet
                Wallet wallet = walletRepository.findByUser(user)
                        .orElseGet(() -> createWalletForUser(user));

                // 3. Duplicate Check (Optional but Recommended in Doc)
                if (transactionLogRepository.existsByReference(transactionReference)) { //
                    logger.info("⚠️ Transaction {} already processed. Skipping.", transactionReference);
                    return;
                }

                // 4. Update Balance
                wallet.setBalance(wallet.getBalance().add(amountPaid));
                walletRepository.save(wallet);

                // 5. Create Log
                TransactionLog transactionLog = new TransactionLog();
                transactionLog.setUserId(user.getId());
                transactionLog.setAmount(amountPaid);
                transactionLog.setTransactionType(com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEPOSIT);
                transactionLog.setReference(transactionReference);
                transactionLog.setDescription(paymentDescription);
                transactionLog.setStatus(com.moniewise.moniewise_backend.enums.TransactionStatus.COMPLETED);
                transactionLog.setCreatedAt(transactionTime);

                transactionLogRepository.save(transactionLog);

                // 6. Notify
                notificationService.sendNotification(
                        user.getId().toString(),
                        "Wallet funded with ₦" + amountPaid,
                        NotificationType.WALLET_FUNDED
                );

                logger.info("✅ Wallet Funded Successfully!");
            }
        } catch (Exception e) {
            logger.error("❌ WEBHOOK CRASHED: ", e);
            throw new RuntimeException("Webhook failed", e);
        }
    }

    // Inside WalletService.java

    @Transactional(rollbackFor = Exception.class)
    public void withdrawToBank(Long userId, WithdrawalRequest request) {
        // 1. Basic Validation
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive");
        }

        // 2. Fetch Wallet
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException("Wallet not found"));

        // 3. Check Funds (Main Wallet only, ignores Envelopes)
        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new IllegalArgumentException("Insufficient funds in wallet. Available: ₦" + wallet.getBalance());
        }

        // 4. (Optional) Verify Transaction PIN
        // if (!passwordEncoder.matches(request.getPassword(), user.getTransactionPin())) { ... }

        // 5. Deduct Balance (The Debit)
        BigDecimal newBalance = wallet.getBalance().subtract(request.getAmount());
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        // 6. Generate Reference
        String reference = "WTH-" + System.currentTimeMillis() + "-" + userId;

        // 7. Initiate Transfer via Payment Provider
        try {
            // This is the same provider you used in EnvelopeService
            paymentProvider.initiateTransfer(
                    request.getBankCode(),
                    request.getAccountNumber(),
                    request.getAccountName(),
                    request.getAmount(),
                    reference,
                    "Wallet Withdrawal"
            );
        } catch (Exception e) {
            // CRITICAL: If the bank transfer fails, @Transactional will rollback the balance deduction automatically.
            logger.error("Withdrawal failed for user {}: {}", userId, e.getMessage());
            throw new RuntimeException("Bank transfer failed: " + e.getMessage());
        }

        // 8. Log the Transaction
        TransactionLog log = new TransactionLog();
        log.setUserId(userId);
        log.setBudgetId(null); // Not related to a budget
        log.setSourceEnvelopeId(null); // Not related to an envelope
        log.setExternalAccountId(request.getAccountNumber());
        log.setAmount(request.getAmount().negate()); // Negative to show money leaving
        log.setFee(BigDecimal.ZERO); // Add fee logic here if needed (e.g. N10)
        log.setTransactionType(TransactionType.WALLET_WITHDRAWAL); // Ensure this Enum exists!
        log.setReference(reference);
        log.setStatus(TransactionStatus.COMPLETED);
        log.setDescription("Withdrawal to " + request.getAccountName());
        log.setCreatedAt(LocalDateTime.now());

        transactionLogRepository.save(log);

        // 9. Send Notification
        notificationService.sendNotification(
                userId.toString(),
                String.format("Debit Alert: ₦%.2f withdrawn to %s.", request.getAmount(), request.getAccountName()),
                NotificationType.DEBIT_ALERT,
                null, null, "VIEW_WALLET", "/dashboard"
        );
    }
}
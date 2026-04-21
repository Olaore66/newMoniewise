package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.request.UpdateBankDetailsRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.entity.Withdrawal;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.exception.InsufficientFundsException;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.enums.WithdrawalStatus;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.repository.WithdrawalRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEDUCTION;
import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEPOSIT;

@Service
public class WalletService {

    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    private static final Logger logger = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final PaymentGatewayResolver paymentGatewayResolver;
    private final WithdrawalRepository withdrawalRepository;
    private final UserService userService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    @Lazy
    private WalletService self;

    public WalletService(
            WalletRepository walletRepository,
            TransactionLogRepository transactionLogRepository,
            NotificationService notificationService,
            UserRepository userRepository,
            PaymentGatewayResolver paymentGatewayResolver,
            WithdrawalRepository withdrawalRepository,
            @Lazy UserService userService
    ) {
        this.walletRepository = walletRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.paymentGatewayResolver = paymentGatewayResolver;
        this.withdrawalRepository = withdrawalRepository;
        this.userService = userService;
    }

    public Wallet getWalletByUserId(Long userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));
    }
    public Map<String, Object> getLinkedBankInfo(Long userId, String email) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));
        PaymentGateway gateway = paymentGatewayResolver.resolveForWallet(wallet);

        Map<String, Object> providerInfo = null;
        try {
            providerInfo = gateway.getWithdrawalBankInfo(email);
        } catch (RuntimeException e) {
            logger.warn("Falling back to stored settlement account for {}: {}", email, e.getMessage());
        }

        if (providerInfo != null && !providerInfo.isEmpty()) {
            String bankName = safeString(providerInfo.get("bank_name"));
            String bankCode = safeString(providerInfo.get("bank_code"));
            String accountNumber = safeString(providerInfo.get("account_number"));
            String accountName = safeString(providerInfo.get("account_name"));

            wallet.setSettlementBankName(bankName);
            wallet.setSettlementBankCode(bankCode);
            wallet.setSettlementAccountNumber(accountNumber);
            wallet.setSettlementAccountName(accountName);
            walletRepository.save(wallet);

            return providerInfo;
        }

        if (hasStoredSettlementAccount(wallet)) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("bank_name", wallet.getSettlementBankName());
            fallback.put("bank_code", wallet.getSettlementBankCode());
            fallback.put("account_number", wallet.getSettlementAccountNumber());
            fallback.put("account_name", wallet.getSettlementAccountName());
            return fallback;
        }

        return Map.of();
    }


    @PostConstruct
    @Transactional
    public void ensureRevenueWalletExists() {
        if (walletRepository.findByRevenueWalletTrue().isPresent()) {
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
        revenueWallet.setRevenueWallet(true);
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
            String message = String.format(
                    "Insufficient wallet balance: ₦%.2f needed, ₦%.2f available",
                    amount,
                    wallet.getBalance()
            );
            notificationService.sendNotification(userId.toString(), message, NotificationType.INSUFFICIENT_BALANCE);
            throw new InsufficientFundsException(message);
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

//    @Transactional
//    public Wallet createWalletForUser(User user) {
//        if (user.getId() == null) {
//            throw new IllegalStateException("User must be saved before creating wallet");
//        }
//
//        if (walletRepository.existsByUser(user)) {
//            throw new IllegalStateException("Wallet already exists");
//        }
//
//        Wallet wallet = new Wallet();
//        wallet.setUser(user);
//        wallet.setBalance(BigDecimal.ZERO);
//        wallet.setCurrency("NGN");
//        wallet.setStatus(WalletStatus.ACTIVE);
//        wallet.setUpdatedAt(LocalDateTime.now());
//
//        Map<String, String> virtualAccount = paymentProvider.createVirtualAccount(user);
//        wallet.setAccountNumber(virtualAccount.get("accountNumber"));
//        wallet.setBankName(virtualAccount.get("bank"));
//
//        return walletRepository.save(wallet);
//    }

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
        wallet.setProviderStatus("PENDING");

        wallet = walletRepository.save(wallet);

        PaymentGateway gateway = paymentGatewayResolver.resolveDefault();
        Map<String, String> virtualAccount = gateway.createVirtualAccount(user);

        wallet.setAccountNumber(virtualAccount.get("accountNumber"));
        wallet.setBankName(virtualAccount.get("bank"));

        // optional provider fields: set only if returned
        wallet.setProviderName(virtualAccount.getOrDefault("providerName", gateway.getProviderName()));
        wallet.setProviderCustomerRef(virtualAccount.get("providerCustomerRef"));
        wallet.setProviderWalletRef(virtualAccount.get("providerWalletRef"));
        wallet.setMasterWalletRef(virtualAccount.get("masterWalletRef"));
        wallet.setSubWalletRef(virtualAccount.get("subWalletRef"));
        wallet.setProviderStatus("ACTIVE");
        wallet.setLastBalanceSyncAt(LocalDateTime.now());

        return walletRepository.save(wallet);
    }

    @Transactional
    public void fundWallet(Long userId, BigDecimal amount, String notificationMessage, boolean suppressLogAndNotification) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Funding amount must be positive");
        }

        String internalRef = "INT-" + System.currentTimeMillis() + "-" + userId;

        this.processSuccessfulFunding(
                user.getEmail(),
                amount,
                amount,
                BigDecimal.ZERO,
                internalRef,
                notificationMessage != null ? notificationMessage : "Wallet Deposit",
                LocalDateTime.now()
        );

        logger.info("Internal Wallet Funding triggered for user {}", userId);
    }

    public void fundWallet(Long userId, BigDecimal amount, String notificationMessage) {
        fundWallet(userId, amount, notificationMessage, false);
    }

    public void fundWalletFromWebhook(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            String eventType = root.path("eventType").asText(null);
            String notificationStatus = root.path("notification_status").asText(null);

            if ("SUCCESSFUL_TRANSACTION".equalsIgnoreCase(eventType)) {
                JsonNode data = root.path("eventData");
                String email = data.path("customer").path("email").asText();
                BigDecimal amountPaid = data.path("amountPaid").decimalValue();
                String transactionReference = data.path("transactionReference").asText();
                String paymentDescription = data.path("paymentDescription").asText();
                LocalDateTime transactionTime = parseTransactionDate(data.path("paidOn").asText());

                this.processSuccessfulFunding(
                        email,
                        amountPaid,
                        amountPaid,
                        BigDecimal.ZERO,
                        transactionReference,
                        paymentDescription,
                        transactionTime
                );
                return;
            }

            if ("payment_successful".equalsIgnoreCase(notificationStatus)) {
                String email = root.path("customer").path("email").asText();
                BigDecimal grossAmount = decimalFromNode(root.path("amount"));
                BigDecimal fee = decimalFromNode(root.path("fees"));
                BigDecimal netAmount = root.hasNonNull("settlement_amount")
                        ? decimalFromNode(root.path("settlement_amount"))
                        : grossAmount.subtract(fee);
                String transactionReference = root.path("transaction_id").asText();
                String paymentDescription = String.format("Deposit of NGN %s", grossAmount);

                this.processSuccessfulFunding(
                        email,
                        netAmount,
                        grossAmount,
                        fee,
                        transactionReference,
                        paymentDescription,
                        LocalDateTime.now()
                );
            }
        } catch (Exception e) {
            logger.error("Webhook crashed", e);
            throw new RuntimeException("Webhook failed", e);
        }
    }

    public List<Map<String, Object>> getSupportedBanks(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElse(null);
        PaymentGateway gateway = wallet != null
                ? paymentGatewayResolver.resolveForWallet(wallet)
                : paymentGatewayResolver.resolveDefault();
        return gateway.getSupportedBanks();
    }

    public String resolveBankAccount(Long userId, String bankCode, String accountNumber) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElse(null);
        PaymentGateway gateway = wallet != null
                ? paymentGatewayResolver.resolveForWallet(wallet)
                : paymentGatewayResolver.resolveDefault();
        return gateway.resolveAccount(bankCode, accountNumber);
    }

    private LocalDateTime parseTransactionDate(String paidOn) {
        if (paidOn == null || paidOn.isEmpty()) {
            return LocalDateTime.now();
        }
        try {
            DateTimeFormatter formatter1 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");
            return LocalDateTime.parse(paidOn, formatter1);
        } catch (Exception e1) {
            try {
                DateTimeFormatter formatter2 = DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm:ss a", Locale.ENGLISH);
                return LocalDateTime.parse(paidOn, formatter2);
            } catch (Exception e2) {
                logger.warn("Date parsing failed for '{}', using current time.", paidOn);
                return LocalDateTime.now();
            }
        }
    }

    @Transactional
    public void processSuccessfulFunding(
            String email,
            BigDecimal netAmount,
            BigDecimal grossAmount,
            BigDecimal fee,
            String ref,
            String desc,
            LocalDateTime time
    ) {
        if (transactionLogRepository.existsByReference(ref)) {
            logger.info("Transaction {} already processed.", ref);
            return;
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));

        Wallet wallet = walletRepository.findByUser(user)
                .orElseGet(() -> createWalletForUser(user));

        wallet.setBalance(wallet.getBalance().add(netAmount));
        wallet.setUpdatedAt(LocalDateTime.now());
        wallet.setLastBalanceSyncAt(LocalDateTime.now());
        walletRepository.save(wallet);

        TransactionLog log = new TransactionLog();
        log.setUserId(user.getId());
        log.setAmount(netAmount);
        log.setFee(fee);
        log.setTransactionType(WALLET_DEPOSIT);
        log.setReference(ref);
        log.setDescription(desc + " | Gross: ₦" + grossAmount);
        log.setStatus(TransactionStatus.COMPLETED);
        log.setCreatedAt(time);
        transactionLogRepository.save(log);

        CompletableFuture.runAsync(() -> {
            try {
                String alertMessage = String.format(
                        "Wallet funded with ₦%.2f. (₦%.2f deposit fee applied)",
                        netAmount,
                        fee
                );
                notificationService.sendNotification(
                        user.getId().toString(),
                        alertMessage,
                        NotificationType.WALLET_FUNDED,
                        null,
                        null,
                        "VIEW_WALLET",
                        "/wallet"
                );
            } catch (Exception e) {
                logger.error("Failed to send credit alert", e);
            }
        });
    }

    private boolean hasStoredSettlementAccount(Wallet wallet) {
        return wallet.getSettlementAccountNumber() != null && !wallet.getSettlementAccountNumber().isBlank()
                && wallet.getSettlementBankName() != null && !wallet.getSettlementBankName().isBlank();
    }

    private String safeString(Object value) {
        return value == null ? "" : value.toString().trim();
    }
    @Transactional
    public Wallet updateSettlementAccount(Long userId, UpdateBankDetailsRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));
        PaymentGateway gateway = paymentGatewayResolver.resolveForWallet(wallet);

        String resolvedAccountName = gateway.resolveAccount(request.getBankCode(), request.getAccountNumber());

        boolean isUpdated = gateway.updateWithdrawalBankInfo(
                user.getEmail(),
                request.getBankName(),
                resolvedAccountName,
                request.getBankCode(),
                request.getAccountNumber()
        );

        if (!isUpdated) {
            throw new RuntimeException("Payment provider rejected the bank details.");
        }

        wallet.setSettlementAccountNumber(request.getAccountNumber());
        wallet.setSettlementBankCode(request.getBankCode());
        wallet.setSettlementBankName(request.getBankName());
        wallet.setSettlementAccountName(resolvedAccountName);

        logger.info("Successfully updated settlement account for user {}", user.getEmail());

        return walletRepository.save(wallet);
    }

    public Withdrawal processWithdrawal(Long userId, WithdrawalRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        if (!userService.verifyTransactionPin(user, request.getTransactionPin())) {
            throw new IllegalArgumentException("Invalid transaction PIN");
        }

        if (wallet.getSettlementAccountNumber() == null || wallet.getSettlementBankCode() == null) {
            throw new IllegalStateException("Please link a withdrawal bank account before withdrawing funds.");
        }

        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new IllegalArgumentException("Insufficient wallet balance.");
        }

        PaymentGateway gateway = paymentGatewayResolver.resolveForWallet(wallet);
        String narration = buildWithdrawalNarration(wallet, request);
        Withdrawal withdrawal = createWithdrawalRecord(user, wallet, request, narration);
        self.reserveWithdrawalForProvider(withdrawal.getId());

        String providerReference;
        try {
            providerReference = gateway.initiateWithdrawal(
                    user.getEmail(),
                    request.getAmount(),
                    narration
            );
        } catch (RuntimeException e) {
            self.markWithdrawalFailed(withdrawal.getId(), e.getMessage());
            throw e;
        }

        return self.finalizeAcceptedWithdrawal(withdrawal.getId(), providerReference);
    }

    @Transactional
    public void debitWalletForWithdrawal(Long userId, BigDecimal amount) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds in wallet for this transaction.");
        }
        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);
    }

    @Transactional(readOnly = true)
    public List<Withdrawal> getRecentWithdrawals(Long userId) {
        return withdrawalRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public Withdrawal finalizeAcceptedWithdrawal(Long withdrawalId, String providerReference) {
        Withdrawal withdrawal = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new IllegalArgumentException("Withdrawal not found"));

        withdrawal.setProviderReference(providerReference);
        withdrawal.setStatus(WithdrawalStatus.PROCESSING);
        withdrawal.setProcessedAt(LocalDateTime.now());
        withdrawal.setFailureReason(null);
        return withdrawalRepository.save(withdrawal);
    }

    @Transactional
    public void markWithdrawalFailed(Long withdrawalId, String reason) {
        withdrawalRepository.findById(withdrawalId).ifPresent(withdrawal -> {
            if (withdrawal.getStatus() == WithdrawalStatus.FAILED || withdrawal.getStatus() == WithdrawalStatus.REVERSED) {
                return;
            }

            Wallet wallet = walletRepository.findByUserIdForUpdate(withdrawal.getUserId())
                    .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

            wallet.setBalance(wallet.getBalance().add(withdrawal.getAmount()));
            wallet.setUpdatedAt(LocalDateTime.now());
            walletRepository.save(wallet);

            transactionLogRepository.findByReference(withdrawal.getClientReference()).ifPresent(logEntry -> {
                logEntry.setStatus(TransactionStatus.FAILED);
                logEntry.setDescription(withdrawal.getNarration() + " | Failed: " + reason);
                transactionLogRepository.save(logEntry);
            });

            withdrawal.setStatus(WithdrawalStatus.FAILED);
            withdrawal.setFailureReason(reason);
            withdrawal.setProcessedAt(LocalDateTime.now());
            withdrawalRepository.save(withdrawal);
        });
    }

    @Transactional
    public void reserveWithdrawalForProvider(Long withdrawalId) {
        Withdrawal withdrawal = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new IllegalArgumentException("Withdrawal not found"));

        Wallet wallet = walletRepository.findByUserIdForUpdate(withdrawal.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        if (wallet.getBalance().compareTo(withdrawal.getAmount()) < 0) {
            throw new IllegalArgumentException("Insufficient wallet balance.");
        }

        wallet.setBalance(wallet.getBalance().subtract(withdrawal.getAmount()));
        wallet.setUpdatedAt(LocalDateTime.now());
        wallet.setLastBalanceSyncAt(LocalDateTime.now());
        walletRepository.save(wallet);

        if (transactionLogRepository.findByReference(withdrawal.getClientReference()).isEmpty()) {
            TransactionLog logEntry = TransactionLog.builder()
                    .userId(withdrawal.getUserId())
                    .externalAccountId(withdrawal.getAccountNumber())
                    .amount(withdrawal.getAmount())
                    .fee(BigDecimal.ZERO)
                    .reference(withdrawal.getClientReference())
                    .status(TransactionStatus.PROCESSING)
                    .transactionType(TransactionType.WALLET_WITHDRAWAL)
                    .description(withdrawal.getNarration())
                    .createdAt(LocalDateTime.now())
                    .build();
            transactionLogRepository.save(logEntry);
        }
    }

    private Withdrawal createWithdrawalRecord(User user, Wallet wallet, WithdrawalRequest request, String narration) {
        Withdrawal withdrawal = new Withdrawal();
        withdrawal.setUserId(user.getId());
        withdrawal.setWalletId(wallet.getId());
        withdrawal.setAmount(request.getAmount());
        withdrawal.setCurrency(wallet.getCurrency());
        withdrawal.setNarration(narration);
        withdrawal.setBankName(wallet.getSettlementBankName());
        withdrawal.setBankCode(wallet.getSettlementBankCode());
        withdrawal.setAccountNumber(wallet.getSettlementAccountNumber());
        withdrawal.setAccountName(wallet.getSettlementAccountName());
        withdrawal.setClientReference(buildClientReference(user.getId()));
        withdrawal.setStatus(WithdrawalStatus.INITIATED);
        withdrawal.setCreatedAt(LocalDateTime.now());
        return withdrawalRepository.save(withdrawal);
    }

    private String buildWithdrawalNarration(Wallet wallet, WithdrawalRequest request) {
        if (request.getNarration() != null && !request.getNarration().isBlank()) {
            return request.getNarration().trim();
        }
        return "Wisemonie Withdrawal to " + wallet.getSettlementBankName();
    }

    private String buildClientReference(Long userId) {
        return "WD-" + userId + "-" + System.currentTimeMillis();
    }

    @Transactional
    public Wallet attachProviderMapping(
            Long userId,
            String providerName,
            String providerCustomerRef,
            String providerWalletRef,
            String masterWalletRef,
            String subWalletRef,
            String providerStatus,
            String providerMetadata
    ) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));

        wallet.setProviderName(providerName);
        wallet.setProviderCustomerRef(providerCustomerRef);
        wallet.setProviderWalletRef(providerWalletRef);
        wallet.setMasterWalletRef(masterWalletRef);
        wallet.setSubWalletRef(subWalletRef);
        wallet.setProviderStatus(providerStatus);
        wallet.setProviderMetadata(providerMetadata);
        wallet.setLastBalanceSyncAt(LocalDateTime.now());

        return walletRepository.save(wallet);
    }

    @Transactional
    public Wallet updateProviderStatus(Long walletId, String providerStatus) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        wallet.setProviderStatus(providerStatus);
        wallet.setLastBalanceSyncAt(LocalDateTime.now());

        return walletRepository.save(wallet);
    }

    @Transactional
    public Wallet updateBalanceFromProvider(Long walletId, BigDecimal newBalance) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        wallet.setBalance(newBalance);
        wallet.setLastBalanceSyncAt(LocalDateTime.now());
        wallet.setUpdatedAt(LocalDateTime.now());

        return walletRepository.save(wallet);
    }

    public Wallet getWalletByProviderWalletRef(String providerWalletRef) {
        return walletRepository.findByProviderWalletRef(providerWalletRef)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for provider wallet ref: " + providerWalletRef));
    }

    public Wallet getWalletBySubWalletRef(String subWalletRef) {
        return walletRepository.findBySubWalletRef(subWalletRef)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for sub-wallet ref: " + subWalletRef));
    }

    private BigDecimal decimalFromNode(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(node.asText());
    }
}




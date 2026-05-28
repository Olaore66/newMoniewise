package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.TransferFeeQuote;
import com.moniewise.moniewise_backend.dto.request.UpdateBankDetailsRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.dto.response.WithdrawalQuoteResponse;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.entity.Withdrawal;
import com.moniewise.moniewise_backend.enums.*;
import com.moniewise.moniewise_backend.exception.InsufficientFundsException;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.ProvidusExpressGateway;
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
import java.util.*;
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
    private final ProvidusExpressGateway providusExpressGateway;
    private final WithdrawalRepository withdrawalRepository;
    private final UserService userService;
    private final WithdrawalFeeService withdrawalFeeService;

    private final TransferFeeService transferFeeService;

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
            ProvidusExpressGateway providusExpressGateway,
            WithdrawalRepository withdrawalRepository,
            @Lazy UserService userService,
            WithdrawalFeeService withdrawalFeeService,
            TransferFeeService transferFeeService) {
        this.walletRepository = walletRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.paymentGatewayResolver = paymentGatewayResolver;
        this.providusExpressGateway = providusExpressGateway;
        this.withdrawalRepository = withdrawalRepository;
        this.userService = userService;
        this.withdrawalFeeService = withdrawalFeeService;
        this.transferFeeService = transferFeeService;
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
                BigDecimal grossAmount = data.path("amountPaid").decimalValue();

                TransferFeeQuote feeQuote = transferFeeService.quoteIncomingFee(
                        "SECUREWAVE",
                        TransferFeeTransferType.WALLET_DEPOSIT,
                        grossAmount
                );

                BigDecimal fee = feeQuote.getFee();
                BigDecimal netAmount = feeQuote.getRecipientReceives();

                String transactionReference = data.path("transactionReference").asText();
                String paymentDescription = data.path("paymentDescription").asText();
                LocalDateTime transactionTime = parseTransactionDate(data.path("paidOn").asText());

                this.processSuccessfulFunding(
                        email,
                        netAmount,
                        grossAmount,
                        fee,
                        transactionReference,
                        paymentDescription,
                        transactionTime
                );
                return;
            }

            if ("payment_successful".equalsIgnoreCase(notificationStatus)) {
                String email = root.path("customer").path("email").asText();
                BigDecimal grossAmount = decimalFromNode(root.path("amount"));

                TransferFeeQuote feeQuote = transferFeeService.quoteIncomingFee(
                        "SECUREWAVE",
                        TransferFeeTransferType.WALLET_DEPOSIT,
                        grossAmount
                );

                BigDecimal fee = feeQuote.getFee();
                BigDecimal netAmount = feeQuote.getRecipientReceives();

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

    // ─────────────────────────────────────────────────────────────────────────
    // Bank-list resilience — three-layer fallback so /wallets/banks NEVER
    // returns 503 in production:
    //   1. Fresh upstream call  → update stale cache, return results.
    //   2. Upstream fails/empty → serve last known-good in-memory cache.
    //   3. No stale cache (cold start with upstream down) → return the
    //      embedded static list of Nigerian banks.  Bank codes are stable;
    //      this list covers all major institutions and keeps the withdrawal
    //      flow open even during full upstream outages.
    // ─────────────────────────────────────────────────────────────────────────

    /** In-memory stale-on-error cache.  Volatile for cross-thread visibility. */
    private volatile List<Map<String, Object>> _lastKnownBanks = null;

    /** Embedded static Nigerian bank list — the last-resort fallback. */
    private static final List<Map<String, Object>> NIGERIAN_BANK_FALLBACK;
    static {
        List<Map<String, Object>> b = new ArrayList<>();
        b.add(bankEntry(1,  "Access Bank",                 "access",     "044"));
        b.add(bankEntry(2,  "Citibank Nigeria",             "citibank",   "023"));
        b.add(bankEntry(3,  "Ecobank Nigeria",              "ecobank",    "050"));
        b.add(bankEntry(4,  "First Bank of Nigeria",        "firstbank",  "011"));
        b.add(bankEntry(5,  "First City Monument Bank",     "fcmb",       "214"));
        b.add(bankEntry(6,  "Fidelity Bank",                "fidelity",   "070"));
        b.add(bankEntry(7,  "Guaranty Trust Bank",          "gtbank",     "058"));
        b.add(bankEntry(8,  "Heritage Bank",                null,         "030"));
        b.add(bankEntry(9,  "Jaiz Bank",                    null,         "301"));
        b.add(bankEntry(10, "Keystone Bank",                "keystone",   "082"));
        b.add(bankEntry(11, "Polaris Bank",                 "polaris",    "076"));
        b.add(bankEntry(12, "Stanbic IBTC Bank",            "stanbic",    "039"));
        b.add(bankEntry(13, "Sterling Bank",                "sterling",   "232"));
        b.add(bankEntry(14, "Union Bank of Nigeria",        "unionbank",  "032"));
        b.add(bankEntry(15, "United Bank for Africa",       "uba",        "033"));
        b.add(bankEntry(16, "Unity Bank",                   null,         "215"));
        b.add(bankEntry(17, "Wema Bank",                    "wema",       "035"));
        b.add(bankEntry(18, "Zenith Bank",                  "zenith",     "057"));
        b.add(bankEntry(19, "Kuda Microfinance Bank",       "kuda",       "999991"));
        b.add(bankEntry(20, "OPay",                         null,         "100004"));
        b.add(bankEntry(21, "PalmPay",                      null,         "999992"));
        b.add(bankEntry(22, "Moniepoint Microfinance Bank", "moniepoint", "50515"));
        b.add(bankEntry(23, "VFD Microfinance Bank",        "vfd",        "566"));
        b.add(bankEntry(24, "Providus Bank",                "providus",   "101"));
        b.add(bankEntry(25, "Standard Chartered Bank",      "scb",        "068"));
        b.add(bankEntry(26, "Coronation Merchant Bank",     null,         "559"));
        b.add(bankEntry(27, "Parallex Bank",                null,         "526"));
        b.add(bankEntry(28, "Titan Trust Bank",             "titan",      "102"));
        b.add(bankEntry(29, "Lotus Bank",                   null,         "303"));
        b.add(bankEntry(30, "Carbon (One Finance)",         "carbon",     "565"));
        NIGERIAN_BANK_FALLBACK = Collections.unmodifiableList(b);
    }

    /** Builds one bank map in the format expected by Flutter's Bank.fromJson. */
    private static Map<String, Object> bankEntry(int id, String name, String alias, String bankCode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        if (alias != null) m.put("alias", alias);
        m.put("bank_code", bankCode);
        return m;
    }

    public List<Map<String, Object>> getSupportedBanks(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId).orElse(null);
        PaymentGateway gateway = wallet != null
                ? paymentGatewayResolver.resolveForWallet(wallet)
                : paymentGatewayResolver.resolveDefault();

        // Layer 1: try fresh upstream call (gateway already swallows HTTP errors
        // and returns [], but wrap in try-catch for any unexpected runtime exceptions).
        List<Map<String, Object>> fresh;
        try {
            fresh = gateway.getSupportedBanks();
        } catch (Exception e) {
            logger.warn("Bank list upstream threw exception — will try stale/static fallback: {}", e.getMessage());
            fresh = Collections.emptyList();
        }

        if (fresh != null && !fresh.isEmpty()) {
            _lastKnownBanks = fresh;   // refresh the stale-on-error cache
            return fresh;
        }

        // Layer 2: stale in-memory cache (populated from a previous successful call
        // within this JVM lifetime — survives transient upstream blips but not restarts).
        if (_lastKnownBanks != null && !_lastKnownBanks.isEmpty()) {
            logger.warn("Bank list upstream empty — serving stale in-memory cache ({} banks)", _lastKnownBanks.size());
            return _lastKnownBanks;
        }

        // Layer 3: static embedded list — cold start with upstream already down.
        // Nigerian bank codes are stable; this list covers all major institutions.
        // The 503 path in WalletController is now effectively unreachable.
        logger.warn("No upstream data and no stale cache — serving static Nigerian bank fallback ({} banks)",
                NIGERIAN_BANK_FALLBACK.size());
        return NIGERIAN_BANK_FALLBACK;
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
        log.setFee(fee != null ? fee : BigDecimal.ZERO);
        log.setTransactionType(WALLET_DEPOSIT);
        log.setReference(ref);
        log.setDescription(desc + " | Gross: ₦" + grossAmount);
        log.setStatus(TransactionStatus.COMPLETED);
        log.setCreatedAt(time);
        transactionLogRepository.save(log);

        if (fee != null && fee.compareTo(BigDecimal.ZERO) > 0) {
            TransactionLog feeLog = new TransactionLog();
            feeLog.setUserId(user.getId());
            feeLog.setAmount(fee.negate());
            feeLog.setFee(BigDecimal.ZERO);
            feeLog.setTransactionType(TransactionType.WALLET_DEPOSIT_FEE);
            feeLog.setReference(ref + "-FEE");
            feeLog.setDescription("Deposit fee for " + ref);
            feeLog.setStatus(TransactionStatus.COMPLETED);
            feeLog.setCreatedAt(time);
            transactionLogRepository.save(feeLog);
        }

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

        BigDecimal withdrawalFee = withdrawalFeeService.calculateWithdrawalFee(request.getAmount());
        BigDecimal totalDebit = withdrawalFeeService.calculateTotalDebit(request.getAmount());

        if (wallet.getBalance().compareTo(totalDebit) < 0) {
            throw new IllegalArgumentException(insufficientWithdrawalBalanceMessage(totalDebit, withdrawalFee));
        }

        PaymentGateway gateway = paymentGatewayResolver.resolveForWallet(wallet);
        String narration = buildWithdrawalNarration(wallet, request);
        Withdrawal withdrawal = createWithdrawalRecord(user, wallet, request, narration, withdrawalFee, totalDebit);
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

    public WithdrawalQuoteResponse quoteWithdrawal(BigDecimal amount) {
        BigDecimal fee = withdrawalFeeService.calculateWithdrawalFee(amount);
        BigDecimal totalDebit = withdrawalFeeService.calculateTotalDebit(amount);
        return new WithdrawalQuoteResponse(
                amount,
                fee,
                totalDebit,
                amount,
                "FLAT_WITHDRAWAL_FEE",
                "USER_BALANCE",
                String.format("A ₦%s withdrawal fee applies. ₦%s will be deducted from your available balance.",
                        formatMoney(fee),
                        formatMoney(totalDebit))
        );
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
        transactionLogRepository.findByReference(withdrawal.getClientReference()).ifPresent(logEntry -> {
            logEntry.setProviderReference(providerReference);
            logEntry.setStatus(TransactionStatus.PROCESSING);
            transactionLogRepository.save(logEntry);
        });
        transactionLogRepository.findByReference(buildWithdrawalFeeReference(withdrawal)).ifPresent(logEntry -> {
            logEntry.setProviderReference(providerReference);
            logEntry.setStatus(TransactionStatus.PROCESSING);
            transactionLogRepository.save(logEntry);
        });
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

            wallet.setBalance(wallet.getBalance().add(withdrawal.getTotalDebit()));
            wallet.setUpdatedAt(LocalDateTime.now());
            walletRepository.save(wallet);

            transactionLogRepository.findByReference(withdrawal.getClientReference()).ifPresent(logEntry -> {
                logEntry.setStatus(TransactionStatus.FAILED);
                logEntry.setDescription(withdrawal.getNarration() + " | Failed: " + reason + " | Amount and withdrawal fee reversed.");
                transactionLogRepository.save(logEntry);
            });

            transactionLogRepository.findByReference(buildWithdrawalFeeReference(withdrawal)).ifPresent(logEntry -> {
                logEntry.setStatus(TransactionStatus.FAILED);
                logEntry.setDescription("Withdrawal fee reversed for " + withdrawal.getClientReference());
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

        BigDecimal fee = withdrawal.getFeeAmount() != null ? withdrawal.getFeeAmount() : BigDecimal.ZERO;
        BigDecimal totalDebit = withdrawal.getTotalDebit();
        if (wallet.getBalance().compareTo(totalDebit) < 0) {
            throw new IllegalArgumentException(insufficientWithdrawalBalanceMessage(totalDebit, fee));
        }

        wallet.setBalance(wallet.getBalance().subtract(totalDebit));
        wallet.setUpdatedAt(LocalDateTime.now());
        wallet.setLastBalanceSyncAt(LocalDateTime.now());
        walletRepository.save(wallet);

        if (transactionLogRepository.findByReference(withdrawal.getClientReference()).isEmpty()) {
            TransactionLog logEntry = TransactionLog.builder()
                    .userId(withdrawal.getUserId())
                    .externalAccountId(withdrawal.getAccountNumber())
                    .amount(withdrawal.getAmount())
                    .fee(fee)
                    .reference(withdrawal.getClientReference())
                    .status(TransactionStatus.PROCESSING)
                    .transactionType(TransactionType.WALLET_WITHDRAWAL)
                    .description(withdrawal.getNarration() + " | Recipient receives ₦" + formatMoney(withdrawal.getRecipientReceives()))
                    .createdAt(LocalDateTime.now())
                    .build();
            transactionLogRepository.save(logEntry);
        }

        String feeReference = buildWithdrawalFeeReference(withdrawal);
        if (fee.compareTo(BigDecimal.ZERO) > 0 && transactionLogRepository.findByReference(feeReference).isEmpty()) {
            TransactionLog feeLogEntry = TransactionLog.builder()
                    .userId(withdrawal.getUserId())
                    .externalAccountId(withdrawal.getAccountNumber())
                    .amount(fee)
                    .fee(BigDecimal.ZERO)
                    .reference(feeReference)
                    .status(TransactionStatus.PROCESSING)
                    .transactionType(TransactionType.WALLET_WITHDRAWAL_FEE)
                    .description("Withdrawal fee recovery for " + withdrawal.getClientReference())
                    .createdAt(LocalDateTime.now())
                    .build();
            transactionLogRepository.save(feeLogEntry);
        }
    }

    private Withdrawal createWithdrawalRecord(User user,
                                              Wallet wallet,
                                              WithdrawalRequest request,
                                              String narration,
                                              BigDecimal fee,
                                              BigDecimal totalDebit) {
        Withdrawal withdrawal = new Withdrawal();
        withdrawal.setUserId(user.getId());
        withdrawal.setWalletId(wallet.getId());
        withdrawal.setAmount(request.getAmount());
        withdrawal.setFeeAmount(fee);
        withdrawal.setTotalDebit(totalDebit);
        withdrawal.setRecipientReceives(request.getAmount());
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

    private String buildWithdrawalFeeReference(Withdrawal withdrawal) {
        return withdrawal.getClientReference() + "-FEE";
    }

    private String insufficientWithdrawalBalanceMessage(BigDecimal totalDebit, BigDecimal fee) {
        return String.format("Insufficient balance. You need ₦%s including the ₦%s withdrawal fee.",
                formatMoney(totalDebit),
                formatMoney(fee));
    }

    private String formatMoney(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
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
//            String providerMetadata
            Map<String, Object> providerMetadata
    ) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));

        wallet.setProviderName(providerName);
        wallet.setProviderCustomerRef(providerCustomerRef);
        wallet.setProviderWalletRef(providerWalletRef);
        wallet.setMasterWalletRef(masterWalletRef);
        wallet.setSubWalletRef(subWalletRef);
        wallet.setProviderStatus(providerStatus);
        wallet.setProviderMetadata(providerMetadata != null ? providerMetadata : new HashMap<>());
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

    public Map<String, Object> getProviderTransactions(Long userId, int page, int perPage) {
        Wallet wallet = getProvidusWalletForUser(userId);
        return providusExpressGateway.getCustomerTransactions(wallet.getProviderCustomerRef(), page, perPage);
    }

    public Map<String, Object> getProviderTransactionDetails(Long userId, String transactionReference) {
        getProvidusWalletForUser(userId);
        return providusExpressGateway.getTransactionDetails(transactionReference);
    }

    private Wallet getProvidusWalletForUser(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));

        if (!providusExpressGateway.isEnabled()) {
            throw new IllegalStateException("Providus integration is disabled.");
        }

        if (wallet.getProviderName() == null
                || !wallet.getProviderName().equalsIgnoreCase(ProvidusExpressGateway.PROVIDER_NAME)) {
            throw new IllegalStateException("This wallet is not a Providus wallet.");
        }

        if (wallet.getProviderCustomerRef() == null || wallet.getProviderCustomerRef().isBlank()) {
            throw new IllegalStateException("Providus customer reference is missing for this wallet.");
        }

        return wallet;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Providus webhook processors
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Processes an incoming deposit/credit webhook event from Providus.
     *
     * <p>Because Providus has not yet shared their exact payload structure, this
     * method tries multiple common field name patterns to extract the recipient's
     * email/account, the credited amount, and the transaction reference.  The raw
     * payload is already logged at INFO level by {@code WebhookService} before this
     * is called, so once the first live webhook arrives the exact field names can be
     * confirmed and this method can be narrowed down.
     */
    @Transactional
    public void fundWalletFromProvidusWebhook(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);

            // ── 1. Extract recipient identity ──────────────────────────────────
            String email = extractProvidusEmail(root);

            // If no email in payload, fall back to account-number lookup
            if (email == null || email.isBlank()) {
                String accountNumber = extractProvidusAccountNumber(root);
                if (accountNumber != null && !accountNumber.isBlank()) {
                    Wallet found = walletRepository.findByAccountNumber(accountNumber).orElse(null);
                    if (found != null && found.getUser() != null) {
                        email = found.getUser().getEmail();
                        logger.info("[PROVIDUS-WEBHOOK] Resolved email {} from accountNumber {}", email, accountNumber);
                    }
                }
            }

            if (email == null || email.isBlank()) {
                logger.error("[PROVIDUS-WEBHOOK] Cannot determine recipient from payload — manual review required:\n{}", payloadJson);
                throw new RuntimeException("Providus deposit webhook: could not identify recipient email or account number");
            }

            // ── 2. Extract amount ──────────────────────────────────────────────
            BigDecimal amount = extractProvidusAmount(root);
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("Providus deposit webhook: could not extract a positive amount");
            }

            // ── 3. Extract reference ───────────────────────────────────────────
            String reference = extractProvidusReference(root);
            if (reference == null || reference.isBlank()) {
                // Generate a fallback reference so we don't lose the credit
                reference = "PRV-" + System.currentTimeMillis();
                logger.warn("[PROVIDUS-WEBHOOK] No reference found in payload — using generated fallback {}", reference);
            }

            // ── 4. Build description ───────────────────────────────────────────
            String description = extractProvidusDescription(root, amount);

            logger.info("[PROVIDUS-WEBHOOK] Processing deposit: email={} amount={} ref={}", email, amount, reference);

            // ── 5. Credit wallet (idempotent — skips if reference already processed)
            processSuccessfulFunding(email, amount, amount, BigDecimal.ZERO, reference, description, LocalDateTime.now());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[PROVIDUS-WEBHOOK] Deposit processing crashed", e);
            throw new RuntimeException("Providus deposit webhook processing failed", e);
        }
    }

    /**
     * Processes a transfer-success or transfer-failed webhook event from Providus.
     *
     * <p>Matches the event to an existing {@link Withdrawal} record by provider reference
     * or client reference, then updates the withdrawal status and notifies the user.
     *
     * @param payloadJson the raw (already normalised) webhook JSON
     * @param isSuccess   {@code true} for TRANSFER_SUCCESSFUL, {@code false} for TRANSFER_FAILED
     */
    @Transactional
    public void processProvidusWithdrawalConfirmation(String payloadJson, boolean isSuccess) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            String reference = extractProvidusReference(root);

            if (reference == null || reference.isBlank()) {
                logger.warn("[PROVIDUS-WEBHOOK] Withdrawal confirmation has no reference — cannot match record. Payload logged above.");
                return;
            }

            // Try provider reference first, then our own client reference
            Withdrawal withdrawal = withdrawalRepository.findByProviderReference(reference)
                    .or(() -> withdrawalRepository.findByClientReference(reference))
                    .orElse(null);

            if (withdrawal == null) {
                logger.warn("[PROVIDUS-WEBHOOK] No withdrawal found for reference {} — may have been processed already or reference mismatch", reference);
                return;
            }

            // Guard against double-processing
            if (withdrawal.getStatus() == WithdrawalStatus.COMPLETED
                    || withdrawal.getStatus() == WithdrawalStatus.FAILED
                    || withdrawal.getStatus() == WithdrawalStatus.REVERSED) {
                logger.info("[PROVIDUS-WEBHOOK] Withdrawal {} already in terminal state {} — skipping", reference, withdrawal.getStatus());
                return;
            }

            if (isSuccess) {
                withdrawal.setStatus(WithdrawalStatus.COMPLETED);
                withdrawal.setCompletedAt(LocalDateTime.now());
                withdrawalRepository.save(withdrawal);

                // Mark transaction log as completed
                transactionLogRepository.findByReference(withdrawal.getClientReference()).ifPresent(log -> {
                    log.setStatus(TransactionStatus.COMPLETED);
                    log.setDescription(log.getDescription() + " | Confirmed by Providus");
                    transactionLogRepository.save(log);
                });

                transactionLogRepository.findByReference(buildWithdrawalFeeReference(withdrawal)).ifPresent(log -> {
                    log.setStatus(TransactionStatus.COMPLETED);
                    log.setDescription(log.getDescription() + " | Confirmed by Providus");
                    transactionLogRepository.save(log);
                });

                // Push notification to user
                String msg = String.format("Your withdrawal of ₦%.2f to %s (%s) was successful.",
                        withdrawal.getAmount(), withdrawal.getAccountNumber(), withdrawal.getBankName());
                CompletableFuture.runAsync(() -> {
                    try {
                        notificationService.sendNotification(
                                withdrawal.getUserId().toString(),
                                msg,
                                NotificationType.WITHDRAWAL,
                                null, null, "VIEW_WALLET", "/wallet"
                        );
                    } catch (Exception e) {
                        logger.error("[PROVIDUS-WEBHOOK] Failed to send withdrawal success notification", e);
                    }
                });

                logger.info("[PROVIDUS-WEBHOOK] Withdrawal {} marked COMPLETED", reference);

            } else {
                // Failure — mark failed and reverse the balance
                String failureReason = extractProvidusFailureReason(root);
                markWithdrawalFailed(withdrawal.getId(), "Providus: " + (failureReason != null ? failureReason : "Transfer failed"));
                logger.info("[PROVIDUS-WEBHOOK] Withdrawal {} marked FAILED — reason: {}", reference, failureReason);
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[PROVIDUS-WEBHOOK] Withdrawal confirmation processing crashed", e);
            throw new RuntimeException("Providus withdrawal webhook processing failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Providus payload extraction helpers
    // (multi-pattern because Providus hasn't confirmed their exact field names)
    // ─────────────────────────────────────────────────────────────────────────────

    private String extractProvidusEmail(JsonNode root) {
        // Root-level
        String val = root.path("email").asText(null);
        if (isPresent(val)) return val;
        // customer.email
        val = root.path("customer").path("email").asText(null);
        if (isPresent(val)) return val;
        // data.email / data.customer.email
        JsonNode data = root.path("data");
        if (!data.isMissingNode()) {
            val = data.path("email").asText(null);
            if (isPresent(val)) return val;
            val = data.path("customer").path("email").asText(null);
            if (isPresent(val)) return val;
        }
        // eventData.customer.email (SecureWave-style)
        val = root.path("eventData").path("customer").path("email").asText(null);
        if (isPresent(val)) return val;
        // wallet.email
        val = root.path("wallet").path("email").asText(null);
        if (isPresent(val)) return val;
        return null;
    }

    private String extractProvidusAccountNumber(JsonNode root) {
        for (String field : new String[]{"accountNumber", "destinationAccountNumber",
                "walletAccountNumber", "creditAccountNumber", "account_number"}) {
            String val = root.path(field).asText(null);
            if (isPresent(val)) return val;
        }
        JsonNode data = root.path("data");
        if (!data.isMissingNode()) {
            for (String field : new String[]{"accountNumber", "destinationAccountNumber", "account_number"}) {
                String val = data.path(field).asText(null);
                if (isPresent(val)) return val;
            }
        }
        // wallet.accountNumber
        String val = root.path("wallet").path("accountNumber").asText(null);
        if (isPresent(val)) return val;
        return null;
    }

    private BigDecimal extractProvidusAmount(JsonNode root) {
        for (String field : new String[]{"amount", "amountPaid", "value", "credit_amount", "settlementAmount"}) {
            JsonNode node = root.path(field);
            if (!node.isMissingNode() && !node.isNull()) return decimalFromNode(node);
        }
        JsonNode data = root.path("data");
        if (!data.isMissingNode()) {
            for (String field : new String[]{"amount", "amountPaid", "value"}) {
                JsonNode node = data.path(field);
                if (!node.isMissingNode() && !node.isNull()) return decimalFromNode(node);
            }
        }
        JsonNode eventData = root.path("eventData");
        if (!eventData.isMissingNode()) {
            JsonNode node = eventData.path("amountPaid");
            if (!node.isMissingNode() && !node.isNull()) return decimalFromNode(node);
        }
        return null;
    }

    private String extractProvidusReference(JsonNode root) {
        for (String field : new String[]{"reference", "transactionReference", "transaction_reference",
                "ref", "transactionId", "transaction_id", "txRef", "tx_ref"}) {
            String val = root.path(field).asText(null);
            if (isPresent(val)) return val;
        }
        JsonNode data = root.path("data");
        if (!data.isMissingNode()) {
            for (String field : new String[]{"reference", "transactionReference", "transaction_reference", "ref", "tx_ref"}) {
                String val = data.path(field).asText(null);
                if (isPresent(val)) return val;
            }
        }
        String val = root.path("eventData").path("transactionReference").asText(null);
        if (isPresent(val)) return val;
        return null;
    }

    private String extractProvidusDescription(JsonNode root, BigDecimal amount) {
        for (String field : new String[]{"narration", "description", "paymentDescription",
                "remark", "remarks", "memo"}) {
            String val = root.path(field).asText(null);
            if (isPresent(val)) return val;
        }
        JsonNode data = root.path("data");
        if (!data.isMissingNode()) {
            for (String field : new String[]{"narration", "description", "remark"}) {
                String val = data.path(field).asText(null);
                if (isPresent(val)) return val;
            }
        }
        return String.format("Providus Deposit of ₦%.2f", amount);
    }

    private String extractProvidusFailureReason(JsonNode root) {
        for (String field : new String[]{"message", "reason", "failureReason",
                "failure_reason", "responseMessage", "responseDescription"}) {
            String val = root.path(field).asText(null);
            if (isPresent(val)) return val;
        }
        return "Transfer failed";
    }

    private boolean isPresent(String val) {
        return val != null && !val.isBlank() && !"null".equalsIgnoreCase(val);
    }

    private BigDecimal decimalFromNode(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(node.asText());
    }
}

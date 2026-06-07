package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.TransferFeeQuote;
import com.moniewise.moniewise_backend.dto.request.UpdateBankDetailsRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.dto.response.WithdrawalQuoteResponse;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.entity.Withdrawal;
import com.moniewise.moniewise_backend.enums.*;
import com.moniewise.moniewise_backend.exception.InsufficientFundsException;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.ProvidusExpressGateway;
import com.moniewise.moniewise_backend.entity.RevenueLog;
import com.moniewise.moniewise_backend.repository.RevenueLogRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.repository.WithdrawalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    private final MarkupCalculatorService markupCalculatorService;

    private final RevenueLogRepository revenueLogRepository;

    private final SystemConfigService systemConfigService;
    private final RedisTemplate<String, String> redisTemplate;
    private final MonnieCacheInvalidationService monnieCacheInvalidationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String BANK_LIST_CACHE_PREFIX = "bank_list:";
    private static final long   BANK_LIST_TTL_HOURS    = 24;
    private static final String RUBIES_P2P_CREDIT_REF_PREFIX = "P2P-RB-CR-";
    private static final String RUBIES_P2P_SETTLEMENT_MARKER_PREFIX = "P2P-RB-WH-";

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
            TransferFeeService transferFeeService,
            MarkupCalculatorService markupCalculatorService,
            RevenueLogRepository revenueLogRepository,
            SystemConfigService systemConfigService,
            RedisTemplate<String, String> redisTemplate,
            MonnieCacheInvalidationService monnieCacheInvalidationService) {
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
        this.markupCalculatorService = markupCalculatorService;
        this.revenueLogRepository = revenueLogRepository;
        this.systemConfigService = systemConfigService;
        this.redisTemplate = redisTemplate;
        this.monnieCacheInvalidationService = monnieCacheInvalidationService;
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
            notificationService.sendNotification(userId.toString(), message,
                    NotificationType.INSUFFICIENT_BALANCE, null, null, "VIEW_WALLET", "/wallet");
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
                "VIEW_ACTIVITY",
                "/activity"
        );

        logger.info("Deducted ₦{} from wallet for user {}", amount, userId);
        monnieCacheInvalidationService.evictUserAfterCommit(userId);
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

        if (suppressLogAndNotification) {
            Wallet wallet = walletRepository.findByUser(user)
                    .orElseGet(() -> createWalletForUser(user));

            wallet.setBalance(wallet.getBalance().add(amount));
            wallet.setUpdatedAt(LocalDateTime.now());
            wallet.setLastBalanceSyncAt(LocalDateTime.now());
            walletRepository.save(wallet);

            logger.info("Internal wallet balance-only funding applied for user {}", userId);
            monnieCacheInvalidationService.evictUserAfterCommit(userId);
            return;
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
        monnieCacheInvalidationService.evictUserAfterCommit(userId);
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

        // Redis cache key is PSP-specific so a provider switch auto-invalidates.
        String cacheKey = BANK_LIST_CACHE_PREFIX + gateway.getClass().getSimpleName().toLowerCase();

        // Layer 0 (new): Redis — survives restarts, shared across all instances.
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                List<Map<String, Object>> banks = objectMapper.readValue(
                        cached,
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, Map.class));
                if (banks != null && !banks.isEmpty()) {
                    logger.debug("[BankList] Redis cache hit for {}", cacheKey);
                    return banks;
                }
            }
        } catch (Exception e) {
            logger.warn("[BankList] Redis read failed — will fetch live: {}", e.getMessage());
        }

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
            _lastKnownBanks = fresh;   // refresh JVM stale-on-error cache
            // Write to Redis — next request returns in < 5ms
            try {
                redisTemplate.opsForValue().set(
                        cacheKey,
                        objectMapper.writeValueAsString(fresh),
                        BANK_LIST_TTL_HOURS, TimeUnit.HOURS);
                logger.info("[BankList] Cached {} banks in Redis (key={}, ttl={}h)",
                        fresh.size(), cacheKey, BANK_LIST_TTL_HOURS);
            } catch (Exception e) {
                logger.warn("[BankList] Redis write failed — result still returned: {}", e.getMessage());
            }
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

    /**
     * Pre-loads the bank list for the default PSP into Redis at server startup.
     * After this runs, every call to {@link #getSupportedBanks} returns from
     * Redis (< 5ms) — no user ever waits on a live Rubies/upstream call.
     *
     * <p>If the cache is already populated (e.g. a recent restart), the upstream
     * call is skipped entirely to avoid unnecessary latency at boot time.
     */
    public void warmBankListCache() {
        try {
            PaymentGateway gateway = paymentGatewayResolver.resolveDefault();
            String cacheKey = BANK_LIST_CACHE_PREFIX + gateway.getClass().getSimpleName().toLowerCase();

            // Already warm? Nothing to do.
            String existing = redisTemplate.opsForValue().get(cacheKey);
            if (existing != null && !existing.isBlank()) {
                logger.info("[BankList] Cache already warm at startup — skipping upstream call");
                return;
            }

            List<Map<String, Object>> banks = gateway.getSupportedBanks();
            if (banks != null && !banks.isEmpty()) {
                redisTemplate.opsForValue().set(
                        cacheKey,
                        objectMapper.writeValueAsString(banks),
                        BANK_LIST_TTL_HOURS, TimeUnit.HOURS);
                logger.info("[BankList] Warmed {} banks into Redis at startup (key={})", banks.size(), cacheKey);
            } else {
                // Upstream returned nothing — static fallback will serve requests until
                // the next successful upstream call populates the cache.
                logger.warn("[BankList] Startup warm skipped — upstream returned empty list. " +
                        "Static fallback will be used until upstream recovers.");
            }
        } catch (Exception e) {
            logger.warn("[BankList] Startup warm failed — requests will lazy-load: {}", e.getMessage());
        }
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
    /** Convenience overload — notification always sent. */
    public void processSuccessfulFunding(
            String email, BigDecimal netAmount, BigDecimal grossAmount,
            BigDecimal fee, String ref, String desc, LocalDateTime time) {
        processSuccessfulFunding(email, netAmount, grossAmount, fee, ref, desc, time, false);
    }

    /**
     * Credits the wallet and (optionally) fires a push notification.
     *
     * @param suppressNotification pass {@code true} for internal P2P credits where
     *                             EnvelopeService already sent the recipient a notification,
     *                             preventing the duplicate "Wallet funded" alert.
     */
    public void processSuccessfulFunding(
            String email,
            BigDecimal netAmount,
            BigDecimal grossAmount,
            BigDecimal fee,
            String ref,
            String desc,
            LocalDateTime time,
            boolean suppressNotification
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
        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());

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

        if (!suppressNotification) {
            final BigDecimal finalFee = fee;
            CompletableFuture.runAsync(() -> {
                try {
                    String alertMessage = String.format(
                            "Wallet funded with ₦%.2f. (₦%.2f deposit fee applied)",
                            netAmount,
                            finalFee
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

        PaymentGateway gateway = paymentGatewayResolver.resolveForWallet(wallet);

        // ── Resolve destination bank details ──────────────────────────────────────
        // Rubies: user enters destination per-transfer (like OPay).
        //         Frontend must call POST /wallets/resolve-account first to verify the name.
        // Legacy: pre-linked settlement account stored on the wallet.
        final String destBankCode;
        final String destBankName;
        final String destAccountNumber;
        final String destAccountName;

        if (RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(wallet.getProviderName())) {
            if (request.getBankCode() == null || request.getBankCode().isBlank()) {
                throw new IllegalArgumentException("Destination bank code is required.");
            }
            if (request.getAccountNumber() == null || request.getAccountNumber().isBlank()) {
                throw new IllegalArgumentException("Destination account number is required.");
            }
            if (request.getAccountName() == null || request.getAccountName().isBlank()) {
                throw new IllegalArgumentException(
                        "Please verify the destination account name before proceeding. " +
                        "Use POST /wallets/resolve-account to look it up.");
            }
            destBankCode      = request.getBankCode().trim();
            destBankName      = request.getBankName() != null ? request.getBankName().trim() : destBankCode;
            destAccountNumber = request.getAccountNumber().trim();
            destAccountName   = request.getAccountName().trim();
        } else {
            // Legacy Providus / SecureWave — must have a pre-linked settlement account
            if (wallet.getSettlementAccountNumber() == null || wallet.getSettlementBankCode() == null) {
                throw new IllegalStateException("Please link a withdrawal bank account before withdrawing funds.");
            }
            destBankCode      = wallet.getSettlementBankCode();
            destBankName      = wallet.getSettlementBankName();
            destAccountNumber = wallet.getSettlementAccountNumber();
            destAccountName   = wallet.getSettlementAccountName();
        }

        // ── Fee calculation: Rubies uses markup tiers, legacy providers use WithdrawalFeeService ──
        // transferFee  = Moniewise markup only (this is what enters the revenue wallet)
        // nipFee       = NIBSS NIP bank charge (auto-deducted by Rubies; does NOT go to revenue)
        // totalDebit   = amount + nipFee + transferFee  (what actually leaves the user's Rubies wallet)
        BigDecimal transferFee;
        BigDecimal nipFee;
        if (RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(wallet.getProviderName())) {
            transferFee = markupCalculatorService.calculateMarkup(request.getAmount(), userId);
            nipFee      = markupCalculatorService.calculateNipFee(request.getAmount());
            logger.info("[Transfer] Rubies fees for user={} amount={}: markup=₦{} NIP=₦{}",
                    userId, request.getAmount(), transferFee, nipFee);
        } else {
            transferFee = withdrawalFeeService.calculateWithdrawalFee(request.getAmount());
            nipFee      = BigDecimal.ZERO;
        }

        BigDecimal totalDebit = request.getAmount().add(nipFee).add(transferFee);

        if (wallet.getBalance().compareTo(totalDebit) < 0) {
            if (nipFee.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal maxSendable = wallet.getBalance().subtract(nipFee).subtract(transferFee)
                        .max(BigDecimal.ZERO);
                BigDecimal totalFee = nipFee.add(transferFee);
                throw new IllegalArgumentException(String.format(
                        "Insufficient balance. You have ₦%,.2f available. " +
                        "Bank charge ₦%,.2f + Moniewise fee ₦%,.2f = ₦%,.2f total charges. " +
                        "The most you can send is ₦%,.2f. Please enter a lower amount.",
                        wallet.getBalance(), nipFee, transferFee, totalFee, maxSendable));
            }
            throw new IllegalArgumentException(insufficientWithdrawalBalanceMessage(totalDebit, transferFee));
        }

        String narration = buildWithdrawalNarration(destBankName, request);
        Withdrawal withdrawal = createWithdrawalRecord(
                user, wallet, request, narration, transferFee, totalDebit,
                destBankCode, destBankName, destAccountNumber, destAccountName);
        self.reserveWithdrawalForProvider(withdrawal.getId());

        String providerReference;
        try {
            if (RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(wallet.getProviderName())) {
                // Rubies: pass explicit debit + credit account details
                providerReference = gateway.initiateTransferWithContext(
                        wallet.getProviderWalletRef(),
                        resolveDisplayName(user),
                        destBankCode,
                        destBankName,
                        destAccountNumber,
                        destAccountName,
                        request.getAmount(),
                        withdrawal.getClientReference(),
                        narration
                );
            } else {
                // Providus / SecureWave: legacy path
                providerReference = gateway.initiateWithdrawal(
                        user.getEmail(),
                        request.getAmount(),
                        narration
                );
            }
        } catch (RuntimeException e) {
            self.markWithdrawalFailed(withdrawal.getId(), e.getMessage());
            // Sanitise provider-level errors before they bubble to the user.
            // "Insufficient float" means OUR merchant float is low — not the user's fault.
            // "Insufficient balance" (without our balance-check prefix) is a Rubies internal
            // error that also maps to a float issue on our side.
            String rawMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (rawMsg.contains("insufficient float")
                    || rawMsg.contains("insufficient balance")
                    || rawMsg.contains("not enough float")) {
                throw new RuntimeException(
                        "Transfer temporarily unavailable. Please try again in a few minutes or contact support.");
            }
            throw e;
        }

        // NOTE: the markup fee is NOT transferred to the revenue wallet here.
        // It moves only after the SUCCESS webhook confirms the transfer landed —
        // see processRubiesWithdrawalConfirmation(). Doing it here would mean the
        // revenue wallet gets the fee even when Rubies later rejects the transfer.

        return self.finalizeAcceptedWithdrawal(withdrawal.getId(), providerReference);
    }

    /**
     * Returns the fee breakdown for a transfer, respecting the user's wallet provider
     * and premium status.
     *
     * <p>Rubies wallets use the markup-fee tiers. Legacy wallets use the flat withdrawal fee.
     * Frontend should call this before showing the confirmation screen.
     */
    public WithdrawalQuoteResponse quoteWithdrawal(BigDecimal amount, Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId).orElse(null);

        if (wallet != null && RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(wallet.getProviderName())) {
            MarkupCalculatorService.FeeBreakdown breakdown =
                    markupCalculatorService.buildBreakdown(amount, userId);

            // fee        = Moniewise markup only (revenue)
            // bankCharge = NIBSS NIP fee (goes to Rubies/banking system — NOT revenue)
            // totalDebit = amount + bankCharge + fee
            return new WithdrawalQuoteResponse(
                    breakdown.transferAmount(),
                    breakdown.markupFee(),
                    breakdown.bankCharge(),
                    breakdown.totalFromEnvelope(),
                    amount,
                    breakdown.markupFee().compareTo(BigDecimal.ZERO) == 0 ? "WAIVED" : "MARKUP_TIER",
                    "USER_BALANCE",
                    breakdown.displayText()
            );
        }

        // Legacy: flat fee
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

    /**
     * Backward-compatible overload — called by existing code that doesn't pass userId.
     * Falls back to the flat withdrawal fee (no markup/premium logic).
     */
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

    /** Resolves the user's display name from BVN profile fields, falling back to email prefix. */
    public String resolveDisplayName(User user) {
        Map<String, Object> profile = user.getProfileData() != null ? user.getProfileData() : Map.of();
        String first  = strFromProfile(profile, "bvnFirstName",  "bvnFirst",  "firstName");
        String last   = strFromProfile(profile, "bvnLastName",   "bvnLast",   "lastName");
        if (first != null && last != null) return (first + " " + last).toUpperCase();
        if (last  != null) return last.toUpperCase();
        // Last resort: use the part of the email before @
        String email = user.getEmail();
        return email != null ? email.split("@")[0].toUpperCase() : "ACCOUNT HOLDER";
    }

    private String strFromProfile(Map<String, Object> profile, String... keys) {
        for (String key : keys) {
            Object v = profile.get(key);
            if (v != null && !v.toString().isBlank()) return v.toString().trim();
        }
        return null;
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
        monnieCacheInvalidationService.evictUserAfterCommit(userId);
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
            monnieCacheInvalidationService.evictUserAfterCommit(withdrawal.getUserId());

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

            // Notify user — fire-and-forget so a notification failure never rolls back the reversal
            String failMsg = String.format(
                    "Your transfer of ₦%.2f to %s (%s) could not be completed. Your balance has been reversed.",
                    withdrawal.getAmount(),
                    withdrawal.getAccountName() != null ? withdrawal.getAccountName() : withdrawal.getAccountNumber(),
                    withdrawal.getBankName() != null ? withdrawal.getBankName() : "Unknown Bank"
            );
            final Long userId = withdrawal.getUserId();
            CompletableFuture.runAsync(() -> {
                try {
                    notificationService.sendNotification(
                            userId.toString(),
                            failMsg,
                            NotificationType.WITHDRAWAL,
                            null, null, "VIEW_WALLET", "/wallet"
                    );
                } catch (Exception e) {
                    logger.error("[Wallet] Failed to send withdrawal failure notification for userId={}", userId, e);
                }
            });
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
        monnieCacheInvalidationService.evictUserAfterCommit(withdrawal.getUserId());

        if (transactionLogRepository.findByReference(withdrawal.getClientReference()).isEmpty()) {
            TransactionLog logEntry = TransactionLog.builder()
                    .userId(withdrawal.getUserId())
                    .externalAccountId(null)
                    .externalBankName(withdrawal.getBankName())
                    .externalAccountNumber(withdrawal.getAccountNumber())
                    .externalAccountName(withdrawal.getAccountName())
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
                    .externalAccountId(null)
                    .externalBankName(withdrawal.getBankName())
                    .externalAccountNumber(withdrawal.getAccountNumber())
                    .externalAccountName(withdrawal.getAccountName())
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
                                              BigDecimal totalDebit,
                                              String destBankCode,
                                              String destBankName,
                                              String destAccountNumber,
                                              String destAccountName) {
        Withdrawal withdrawal = new Withdrawal();
        withdrawal.setUserId(user.getId());
        withdrawal.setWalletId(wallet.getId());
        withdrawal.setAmount(request.getAmount());
        withdrawal.setFeeAmount(fee);
        withdrawal.setTotalDebit(totalDebit);
        withdrawal.setRecipientReceives(request.getAmount());
        withdrawal.setCurrency(wallet.getCurrency());
        withdrawal.setNarration(narration);
        withdrawal.setBankName(destBankName);
        withdrawal.setBankCode(destBankCode);
        withdrawal.setAccountNumber(destAccountNumber);
        withdrawal.setAccountName(destAccountName);
        withdrawal.setClientReference(buildClientReference(user.getId()));
        withdrawal.setStatus(WithdrawalStatus.INITIATED);
        withdrawal.setCreatedAt(LocalDateTime.now());
        return withdrawalRepository.save(withdrawal);
    }

    private String buildWithdrawalNarration(String destBankName, WithdrawalRequest request) {
        if (request.getNarration() != null && !request.getNarration().isBlank()) {
            return request.getNarration().trim();
        }
        return "Wisemonie Withdrawal to " + (destBankName != null ? destBankName : "Bank");
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

    /**
     * Fires a best-effort Rubies transfer of the markup fee from the user's Rubies
     * wallet into Moniewise's own Rubies revenue wallet.
     *
     * <p>This call is asynchronous and non-blocking — if it fails, the internal
     * revenue wallet DB record (written by the webhook handler) remains the source
     * of truth for accounting. The Rubies-wallet balance reconciliation can catch any
     * missed fee transfers during periodic audits.
     *
     * <p>Set {@code rubies.revenue.account.number} in system_config via:
     * {@code PUT /admin/config/rubies.revenue.account.number} with
     * {@code {"value":"7012345678","description":"Moniewise Rubies revenue wallet"}}.
     *
     * @param feeAmount      the markup fee to transfer (must be > 0)
     * @param fromWalletRef  the user's Rubies wallet account number (debit side)
     * @param fromWalletName the user's display name (debit narration)
     * @param originalRef    the original WD- or EXT- reference (used to build REV- reference)
     */
    public void collectRubiesMarkupFeeAsync(
            BigDecimal feeAmount,
            String fromWalletRef,
            String fromWalletName,
            String originalRef) {

        if (feeAmount == null || feeAmount.compareTo(BigDecimal.ZERO) <= 0) return;
        if (fromWalletRef == null || fromWalletRef.isBlank()) return;

        String revenueAccountNumber = null;
        String revenueAccountName   = "Moniewise Revenue";
        try {
            revenueAccountNumber = systemConfigService.getString(
                    SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER);
            String configuredName = systemConfigService.getString(
                    SystemConfigService.RUBIES_REVENUE_ACCOUNT_NAME);
            if (configuredName != null && !configuredName.isBlank()) {
                revenueAccountName = configuredName;
            }
        } catch (Exception e) {
            logger.warn("[Rubies-Fee] Could not read revenue account config: {}", e.getMessage());
        }

        if (revenueAccountNumber == null || revenueAccountNumber.isBlank()) {
            logger.warn("[Rubies-Fee] rubies.revenue.account.number not configured — " +
                    "markup fee ₦{} for ref={} tracked internally only. " +
                    "Set via PUT /admin/config/rubies.revenue.account.number",
                    feeAmount, originalRef);
            return;
        }

        final String revRef     = "REV-" + originalRef;
        final String revAccount = revenueAccountNumber;
        final String revName    = revenueAccountName;

        CompletableFuture.runAsync(() -> {
            try {
                PaymentGateway rubies = paymentGatewayResolver
                        .resolveByProviderName(RubiesGateway.PROVIDER_NAME);
                rubies.initiateTransferWithContext(
                        fromWalletRef,
                        fromWalletName,
                        "090175",          // Rubies MFB bank code
                        "Rubies MFB",
                        revAccount,
                        revName,
                        feeAmount,
                        revRef,
                        "Markup fee for " + originalRef
                );
                logger.info("[Rubies-Fee] Markup fee ₦{} transferred to revenue wallet for ref={}",
                        feeAmount, originalRef);
            } catch (Exception e) {
                logger.error("[Rubies-Fee] Failed to transfer markup fee ₦{} to revenue wallet for ref={}: {}",
                        feeAmount, originalRef, e.getMessage());
            }
        });
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

        Wallet savedWallet = walletRepository.save(wallet);
        if (savedWallet.getUser() != null && savedWallet.getUser().getId() != null) {
            monnieCacheInvalidationService.evictUserAfterCommit(savedWallet.getUser().getId());
        }
        return savedWallet;
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
    // Rubies webhook processors
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Handles a Rubies transfer-success or transfer-failed webhook.
     *
     * <p>Looks up the {@link Withdrawal} record by the {@code transactionReference}
     * in the webhook payload (= the {@code clientReference} we sent to Rubies).
     * Updates its status, updates the corresponding transaction logs, and sends a
     * push notification to the user.
     *
     * <p>On failure the user's balance is reversed automatically via
     * {@link #markWithdrawalFailed}.
     *
     * @param payloadJson the raw (already normalised) Rubies webhook JSON
     * @param isSuccess   {@code true} = transfer settled, {@code false} = transfer failed
     */
    @Transactional
    public void processRubiesWithdrawalConfirmation(String payloadJson, boolean isSuccess) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);

            // Rubies webhook is FLAT — paymentReference at root level echoes our transactionReference
            String reference = root.path("paymentReference").asText(null);
            if (reference == null || reference.isBlank()) {
                // secondary fallback for any variant field names
                reference = root.path("transactionReference").asText(null);
            }

            if (reference == null || reference.isBlank()) {
                logger.warn("[RUBIES-WEBHOOK] Withdrawal confirmation has no transactionReference — cannot match record");
                return;
            }

            // Match by our clientReference (which we passed as transactionReference to Rubies)
            // or by the sessionId Rubies returned as providerReference
            final String lookupReference = reference;

            Withdrawal withdrawal = withdrawalRepository.findByClientReference(lookupReference)
                    .or(() -> withdrawalRepository.findByProviderReference(lookupReference))
                    .orElse(null);


            if (withdrawal == null) {
                if (reference.startsWith("P2P-RB-")) {
                    // P2P-RB references are settled synchronously in EnvelopeService — no Withdrawal entity exists.
                    logger.info("[RUBIES-WEBHOOK] P2P reference={} already settled in-app — skipping", reference);
                } else if (reference.startsWith("EXT-")) {
                    // EXT- references belong to envelope external transfers, settled by
                    // ExternalTransferSettlementService.settleExternalTransferIfExists() — not here.
                    logger.info("[RUBIES-WEBHOOK] Envelope external transfer reference={} — settlement handled by ExternalTransferSettlementService", reference);
                } else if (reference.startsWith("REV-")) {
                    // REV- references are the markup fee transfers to Moniewise's own Rubies revenue
                    // wallet. Internal accounting was already done at initiation time — nothing to do here.
                    logger.info("[RUBIES-WEBHOOK] Revenue fee collection reference={} — no further action needed", reference);
                } else {
                    logger.warn("[RUBIES-WEBHOOK] No withdrawal found for reference={} — unexpected", reference);
                }
                return;
            }

            // Guard: don't double-process a terminal state
            if (withdrawal.getStatus() == WithdrawalStatus.COMPLETED
                    || withdrawal.getStatus() == WithdrawalStatus.FAILED
                    || withdrawal.getStatus() == WithdrawalStatus.REVERSED) {
                logger.info("[RUBIES-WEBHOOK] Withdrawal {} already in terminal state {} — skipping",
                        reference, withdrawal.getStatus());
                return;
            }

            if (isSuccess) {
                withdrawal.setStatus(WithdrawalStatus.COMPLETED);
                withdrawal.setCompletedAt(LocalDateTime.now());
                withdrawalRepository.save(withdrawal);

                // Mark transaction logs as completed
                transactionLogRepository.findByReference(withdrawal.getClientReference()).ifPresent(log -> {
                    log.setStatus(TransactionStatus.COMPLETED);
                    log.setDescription(log.getDescription() + " | Confirmed by Rubies");
                    transactionLogRepository.save(log);
                });
                transactionLogRepository.findByReference(buildWithdrawalFeeReference(withdrawal)).ifPresent(log -> {
                    log.setStatus(TransactionStatus.COMPLETED);
                    log.setDescription(log.getDescription() + " | Confirmed by Rubies");
                    transactionLogRepository.save(log);
                });

                // ── Credit markup fee to revenue wallet ───────────────────────
                // The fee was already deducted from the user at reservation time.
                // Now that the transfer is confirmed, move it to the revenue wallet.
                BigDecimal markupFee = withdrawal.getFeeAmount() != null
                        ? withdrawal.getFeeAmount() : BigDecimal.ZERO;

                if (markupFee.compareTo(BigDecimal.ZERO) > 0) {
                    // 1. Credit the internal revenue wallet (accounting ledger).
                    walletRepository.findByRevenueWalletTrue().ifPresent(revenueWallet -> {
                        revenueWallet.setBalance(revenueWallet.getBalance().add(markupFee));
                        revenueWallet.setUpdatedAt(LocalDateTime.now());
                        walletRepository.save(revenueWallet);

                        RevenueLog revenueLog = new RevenueLog();
                        revenueLog.setUserId(withdrawal.getUserId());
                        revenueLog.setType("transfer_markup_fee");
                        revenueLog.setAmount(markupFee);
                        revenueLog.setDescription("Markup fee for transfer " + withdrawal.getClientReference()
                                + " — user " + withdrawal.getUserId());
                        revenueLog.setCreatedAt(LocalDateTime.now());
                        revenueLogRepository.save(revenueLog);

                        logger.info("[RUBIES] Markup fee ₦{} credited to revenue wallet for ref={}",
                                markupFee, withdrawal.getClientReference());
                    });

                    // 2. Fire-and-forget: actually move the markup fee from the user's
                    //    Rubies wallet into Moniewise's own Rubies revenue wallet.
                    //    Done HERE (on confirmed success) — NOT at initiation time —
                    //    so we never collect a fee for a transfer that Rubies rejected.
                    walletRepository.findByUserId(withdrawal.getUserId()).ifPresent(userWallet -> {
                        if (userWallet.getProviderWalletRef() != null) {
                            User transferUser = userRepository.findById(withdrawal.getUserId()).orElse(null);
                            String fromName = transferUser != null
                                    ? resolveDisplayName(transferUser) : "Moniewise User";
                            collectRubiesMarkupFeeAsync(
                                    markupFee,
                                    userWallet.getProviderWalletRef(),
                                    fromName,
                                    withdrawal.getClientReference()
                            );
                        }
                    });
                }

                // Push notification
                final String msg = String.format("Your transfer of ₦%.2f to %s (%s) was successful.",
                        withdrawal.getAmount(), withdrawal.getAccountName(), withdrawal.getBankName());
                CompletableFuture.runAsync(() -> {
                    try {
                        notificationService.sendNotification(
                                withdrawal.getUserId().toString(), msg,
                                NotificationType.WITHDRAWAL, null, null, "VIEW_WALLET", "/wallet"
                        );
                    } catch (Exception e) {
                        logger.error("[RUBIES-WEBHOOK] Failed to send transfer success notification", e);
                    }
                });

                logger.info("[RUBIES-WEBHOOK] Withdrawal {} marked COMPLETED", reference);

            } else {
                // Failure: reverse balance automatically
                // narration is at root level in the flat Rubies webhook payload
                String failureReason = root.path("narration").asText("Transfer failed");
                markWithdrawalFailed(withdrawal.getId(), "Rubies: " + failureReason);
                logger.info("[RUBIES-WEBHOOK] Withdrawal {} marked FAILED — reason: {}", reference, failureReason);
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[RUBIES-WEBHOOK] Withdrawal confirmation processing crashed", e);
            throw new RuntimeException("Rubies withdrawal webhook processing failed", e);
        }
    }

    /**
     * Handles an inbound credit to a Rubies wallet (someone sent money to the user).
     *
     * <p>Rubies sends the credit to the user's account number. We look up the wallet
     * by {@code creditAccountNumber}, credit the internal balance, and notify the user.
     *
     * @param payloadJson the raw Rubies webhook JSON for a credit/deposit event
     */
    @Transactional
    public void processRubiesDepositWebhook(String payloadJson) {
        try {
            // Rubies webhook is FLAT — no nested "data" wrapper.
            // Fields: paymentReference, creditAccount, drCr, amount, narration, responseCode, sessionId
            JsonNode root = objectMapper.readTree(payloadJson);

            // Only process genuine inbound credits
            String drCr         = root.path("drCr").asText(null);
            String responseCode = root.path("responseCode").asText(null);
            if (!"CR".equalsIgnoreCase(drCr) || !"00".equals(responseCode)) {
                logger.warn("[RUBIES-WEBHOOK] Deposit webhook drCr={} responseCode={} — not a successful credit, skipping",
                        drCr, responseCode);
                return;
            }

            String creditAccountNumber = root.path("creditAccount").asText(null);
            String reference           = root.path("paymentReference").asText(null);
            String amountStr           = root.path("amount").asText(null);
            String narration           = root.path("narration").asText("Inbound transfer");

            if (creditAccountNumber == null || creditAccountNumber.isBlank()) {
                logger.error("[RUBIES-WEBHOOK] Deposit webhook missing creditAccount — raw: {}", payloadJson);
                return;
            }
            if (reference == null || reference.isBlank()) {
                reference = "RUB-DEP-" + System.currentTimeMillis();
                logger.warn("[RUBIES-WEBHOOK] Deposit webhook missing transactionReference — using fallback {}", reference);
            }

            BigDecimal amount;
            try {
                amount = new BigDecimal(amountStr != null ? amountStr.trim() : "0");
            } catch (NumberFormatException e) {
                logger.error("[RUBIES-WEBHOOK] Cannot parse deposit amount '{}' for acct={}", amountStr, creditAccountNumber);
                return;
            }

            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("[RUBIES-WEBHOOK] Deposit amount is zero/negative for acct={}", creditAccountNumber);
                return;
            }

            // Skip credits arriving at Moniewise's own Rubies revenue wallet —
            // those are the REV- markup fee transfers we initiated ourselves.
            // Internal accounting was already done inside processRubiesWithdrawalConfirmation/
            // ExternalTransferSettlementService when the DR SUCCESS webhook arrived.
            // No user wallet exists for this account number — silently ignore.
            String revenueAcct = systemConfigService.getString(
                    SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER);
            if (revenueAcct != null && revenueAcct.equalsIgnoreCase(creditAccountNumber)) {
                logger.debug("[RUBIES-WEBHOOK] Credit to Moniewise revenue wallet acct={} ref={} — already accounted, skipping",
                        creditAccountNumber, reference);
                return;
            }

            // Find the wallet by the Rubies account number
            Wallet wallet = walletRepository.findByAccountNumber(creditAccountNumber).orElse(null);
            if (wallet == null) {
                // Try by providerWalletRef as fallback
                wallet = walletRepository.findByProviderWalletRef(creditAccountNumber).orElse(null);
            }
            if (wallet == null || wallet.getUser() == null) {
                logger.error("[RUBIES-WEBHOOK] No wallet found for creditAccountNumber={}", creditAccountNumber);
                return;
            }

            String sessionId = root.path("sessionId").asText(null);
            String originatorName = root.path("originatorName").asText(null);
            Optional<TransactionLog> internalP2pCreditLog =
                    findRubiesP2pCreditLog(wallet.getUser().getId(), reference, sessionId);

            if (isRubiesP2pCredit(reference, internalP2pCreditLog)) {
                logger.info("[RUBIES-WEBHOOK] Processing internal P2P credit: acct={} amount={} ref={} sessionId={}",
                        creditAccountNumber, amount, reference, sessionId);
                settleRubiesP2pCredit(wallet, internalP2pCreditLog, amount, reference, sessionId, originatorName);
                return;
            }

            String description = "Inbound transfer: " + narration;
            logger.info("[RUBIES-WEBHOOK] Processing deposit: acct={} amount={} ref={}", creditAccountNumber, amount, reference);

            // Detect P2P transfers: EnvelopeService saves the credit log as
            // "P2P-RB-CR-{providerReference}" before the webhook arrives.
            // If that log exists it means:
            //   1. EnvelopeService already sent the recipient a WALLET_DEPOSIT notification.
            //   2. The balance was NOT credited by EnvelopeService (Rubies handles it) —
            //      so processSuccessfulFunding MUST still run to update the balance.
            //   3. But the notification must be suppressed to avoid a duplicate.
            boolean isInternalP2p = transactionLogRepository
                    .existsByReference("P2P-RB-CR-" + reference);

            if (isInternalP2p) {
                logger.info("[RUBIES-WEBHOOK] P2P credit ref={} — balance update only, " +
                        "suppressing duplicate notification (EnvelopeService already sent one)", reference);
            }

            // Credit the internal wallet (idempotent — skips if reference already processed)
            processSuccessfulFunding(
                    wallet.getUser().getEmail(),
                    amount,
                    amount,
                    BigDecimal.ZERO,
                    reference,
                    description,
                    LocalDateTime.now(),
                    isInternalP2p   // suppress notification for P2P credits
            );

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[RUBIES-WEBHOOK] Deposit processing crashed", e);
            throw new RuntimeException("Rubies deposit webhook processing failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Providus payload extraction helpers
    // (multi-pattern because Providus hasn't confirmed their exact field names)
    // ─────────────────────────────────────────────────────────────────────────────

    private Optional<TransactionLog> findRubiesP2pCreditLog(Long userId, String paymentReference, String sessionId) {
        Set<String> candidateReferences = new LinkedHashSet<>();
        if (isPresent(paymentReference)) {
            candidateReferences.add(RUBIES_P2P_CREDIT_REF_PREFIX + paymentReference);
        }
        if (isPresent(sessionId)) {
            candidateReferences.add(RUBIES_P2P_CREDIT_REF_PREFIX + sessionId);
        }

        if (!candidateReferences.isEmpty()) {
            Optional<TransactionLog> byReference =
                    transactionLogRepository.findFirstByUserIdAndTransactionTypeAndReferenceInOrderByCreatedAtDesc(
                            userId,
                            TransactionType.USER_TO_ENVELOPE,
                            candidateReferences
                    );
            if (byReference.isPresent()) {
                return byReference;
            }
        }

        if (isPresent(sessionId)) {
            Optional<TransactionLog> bySession =
                    transactionLogRepository.findFirstByUserIdAndTransactionTypeAndProviderReferenceOrderByCreatedAtDesc(
                            userId,
                            TransactionType.USER_TO_ENVELOPE,
                            sessionId
                    );
            if (bySession.isPresent()) {
                return bySession;
            }
        }

        if (isPresent(paymentReference)) {
            return transactionLogRepository.findFirstByUserIdAndTransactionTypeAndProviderReferenceOrderByCreatedAtDesc(
                    userId,
                    TransactionType.USER_TO_ENVELOPE,
                    paymentReference
            );
        }

        return Optional.empty();
    }

    private boolean isRubiesP2pCredit(String paymentReference, Optional<TransactionLog> creditLog) {
        return creditLog.isPresent()
                || (isPresent(paymentReference) && paymentReference.startsWith("P2P-RB-"));
    }

    private void settleRubiesP2pCredit(
            Wallet wallet,
            Optional<TransactionLog> creditLog,
            BigDecimal amount,
            String paymentReference,
            String sessionId,
            String originatorName
    ) {
        String markerReference = rubiesP2pSettlementMarkerReference(paymentReference, sessionId);
        if (!isPresent(markerReference)) {
            logger.error("[RUBIES-WEBHOOK] P2P credit missing stable reference; cannot settle safely");
            return;
        }

        if (transactionLogRepository.existsByReference(markerReference)) {
            logger.info("[RUBIES-WEBHOOK] P2P credit already settled marker={} ref={} sessionId={}",
                    markerReference, paymentReference, sessionId);
            completeRubiesP2pCreditLog(creditLog, sessionId);
            return;
        }

        if (isPresent(paymentReference) && transactionLogRepository.existsByReference(paymentReference)) {
            logger.warn("[RUBIES-WEBHOOK] P2P credit ref={} was already processed as a generic deposit; skipping balance update",
                    paymentReference);
            completeRubiesP2pCreditLog(creditLog, sessionId);
            return;
        }

        TransactionLog marker = new TransactionLog();
        marker.setUserId(wallet.getUser().getId());
        marker.setAmount(amount);
        marker.setFee(BigDecimal.ZERO);
        marker.setTransactionType(TransactionType.P2P_RUBIES_SETTLEMENT);
        marker.setReference(markerReference);
        marker.setProviderName(RubiesGateway.PROVIDER_NAME);
        marker.setProviderReference(isPresent(sessionId) ? sessionId : paymentReference);
        marker.setDescription("Rubies P2P credit webhook processed");
        marker.setStatus(TransactionStatus.COMPLETED);
        marker.setCreatedAt(LocalDateTime.now());
        transactionLogRepository.save(marker);

        wallet.setBalance(wallet.getBalance().add(amount));
        wallet.setUpdatedAt(LocalDateTime.now());
        wallet.setLastBalanceSyncAt(LocalDateTime.now());
        walletRepository.save(wallet);
        monnieCacheInvalidationService.evictUserAfterCommit(wallet.getUser().getId());

        completeRubiesP2pCreditLog(creditLog, sessionId);
        sendRubiesP2pCreditNotification(wallet.getUser().getId(), amount, creditLog, originatorName);
    }

    private String rubiesP2pSettlementMarkerReference(String paymentReference, String sessionId) {
        if (isPresent(paymentReference) && !paymentReference.startsWith("RUB-DEP-")) {
            return RUBIES_P2P_SETTLEMENT_MARKER_PREFIX + paymentReference;
        }
        if (isPresent(sessionId)) {
            return RUBIES_P2P_SETTLEMENT_MARKER_PREFIX + sessionId;
        }
        return null;
    }

    private void completeRubiesP2pCreditLog(Optional<TransactionLog> creditLog, String sessionId) {
        creditLog.ifPresent(log -> {
            boolean changed = false;
            if (log.getStatus() != TransactionStatus.COMPLETED) {
                log.setStatus(TransactionStatus.COMPLETED);
                changed = true;
            }
            if (!isPresent(log.getProviderReference()) && isPresent(sessionId)) {
                log.setProviderReference(sessionId);
                changed = true;
            }
            if (changed) {
                transactionLogRepository.save(log);
            }
        });
    }

    private void sendRubiesP2pCreditNotification(
            Long userId,
            BigDecimal amount,
            Optional<TransactionLog> creditLog,
            String originatorName
    ) {
        try {
            String senderName = resolveRubiesP2pSenderName(creditLog, originatorName);
            String message = String.format("\u20A6%,.2f has been credited to your wallet from %s.",
                    amount, senderName);
            notificationService.sendNotification(
                    userId.toString(),
                    message,
                    NotificationType.WALLET_DEPOSIT,
                    null,
                    null,
                    "VIEW_WALLET",
                    "/dashboard"
            );
        } catch (Exception e) {
            logger.error("[RUBIES-WEBHOOK] Failed to send P2P credit notification for userId={}", userId, e);
        }
    }

    private String resolveRubiesP2pSenderName(Optional<TransactionLog> creditLog, String originatorName) {
        if (creditLog.isPresent() && creditLog.get().getCounterpartyUserId() != null) {
            return userRepository.findById(creditLog.get().getCounterpartyUserId())
                    .map(this::resolveDisplayName)
                    .orElse("a Wisemonie user");
        }
        if (isPresent(originatorName)) {
            return originatorName.trim();
        }
        return "a Wisemonie user";
    }

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

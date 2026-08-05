package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.entity.OutboxEvent;
import com.moniewise.moniewise_backend.entity.ReconciliationItem;
import com.moniewise.moniewise_backend.entity.ReconciliationRun;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.TransactionRequest;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.entity.Withdrawal;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.OutboxEventRepository;
import com.moniewise.moniewise_backend.repository.ReconciliationItemRepository;
import com.moniewise.moniewise_backend.repository.ReconciliationRunRepository;
import com.moniewise.moniewise_backend.repository.SavingsGoalRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.TransactionRequestRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.repository.WithdrawalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class ReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(ReconciliationService.class);

    private static final BigDecimal HOLDINGS_TOLERANCE = new BigDecimal("1.00");
    private static final int MISSED_DEPOSIT_LOOKBACK_DAYS = 7;
    private static final int RUBIES_TRANSACTION_PAGE_SIZE = 100;
    private static final Long SYSTEM_OUTBOX_USER_ID = 0L;

    private final ReconciliationRunRepository reconciliationRunRepository;
    private final ReconciliationItemRepository reconciliationItemRepository;
    private final WalletRepository walletRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final TransactionRequestRepository transactionRequestRepository;
    private final EnvelopeRepository envelopeRepository;
    private final SavingsGoalRepository savingsGoalRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentGatewayResolver paymentGatewayResolver;
    private final WalletService walletService;
    private final MonnieCacheInvalidationService monnieCacheInvalidationService;
    private final ObjectMapper objectMapper;

    @Value("${moniewise.reconciliation.auto-heal-max:50000}")
    private BigDecimal autoHealMax;

    public ReconciliationService(
            ReconciliationRunRepository reconciliationRunRepository,
            ReconciliationItemRepository reconciliationItemRepository,
            WalletRepository walletRepository,
            WithdrawalRepository withdrawalRepository,
            TransactionRequestRepository transactionRequestRepository,
            EnvelopeRepository envelopeRepository,
            SavingsGoalRepository savingsGoalRepository,
            TransactionLogRepository transactionLogRepository,
            OutboxEventRepository outboxEventRepository,
            PaymentGatewayResolver paymentGatewayResolver,
            WalletService walletService,
            MonnieCacheInvalidationService monnieCacheInvalidationService,
            ObjectMapper objectMapper
    ) {
        this.reconciliationRunRepository = reconciliationRunRepository;
        this.reconciliationItemRepository = reconciliationItemRepository;
        this.walletRepository = walletRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.transactionRequestRepository = transactionRequestRepository;
        this.envelopeRepository = envelopeRepository;
        this.savingsGoalRepository = savingsGoalRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.paymentGatewayResolver = paymentGatewayResolver;
        this.walletService = walletService;
        this.monnieCacheInvalidationService = monnieCacheInvalidationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReconciliationRun startRun(String providerName, String runType) {
        ReconciliationRun run = new ReconciliationRun();
        run.setProviderName(providerName);
        run.setRunType(runType);
        run.setStatus(ReconciliationRun.STATUS_STARTED);
        run.setStartedAt(LocalDateTime.now());
        run.setCreatedBy(ReconciliationRun.CREATED_BY_SYSTEM);
        return reconciliationRunRepository.save(run);
    }

    @Transactional
    public Map<String, Object> reconcileWalletBalances(Long runId) {
        ReconciliationRun run = getRunOrThrow(runId);
        int checked = 0;
        int mismatches = 0;

        for (Wallet wallet : walletRepository.findAll()) {
            if (!wallet.isRevenueWallet()) {
                continue;
            }

            checked++;
            PaymentGateway gateway = resolveGatewayForRun(wallet, run);

            String providerReference = firstNonBlank(wallet.getProviderWalletRef(), wallet.getSubWalletRef());
            if (providerReference == null) {
                recordMismatch(
                        run,
                        ReconciliationItem.REFERENCE_TYPE_WALLET,
                        wallet.getId().toString(),
                        null,
                        ReconciliationItem.MISMATCH_MISSING_PROVIDER,
                        stringify(wallet.getBalance()),
                        "NO_PROVIDER_WALLET_REF"
                );
                mismatches++;
                continue;
            }

            Optional<BigDecimal> providerBalance = gateway.fetchWalletBalance(providerReference);
            if (providerBalance.isEmpty()) {
                recordMismatch(
                        run,
                        ReconciliationItem.REFERENCE_TYPE_WALLET,
                        wallet.getId().toString(),
                        providerReference,
                        ReconciliationItem.MISMATCH_MISSING_PROVIDER,
                        stringify(wallet.getBalance()),
                        null
                );
                mismatches++;
                continue;
            }

            if (wallet.getBalance().compareTo(providerBalance.get()) != 0) {
                recordMismatch(
                        run,
                        ReconciliationItem.REFERENCE_TYPE_WALLET,
                        wallet.getId().toString(),
                        providerReference,
                        ReconciliationItem.MISMATCH_BALANCE,
                        stringify(wallet.getBalance()),
                        stringify(providerBalance.get())
                );
                mismatches++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scope", "walletBalances");
        summary.put("checked", checked);
        summary.put("mismatches", mismatches);
        return summary;
    }

    @Transactional
    public Map<String, Object> reconcileWithdrawals(Long runId) {
        ReconciliationRun run = getRunOrThrow(runId);
        int checked = 0;
        int mismatches = 0;

        for (Withdrawal withdrawal : withdrawalRepository.findAll()) {
            checked++;
            Wallet wallet = walletRepository.findById(withdrawal.getWalletId()).orElse(null);
            PaymentGateway gateway = resolveGatewayForRun(wallet, run);

            String internalReference = withdrawal.getClientReference();
            String providerReference = withdrawal.getProviderReference();

            if (providerReference == null || providerReference.isBlank()) {
                if (isExpectedToExistAtProvider(withdrawal)) {
                    recordMismatch(
                            run,
                            ReconciliationItem.REFERENCE_TYPE_WITHDRAWAL,
                            internalReference,
                            null,
                            ReconciliationItem.MISMATCH_MISSING_PROVIDER,
                            withdrawal.getStatus().name(),
                            null
                    );
                    mismatches++;
                }
                continue;
            }

            Optional<String> providerStatus = gateway.fetchTransactionStatus(providerReference);
            if (providerStatus.isEmpty()) {
                recordMismatch(
                        run,
                        ReconciliationItem.REFERENCE_TYPE_WITHDRAWAL,
                        internalReference,
                        providerReference,
                        ReconciliationItem.MISMATCH_MISSING_PROVIDER,
                        withdrawal.getStatus().name(),
                        null
                );
                mismatches++;
                continue;
            }

            if (!withdrawal.getStatus().name().equalsIgnoreCase(providerStatus.get())) {
                recordMismatch(
                        run,
                        ReconciliationItem.REFERENCE_TYPE_WITHDRAWAL,
                        internalReference,
                        providerReference,
                        ReconciliationItem.MISMATCH_STATUS,
                        withdrawal.getStatus().name(),
                        providerStatus.get()
                );
                mismatches++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scope", "withdrawals");
        summary.put("checked", checked);
        summary.put("mismatches", mismatches);
        return summary;
    }

    @Transactional
    public Map<String, Object> reconcileTransactionRequests(Long runId) {
        ReconciliationRun run = getRunOrThrow(runId);
        int checked = 0;
        int mismatches = 0;

        for (TransactionRequest request : transactionRequestRepository.findAll()) {
            checked++;

            if (request.getStatus() == TransactionRequest.Status.PENDING_PROVIDER
                    || request.getStatus() == TransactionRequest.Status.COMPLETED) {
                if (request.getClientReference() == null || request.getClientReference().isBlank()) {
                    recordMismatch(
                            run,
                            ReconciliationItem.REFERENCE_TYPE_TRANSACTION,
                            null,
                            null,
                            ReconciliationItem.MISMATCH_MISSING_INTERNAL,
                            "MISSING_CLIENT_REFERENCE",
                            null
                    );
                    mismatches++;
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scope", "transactionRequests");
        summary.put("checked", checked);
        summary.put("mismatches", mismatches);
        return summary;
    }

    @Transactional
    public Map<String, Object> reconcileTotalHoldings(Long runId) {
        ReconciliationRun run = getRunOrThrow(runId);
        int checked = 0;
        int mismatches = 0;
        int autoResolved = 0;
        int manualReview = 0;

        for (Wallet wallet : walletRepository.findAll()) {
            if (wallet.isRevenueWallet()) {
                continue;
            }

            checked++;
            Long userId = wallet.getUser().getId();
            PaymentGateway gateway = resolveGatewayForRun(wallet, run);

            String providerReference = firstNonBlank(wallet.getProviderWalletRef(), wallet.getSubWalletRef());
            if (providerReference == null) {
                continue;
            }

            Optional<BigDecimal> providerBalance = gateway.fetchWalletBalance(providerReference);
            if (providerBalance.isEmpty()) {
                logger.warn("[Recon] Could not fetch provider balance for wallet {} (user {})", wallet.getId(), userId);
                continue;
            }

            BigDecimal walletBalance = wallet.getBalance() != null ? wallet.getBalance() : BigDecimal.ZERO;
            BigDecimal envelopeTotal = envelopeRepository.sumTotalRemainingByUserIdAndBudgetStatus(userId, BudgetStatus.ACTIVE);
            BigDecimal savingsTotal = savingsGoalRepository.sumBalanceByUserIdAndStatus(userId, SavingsStatus.ACTIVE);
            BigDecimal internalTotal = walletBalance.add(envelopeTotal).add(savingsTotal);

            BigDecimal providerTotal = providerBalance.get();
            BigDecimal difference = providerTotal.subtract(internalTotal);
            BigDecimal gap = difference.abs();

            if (gap.compareTo(HOLDINGS_TOLERANCE) > 0) {
                logger.warn("[Recon] Holdings mismatch for user {}: provider={}, internal={} (wallet={}, envelopes={}, savings={}), gap={}",
                        userId, providerTotal, internalTotal, walletBalance, envelopeTotal, savingsTotal, gap);

                String breakdown = String.format("wallet=%s, envelopes=%s, savings=%s, total=%s",
                        walletBalance, envelopeTotal, savingsTotal, internalTotal);

                ReconciliationItem item = recordMismatch(
                        run,
                        ReconciliationItem.REFERENCE_TYPE_HOLDINGS,
                        wallet.getId().toString(),
                        providerReference,
                        ReconciliationItem.MISMATCH_HOLDINGS,
                        breakdown,
                        stringify(providerTotal)
                );
                mismatches++;

                if (difference.compareTo(BigDecimal.ZERO) > 0) {
                    HealResult result = healMissedDeposits(item, wallet, gap, gateway);
                    if (result.autoResolved()) {
                        autoResolved++;
                    } else {
                        manualReview++;
                    }
                } else {
                    markManualReview(item, "Internal total exceeds BaaS provider balance by "
                            + gap + ". Never auto-debit users; ops review required.");
                    manualReview++;
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scope", "totalHoldings");
        summary.put("checked", checked);
        summary.put("mismatches", mismatches);
        summary.put("autoResolved", autoResolved);
        summary.put("manualReview", manualReview);
        return summary;
    }

    @Transactional
    public ReconciliationItem recordMismatch(
            ReconciliationRun run,
            String referenceType,
            String internalReference,
            String providerReference,
            String mismatchType,
            String internalValue,
            String providerValue
    ) {
        ReconciliationItem item = new ReconciliationItem();
        item.setReconciliationRun(run);
        item.setReferenceType(referenceType);
        item.setInternalReference(internalReference);
        item.setProviderReference(providerReference);
        item.setMismatchType(mismatchType);
        item.setInternalValue(internalValue);
        item.setProviderValue(providerValue);
        item.setStatus(ReconciliationItem.STATUS_OPEN);
        item.setDetectedAt(LocalDateTime.now());
        return reconciliationItemRepository.save(item);
    }

    @Transactional
    private HealResult healMissedDeposits(ReconciliationItem item, Wallet wallet, BigDecimal gap, PaymentGateway gateway) {
        if (gap == null || gap.compareTo(BigDecimal.ZERO) <= 0) {
            return markManualReview(item, "Auto-heal skipped: invalid gap amount " + gap + ".");
        }

        BigDecimal ceiling = autoHealMax != null ? autoHealMax : new BigDecimal("50000");
        if (gap.compareTo(ceiling) > 0) {
            return markManualReview(item, "Auto-heal skipped: gap " + gap
                    + " exceeds configured ceiling " + ceiling + ".");
        }

        String searchItem = firstNonBlank(wallet.getAccountNumber(), wallet.getProviderWalletRef(), wallet.getSubWalletRef());
        if (searchItem == null) {
            return markManualReview(item, "Auto-heal skipped: wallet has no account number/provider reference to search.");
        }

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(MISSED_DEPOSIT_LOOKBACK_DAYS);
        Optional<List<Map<String, Object>>> providerTransactions = gateway.fetchAllWalletTransactions(
                start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                searchItem,
                1,
                RUBIES_TRANSACTION_PAGE_SIZE
        );

        if (providerTransactions.isEmpty()) {
            return markManualReview(item, "Auto-heal skipped: provider transaction lookup returned no usable response.");
        }

        List<MissedCreditCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> transaction : providerTransactions.get()) {
            extractMissedCreditCandidate(wallet, gateway, transaction).ifPresent(candidates::add);
        }

        if (candidates.isEmpty()) {
            return markManualReview(item, "No unprocessed Rubies credit in the last "
                    + MISSED_DEPOSIT_LOOKBACK_DAYS + " days explains this gap.");
        }

        List<MissedCreditCandidate> selected = selectCreditsThatExplainGap(candidates, gap);
        if (selected.isEmpty()) {
            BigDecimal candidateTotal = candidates.stream()
                    .map(MissedCreditCandidate::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return markManualReview(item, "Unprocessed Rubies credits found, but their total "
                    + candidateTotal + " does not explain the gap " + gap + ".");
        }

        BigDecimal healedTotal = BigDecimal.ZERO;
        List<String> healedReferences = new ArrayList<>();
        for (MissedCreditCandidate candidate : selected) {
            if (creditMissedDeposit(wallet, candidate, gateway.getProviderName())) {
                healedTotal = healedTotal.add(candidate.amount());
                healedReferences.add(candidate.paymentReference());
            }
        }

        if (!isWithinTolerance(healedTotal, gap)) {
            return markManualReview(item, "Auto-heal incomplete: credited " + healedTotal
                    + " but expected gap " + gap + ".");
        }

        item.setStatus(ReconciliationItem.STATUS_AUTO_RESOLVED);
        item.setResolvedAt(LocalDateTime.now());
        item.setResolutionNote("Auto-healed missed Rubies credit(s): "
                + String.join(", ", healedReferences) + " total=" + healedTotal + ".");
        reconciliationItemRepository.save(item);
        logger.warn("[Recon] Auto-healed missed deposit(s) for user {} wallet {} refs={} total={}",
                wallet.getUser().getId(), wallet.getId(), healedReferences, healedTotal);
        return new HealResult(true, healedTotal, item.getResolutionNote());
    }

    private Optional<MissedCreditCandidate> extractMissedCreditCandidate(
            Wallet wallet,
            PaymentGateway gateway,
            Map<String, Object> transaction) {
        if (transaction == null || !isCreditTransaction(transaction)) {
            return Optional.empty();
        }

        String paymentReference = firstString(transaction,
                "paymentReference", "transactionReference", "reference",
                "sessionId", "transactionId", "id");
        if (paymentReference == null) {
            return Optional.empty();
        }

        if (transactionLogRepository.existsByReference(paymentReference)
                || transactionLogRepository.existsByProviderNameAndProviderReference(
                        gateway.getProviderName(), paymentReference)) {
            return Optional.empty();
        }

        BigDecimal amount = firstAmount(transaction,
                "amount", "transactionAmount", "creditAmount", "paymentAmount");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        if (!matchesWalletAccount(wallet, transaction)) {
            return Optional.empty();
        }

        LocalDateTime occurredAt = parseProviderDate(firstString(transaction,
                "transactionDate", "transactionDateTime", "createdAt",
                "created_at", "date", "tranDate")).orElse(LocalDateTime.now());
        String narration = firstString(transaction, "narration", "remark", "description", "paymentDescription");

        return Optional.of(new MissedCreditCandidate(paymentReference, amount.abs(), occurredAt, narration));
    }

    private boolean creditMissedDeposit(Wallet wallet, MissedCreditCandidate candidate, String providerName) {
        String reference = candidate.paymentReference();
        if (transactionLogRepository.existsByReference(reference)
                || transactionLogRepository.existsByProviderNameAndProviderReference(providerName, reference)) {
            return false;
        }

        Wallet lockedWallet = walletRepository.findByUserIdForUpdate(wallet.getUser().getId())
                .orElseThrow(() -> new IllegalStateException("Wallet not found for user " + wallet.getUser().getId()));

        if (transactionLogRepository.existsByReference(reference)
                || transactionLogRepository.existsByProviderNameAndProviderReference(providerName, reference)) {
            return false;
        }

        BigDecimal currentBalance = lockedWallet.getBalance() != null ? lockedWallet.getBalance() : BigDecimal.ZERO;
        lockedWallet.setBalance(currentBalance.add(candidate.amount()));
        lockedWallet.setUpdatedAt(LocalDateTime.now());
        lockedWallet.setLastBalanceSyncAt(LocalDateTime.now());
        walletRepository.save(lockedWallet);

        TransactionLog log = TransactionLog.builder()
                .userId(lockedWallet.getUser().getId())
                .amount(candidate.amount())
                .fee(BigDecimal.ZERO)
                .reference(reference)
                .providerName(providerName)
                .providerReference(reference)
                .status(TransactionStatus.COMPLETED)
                .transactionType(TransactionType.RECONCILIATION_CREDIT)
                .description("Auto-recovered missed Rubies credit"
                        + (candidate.narration() != null ? ": " + candidate.narration() : ""))
                .createdAt(candidate.occurredAt())
                .build();
        transactionLogRepository.save(log);

        monnieCacheInvalidationService.evictUserAfterCommit(lockedWallet.getUser().getId());
        walletService.evictWalletCache(lockedWallet.getUser().getId());
        return true;
    }

    private List<MissedCreditCandidate> selectCreditsThatExplainGap(
            List<MissedCreditCandidate> candidates,
            BigDecimal gap) {
        List<MissedCreditCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(MissedCreditCandidate::amount).reversed());

        List<MissedCreditCandidate> selected = new ArrayList<>();
        if (findMatchingCredits(sorted, 0, BigDecimal.ZERO, gap, new ArrayList<>(), selected)) {
            return selected;
        }
        return List.of();
    }

    private boolean findMatchingCredits(
            List<MissedCreditCandidate> candidates,
            int index,
            BigDecimal current,
            BigDecimal target,
            List<MissedCreditCandidate> working,
            List<MissedCreditCandidate> selected) {
        if (isWithinTolerance(current, target)) {
            selected.addAll(working);
            return true;
        }
        if (index >= candidates.size() || current.compareTo(target.add(HOLDINGS_TOLERANCE)) > 0) {
            return false;
        }

        for (int i = index; i < candidates.size(); i++) {
            MissedCreditCandidate candidate = candidates.get(i);
            BigDecimal next = current.add(candidate.amount());
            if (next.compareTo(target.add(HOLDINGS_TOLERANCE)) > 0) {
                continue;
            }
            working.add(candidate);
            if (findMatchingCredits(candidates, i + 1, next, target, working, selected)) {
                return true;
            }
            working.remove(working.size() - 1);
        }
        return false;
    }

    private HealResult markManualReview(ReconciliationItem item, String note) {
        item.setStatus(ReconciliationItem.STATUS_MANUAL_REVIEW);
        item.setResolvedAt(null);
        item.setResolutionNote(note);
        reconciliationItemRepository.save(item);
        logger.warn("[Recon] Manual review required for reconciliation item {}: {}", item.getId(), note);
        return new HealResult(false, BigDecimal.ZERO, note);
    }

    @Transactional
    public void completeRun(Long runId, String summaryJson) {
        ReconciliationRun run = getRunOrThrow(runId);
        run.setStatus(ReconciliationRun.STATUS_COMPLETED);
        run.setCompletedAt(LocalDateTime.now());
        run.setSummaryJson(summaryJson);
        reconciliationRunRepository.save(run);
    }

    @Transactional
    public void failRun(Long runId, String errorMessage) {
        ReconciliationRun run = getRunOrThrow(runId);
        run.setStatus(ReconciliationRun.STATUS_FAILED);
        run.setCompletedAt(LocalDateTime.now());
        run.setSummaryJson(toSummaryJson(Map.of(
                "status", ReconciliationRun.STATUS_FAILED,
                "errorMessage", errorMessage
        )));
        reconciliationRunRepository.save(run);
    }

    @Transactional
    public void runDailyReconciliation(String providerName) {
        ReconciliationRun run = startRun(providerName, ReconciliationRun.RUN_TYPE_INCREMENTAL);

        try {
            Map<String, Object> walletSummary = reconcileWalletBalances(run.getId());
            Map<String, Object> holdingsSummary = reconcileTotalHoldings(run.getId());
            Map<String, Object> withdrawalSummary = reconcileWithdrawals(run.getId());
            Map<String, Object> transactionSummary = reconcileTransactionRequests(run.getId());

            Map<String, Object> finalSummary = new LinkedHashMap<>();
            finalSummary.put("providerName", providerName);
            finalSummary.put("runId", run.getId());
            finalSummary.put("wallets", walletSummary);
            finalSummary.put("holdings", holdingsSummary);
            finalSummary.put("withdrawals", withdrawalSummary);
            finalSummary.put("transactions", transactionSummary);
            List<ReconciliationItem> reviewItems = reconciliationItemRepository.findByReconciliationRunIdAndStatusIn(
                    run.getId(),
                    List.of(ReconciliationItem.STATUS_OPEN, ReconciliationItem.STATUS_MANUAL_REVIEW)
            );
            List<ReconciliationItem> autoResolvedItems = reconciliationItemRepository.findByReconciliationRunIdAndStatusIn(
                    run.getId(),
                    List.of(ReconciliationItem.STATUS_AUTO_RESOLVED)
            );
            finalSummary.put("openItems", reviewItems.stream()
                    .filter(item -> ReconciliationItem.STATUS_OPEN.equals(item.getStatus()))
                    .count());
            finalSummary.put("manualReviewItems", reviewItems.stream()
                    .filter(item -> ReconciliationItem.STATUS_MANUAL_REVIEW.equals(item.getStatus()))
                    .count());
            finalSummary.put("autoResolvedItems", autoResolvedItems.size());

            String summaryJson = toSummaryJson(finalSummary);
            completeRun(run.getId(), summaryJson);
            enqueueAdminAlertIfNeeded(run, reviewItems, finalSummary, summaryJson);
        } catch (Exception ex) {
            logger.error("Reconciliation run {} failed", run.getId(), ex);
            failRun(run.getId(), ex.getMessage());
        }
    }

    private boolean isCreditTransaction(Map<String, Object> transaction) {
        String drCr = firstString(transaction, "drCr", "dr_cr", "debitCredit",
                "creditDebitIndicator", "transactionType", "type");
        if (drCr == null) {
            return false;
        }
        String normalized = drCr.trim().toUpperCase();
        return "CR".equals(normalized)
                || "CREDIT".equals(normalized)
                || normalized.contains("CREDIT");
    }

    private boolean matchesWalletAccount(Wallet wallet, Map<String, Object> transaction) {
        Set<String> walletReferences = new LinkedHashSet<>();
        addNormalizedAccount(walletReferences, wallet.getAccountNumber());
        addNormalizedAccount(walletReferences, wallet.getProviderWalletRef());
        addNormalizedAccount(walletReferences, wallet.getSubWalletRef());

        String account = firstString(transaction,
                "creditAccount", "creditAccountNumber", "accountNumber",
                "walletAccountNumber", "beneficiaryAccountNumber");
        if (account == null) {
            return true;
        }
        return walletReferences.contains(normalizeAccount(account));
    }

    private void addNormalizedAccount(Set<String> references, String value) {
        String normalized = normalizeAccount(value);
        if (normalized != null) {
            references.add(normalized);
        }
    }

    private String normalizeAccount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isBlank() ? value.trim() : digits;
    }

    private String firstString(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private BigDecimal firstAmount(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            BigDecimal amount = parseAmount(map.get(key));
            if (amount != null) {
                return amount.abs();
            }
        }
        return null;
    }

    private BigDecimal parseAmount(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String cleaned = value.toString()
                    .replace(",", "")
                    .replace("NGN", "")
                    .replace("\u20A6", "")
                    .trim();
            if (cleaned.isBlank()) {
                return null;
            }
            return new BigDecimal(cleaned);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Optional<LocalDateTime> parseProviderDate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String text = value.trim();
        try {
            return Optional.of(OffsetDateTime.parse(text).toLocalDateTime());
        } catch (Exception ignored) {
            // Try the next common shape.
        }
        try {
            return Optional.of(LocalDateTime.parse(text));
        } catch (Exception ignored) {
            // Try date-only values.
        }
        try {
            return Optional.of(LocalDate.parse(text).atStartOfDay());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private boolean isWithinTolerance(BigDecimal first, BigDecimal second) {
        if (first == null || second == null) {
            return false;
        }
        return first.subtract(second).abs().compareTo(HOLDINGS_TOLERANCE) <= 0;
    }

    private void enqueueAdminAlertIfNeeded(
            ReconciliationRun run,
            List<ReconciliationItem> reviewItems,
            Map<String, Object> finalSummary,
            String summaryJson) {
        if (reviewItems == null || reviewItems.isEmpty()) {
            return;
        }

        long openCount = reviewItems.stream()
                .filter(item -> ReconciliationItem.STATUS_OPEN.equals(item.getStatus()))
                .count();
        long manualReviewCount = reviewItems.stream()
                .filter(item -> ReconciliationItem.STATUS_MANUAL_REVIEW.equals(item.getStatus()))
                .count();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("__message", "Reconciliation run " + run.getId() + " needs ops review.");
        payload.put("runId", run.getId());
        payload.put("providerName", run.getProviderName());
        payload.put("openCount", openCount);
        payload.put("manualReviewCount", manualReviewCount);
        payload.put("affectedUsers", affectedUsersFor(reviewItems));
        payload.put("totalGapAmount", estimateTotalGapAmount(reviewItems).toPlainString());
        payload.put("summary", finalSummary);
        payload.put("summaryJson", summaryJson);

        OutboxEvent event = new OutboxEvent();
        event.setEventType(NotificationType.ADMIN_RECONCILIATION_ALERT.name());
        event.setUserId(SYSTEM_OUTBOX_USER_ID);
        event.setPayload(payload);
        event.setStatus("PENDING");
        event.setCreatedAt(LocalDateTime.now());
        event.setTtlSeconds(604_800L);
        outboxEventRepository.save(event);

        logger.warn("[Recon] Queued admin reconciliation alert for run {}: open={}, manualReview={}",
                run.getId(), openCount, manualReviewCount);
    }

    private List<String> affectedUsersFor(List<ReconciliationItem> items) {
        Set<String> affected = new LinkedHashSet<>();
        for (ReconciliationItem item : items) {
            String internalReference = item.getInternalReference();
            if ((ReconciliationItem.REFERENCE_TYPE_WALLET.equals(item.getReferenceType())
                    || ReconciliationItem.REFERENCE_TYPE_HOLDINGS.equals(item.getReferenceType()))
                    && isLong(internalReference)) {
                walletRepository.findById(Long.valueOf(internalReference))
                        .ifPresent(wallet -> affected.add("userId=" + wallet.getUser().getId()
                                + ", walletId=" + wallet.getId()));
            } else {
                affected.add(item.getReferenceType() + "=" + stringify(internalReference));
            }
        }
        return new ArrayList<>(affected);
    }

    private BigDecimal estimateTotalGapAmount(List<ReconciliationItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (ReconciliationItem item : items) {
            BigDecimal provider = parseAmount(item.getProviderValue());
            BigDecimal internal = parseInternalAmount(item.getInternalValue());
            if (provider != null && internal != null) {
                total = total.add(provider.subtract(internal).abs());
            }
        }
        return total;
    }

    private BigDecimal parseInternalAmount(String value) {
        if (value == null) {
            return null;
        }
        int totalIndex = value.indexOf("total=");
        if (totalIndex >= 0) {
            return parseAmount(value.substring(totalIndex + "total=".length()));
        }
        return parseAmount(value);
    }

    private boolean isLong(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private ReconciliationRun getRunOrThrow(Long runId) {
        return reconciliationRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Reconciliation run not found: " + runId));
    }

    private PaymentGateway resolveGatewayForRun(Wallet wallet, ReconciliationRun run) {
        if (wallet != null && wallet.getProviderName() != null && !wallet.getProviderName().isBlank()) {
            return paymentGatewayResolver.resolveForWallet(wallet);
        }
        return paymentGatewayResolver.resolveByProviderName(run.getProviderName());
    }

    private boolean isExpectedToExistAtProvider(Withdrawal withdrawal) {
        return withdrawal.getStatus() != null
                && withdrawal.getStatus().name() != null
                && !"INITIATED".equalsIgnoreCase(withdrawal.getStatus().name());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String toSummaryJson(Map<String, Object> summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException ex) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("serializationError", ex.getMessage());
            fallback.put("rawSummary", summary.toString());
            try {
                return objectMapper.writeValueAsString(fallback);
            } catch (JsonProcessingException nested) {
                return "{\"serializationError\":\"" + nested.getMessage() + "\"}";
            }
        }
    }

    private record MissedCreditCandidate(
            String paymentReference,
            BigDecimal amount,
            LocalDateTime occurredAt,
            String narration
    ) {}

    private record HealResult(
            boolean autoResolved,
            BigDecimal amount,
            String note
    ) {}
}

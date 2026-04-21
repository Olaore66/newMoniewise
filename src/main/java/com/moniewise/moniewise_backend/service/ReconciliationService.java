package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.entity.ReconciliationItem;
import com.moniewise.moniewise_backend.entity.ReconciliationRun;
import com.moniewise.moniewise_backend.entity.TransactionRequest;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.entity.Withdrawal;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.repository.ReconciliationItemRepository;
import com.moniewise.moniewise_backend.repository.ReconciliationRunRepository;
import com.moniewise.moniewise_backend.repository.TransactionRequestRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.repository.WithdrawalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class ReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRunRepository reconciliationRunRepository;
    private final ReconciliationItemRepository reconciliationItemRepository;
    private final WalletRepository walletRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final TransactionRequestRepository transactionRequestRepository;
    private final PaymentGatewayResolver paymentGatewayResolver;
    private final ObjectMapper objectMapper;

    public ReconciliationService(
            ReconciliationRunRepository reconciliationRunRepository,
            ReconciliationItemRepository reconciliationItemRepository,
            WalletRepository walletRepository,
            WithdrawalRepository withdrawalRepository,
            TransactionRequestRepository transactionRequestRepository,
            PaymentGatewayResolver paymentGatewayResolver,
            ObjectMapper objectMapper
    ) {
        this.reconciliationRunRepository = reconciliationRunRepository;
        this.reconciliationItemRepository = reconciliationItemRepository;
        this.walletRepository = walletRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.transactionRequestRepository = transactionRequestRepository;
        this.paymentGatewayResolver = paymentGatewayResolver;
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
            checked++;
            PaymentGateway gateway = paymentGatewayResolver.resolveForWallet(wallet);

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
                // Placeholder until a provider-side balance lookup endpoint is available.
                providerBalance = Optional.of(wallet.getBalance());
            }
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
            PaymentGateway gateway = wallet != null
                    ? paymentGatewayResolver.resolveForWallet(wallet)
                    : paymentGatewayResolver.resolveDefault();

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
                // Placeholder until a provider-side status lookup endpoint is available.
                providerStatus = Optional.of(withdrawal.getStatus().name());
            }
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
            Map<String, Object> withdrawalSummary = reconcileWithdrawals(run.getId());
            Map<String, Object> transactionSummary = reconcileTransactionRequests(run.getId());

            Map<String, Object> finalSummary = new LinkedHashMap<>();
            finalSummary.put("providerName", providerName);
            finalSummary.put("runId", run.getId());
            finalSummary.put("wallets", walletSummary);
            finalSummary.put("withdrawals", withdrawalSummary);
            finalSummary.put("transactions", transactionSummary);
            finalSummary.put("openItems", reconciliationItemRepository.findByStatus(ReconciliationItem.STATUS_OPEN).size());

            completeRun(run.getId(), toSummaryJson(finalSummary));
        } catch (Exception ex) {
            logger.error("Reconciliation run {} failed", run.getId(), ex);
            failRun(run.getId(), ex.getMessage());
        }
    }

    private ReconciliationRun getRunOrThrow(Long runId) {
        return reconciliationRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Reconciliation run not found: " + runId));
    }

    private boolean isExpectedToExistAtProvider(Withdrawal withdrawal) {
        return withdrawal.getStatus() != null
                && withdrawal.getStatus().name() != null
                && !"INITIATED".equalsIgnoreCase(withdrawal.getStatus().name());
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
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
}

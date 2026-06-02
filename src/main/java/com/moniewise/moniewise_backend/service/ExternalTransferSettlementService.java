package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.RevenueLog;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.exception.EntityNotFoundException;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.RevenueLogRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.springframework.context.annotation.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class ExternalTransferSettlementService {

    private static final Logger logger = LoggerFactory.getLogger(ExternalTransferSettlementService.class);

    private final TransactionLogRepository transactionLogRepository;
    private final EnvelopeRepository envelopeRepository;
    private final WalletRepository walletRepository;
    private final RevenueLogRepository revenueLogRepository;
    private final WalletService walletService;
    private final MarkupCalculatorService markupCalculatorService;

    public ExternalTransferSettlementService(
            TransactionLogRepository transactionLogRepository,
            EnvelopeRepository envelopeRepository,
            WalletRepository walletRepository,
            RevenueLogRepository revenueLogRepository,
            @Lazy WalletService walletService,
            MarkupCalculatorService markupCalculatorService
    ) {
        this.transactionLogRepository = transactionLogRepository;
        this.envelopeRepository = envelopeRepository;
        this.walletRepository = walletRepository;
        this.revenueLogRepository = revenueLogRepository;
        this.walletService = walletService;
        this.markupCalculatorService = markupCalculatorService;
    }

    @Transactional
    public void settleExternalTransfer(String reference, String status) {

        TransactionLog txn = transactionLogRepository.findByReference(reference)
                .or(() -> transactionLogRepository.findByProviderReference(reference))
                .orElseThrow(() -> new EntityNotFoundException("External transfer transaction not found"));

        if (txn.getStatus() == TransactionStatus.COMPLETED ||
                txn.getStatus() == TransactionStatus.FAILED ||
                txn.getStatus() == TransactionStatus.REVERSED) {
            return;
        }

        Envelope source = envelopeRepository.findById(txn.getSourceEnvelopeId())
                .orElseThrow(() -> new EntityNotFoundException("Source envelope not found"));

        BigDecimal transferAmount = txn.getAmount().abs();
        // markupFee = Moniewise revenue fee (stored on txn.fee at initiation time)
        BigDecimal markupFee = txn.getFee() != null ? txn.getFee() : BigDecimal.ZERO;
        // nipFee = NIBSS NIP bank charge. Not stored on TransactionLog (it is deducted
        // automatically by Rubies at the BaaS level). Recalculate from the transfer amount
        // so the held envelope balance is fully released — amount + nipFee + markup were
        // all held at initiation; all three must be released here.
        BigDecimal nipFee = markupCalculatorService.calculateNipFee(transferAmount);
        BigDecimal fee    = markupFee;   // alias used in revenue-credit block below
        BigDecimal totalDebit = transferAmount.add(nipFee).add(markupFee);

        if (isSuccessful(status)) {
            source.setHeldAmount(source.getHeldAmount().subtract(totalDebit).max(BigDecimal.ZERO));
            source.setTotalRemainingAmount(source.getTotalRemainingAmount().subtract(totalDebit).max(BigDecimal.ZERO));
            txn.setStatus(TransactionStatus.COMPLETED);

            // Bug-fix: credit the markup fee to the platform revenue wallet (was never done
            // for envelope external transfers, only for wallet withdrawals).
            if (fee.compareTo(BigDecimal.ZERO) > 0) {
                walletRepository.findByRevenueWalletTrue().ifPresent(revenueWallet -> {
                    revenueWallet.setBalance(revenueWallet.getBalance().add(fee));
                    revenueWallet.setUpdatedAt(LocalDateTime.now());
                    walletRepository.save(revenueWallet);

                    RevenueLog revenueLog = new RevenueLog();
                    revenueLog.setUserId(txn.getUserId());
                    revenueLog.setType("envelope_external_transfer_fee");
                    revenueLog.setAmount(fee);
                    revenueLog.setDescription("Transfer fee for envelope external transfer "
                            + txn.getReference() + " — user " + txn.getUserId());
                    revenueLog.setCreatedAt(LocalDateTime.now());
                    revenueLogRepository.save(revenueLog);

                    logger.info("[ExternalTransfer] Fee ₦{} credited to revenue wallet for ref={}",
                            fee, txn.getReference());
                });

                // Fire-and-forget: physically move the markup fee from the user's Rubies wallet
                // to Moniewise's Rubies revenue wallet.
                // This is done HERE (on confirmed webhook success) — NOT at initiation time.
                // If the transfer had failed, the fee amount would still be in the user's wallet
                // and we must not collect it.
                walletRepository.findByUserId(txn.getUserId()).ifPresent(userWallet -> {
                    if (userWallet.getProviderWalletRef() != null
                            && RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(userWallet.getProviderName())) {
                        walletService.collectRubiesMarkupFeeAsync(
                                fee,
                                userWallet.getProviderWalletRef(),
                                "Moniewise User",
                                txn.getReference()
                        );
                    }
                });
            }

        } else if (isFailed(status)) {
            // On failure the full debit (amount + fee) is returned to the envelope.
            source.setHeldAmount(source.getHeldAmount().subtract(totalDebit).max(BigDecimal.ZERO));
            source.setRemainingAmount(source.getRemainingAmount().add(totalDebit));
            txn.setStatus(TransactionStatus.FAILED);

        } else {
            return;
        }

        envelopeRepository.save(source);
        transactionLogRepository.save(txn);

        // Keep the companion FEE log in sync with the main transaction status.
        transactionLogRepository.findByReference(txn.getReference() + "-FEE").ifPresent(feeTxn -> {
            feeTxn.setStatus(txn.getStatus());
            transactionLogRepository.save(feeTxn);
        });
    }
    private boolean isSuccessful(String status) {
        if (status == null) return false;
        String s = status.toUpperCase();
        return s.contains("SUCCESS") || s.contains("COMPLETED");
    }

    private boolean isFailed(String status) {
        if (status == null) return false;
        String s = status.toUpperCase();
        return s.contains("FAILED")
                || s.contains("FAIL")
                || s.contains("REVERSED")
                || s.contains("REVERSAL");
    }

    /**
     * Graceful variant used by Rubies DR webhooks alongside
     * {@link WalletWebhookService#processRubiesWithdrawalWebhook}.
     *
     * <p>Unlike {@link #settleExternalTransfer(String, String)}, this method:
     * <ul>
     *   <li>Returns {@code false} (rather than throwing) when the reference is not found.
     *       This is normal — the reference may belong to a wallet withdrawal (WD-) or
     *       a P2P (P2P-RB-) rather than an envelope external transfer (EXT-).</li>
     *   <li>Returns {@code false} when the transaction does not belong to an envelope
     *       external transfer (i.e. {@code sourceEnvelopeId} is {@code null}).</li>
     * </ul>
     *
     * <p>Both this method and {@code processRubiesWithdrawalWebhook} should be called for
     * every DR event — each silently ignores references it does not own.
     *
     * @param reference the payment reference echoed back by Rubies (our {@code EXT-…} reference)
     * @param status    {@code "SUCCESS"} or {@code "FAILED"}
     * @return {@code true} if an envelope external transfer was found and settled
     */
    @Transactional
    public boolean settleExternalTransferIfExists(String reference, String status) {
        if (reference == null || reference.isBlank()) return false;

        Optional<TransactionLog> txnOpt =
                transactionLogRepository.findByReference(reference)
                        .or(() -> transactionLogRepository.findByProviderReference(reference));

        if (txnOpt.isEmpty()) {
            // Reference not found — belongs to a wallet withdrawal or P2P, not an envelope
            // external transfer. Normal; let the other handlers own it.
            return false;
        }

        if (txnOpt.get().getSourceEnvelopeId() == null) {
            // Transaction found but not associated with an envelope. Skip.
            return false;
        }

        // Delegate to the standard settlement path which handles idempotency and
        // envelope balance adjustments.
        settleExternalTransfer(reference, status);
        return true;
    }
}
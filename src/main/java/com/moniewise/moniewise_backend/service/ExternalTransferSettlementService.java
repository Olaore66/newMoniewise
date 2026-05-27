package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.exception.EntityNotFoundException;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class ExternalTransferSettlementService {

    private final TransactionLogRepository transactionLogRepository;
    private final EnvelopeRepository envelopeRepository;

    public ExternalTransferSettlementService(
            TransactionLogRepository transactionLogRepository,
            EnvelopeRepository envelopeRepository
    ) {
        this.transactionLogRepository = transactionLogRepository;
        this.envelopeRepository = envelopeRepository;
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

        BigDecimal amount = txn.getAmount().abs();

        if (isSuccessful(status)) {
            source.setHeldAmount(source.getHeldAmount().subtract(amount));
            source.setTotalRemainingAmount(source.getTotalRemainingAmount().subtract(amount));
            txn.setStatus(TransactionStatus.COMPLETED);

        } else if (isFailed(status)) {
            source.setHeldAmount(source.getHeldAmount().subtract(amount));
            source.setRemainingAmount(source.getRemainingAmount().add(amount));
            txn.setStatus(TransactionStatus.FAILED);

        } else {
            return;
        }

        envelopeRepository.save(source);
        transactionLogRepository.save(txn);
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
}
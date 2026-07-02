package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.PayeelordVasTransaction;
import com.moniewise.moniewise_backend.enums.VasTransactionStatus;
import com.moniewise.moniewise_backend.repository.PayeelordVasTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled safety net that recovers airtime/data purchases stuck in {@code PENDING}.
 *
 * <h3>Why this is needed</h3>
 * <p>Since {@link PayeelordVasService#purchaseAirtime}/{@code purchaseData} return
 * {@code PENDING} immediately and complete the (slow) Payeelord delivery on a background
 * thread, a purchase can be orphaned if that thread never finishes — e.g. the server is
 * redeployed/restarted mid-delivery. The envelope hold was placed but is never settled or
 * released, leaving the user's money stuck and the row {@code PENDING} forever.
 *
 * <h3>What it does</h3>
 * <p>Every {@value #POLL_INTERVAL_MS} ms it finds purchases still {@code PENDING} more than
 * {@value #STALE_AFTER_MINUTES} minutes after creation (far past the ~45s normal path) and
 * hands each to {@link PayeelordVasService#recoverStalePurchase}, which resolves it
 * conservatively: auto-refund only ones Payeelord clearly never registered, escalate the
 * rest for manual reconciliation (Payeelord has no status re-query endpoint, so a delivered
 * purchase can't be auto-confirmed).
 */
@Component
public class VasPurchaseRecoveryScheduler {

    private static final Logger logger =
            LoggerFactory.getLogger(VasPurchaseRecoveryScheduler.class);

    /** Normal completion is ~45s; give a wide margin before treating a PENDING as stuck. */
    static final int STALE_AFTER_MINUTES = 30;

    /** How often the job runs, in milliseconds. */
    static final long POLL_INTERVAL_MS = 5L * 60 * 1000; // 5 minutes

    private final PayeelordVasTransactionRepository transactionRepository;
    private final PayeelordVasService vasService;

    public VasPurchaseRecoveryScheduler(PayeelordVasTransactionRepository transactionRepository,
                                        PayeelordVasService vasService) {
        this.transactionRepository = transactionRepository;
        this.vasService = vasService;
    }

    /**
     * {@code fixedDelay} (not {@code fixedRate}) so a slow batch never overlaps itself.
     */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void recoverStalePurchases() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STALE_AFTER_MINUTES);

        List<PayeelordVasTransaction> stale =
                transactionRepository.findByStatusAndCreatedAtBefore(VasTransactionStatus.PENDING, cutoff);

        if (stale.isEmpty()) {
            return; // nothing to do — keep quiet servers quiet
        }

        logger.info("[VasRecovery] {} PENDING VAS purchase(s) older than {} min — attempting recovery",
                stale.size(), STALE_AFTER_MINUTES);

        for (PayeelordVasTransaction txn : stale) {
            try {
                vasService.recoverStalePurchase(txn.getId());
            } catch (Exception e) {
                // One bad record must not abort the whole batch.
                logger.warn("[VasRecovery] Error recovering ref={}: {}",
                        txn.getReference(), e.getMessage(), e);
            }
        }
    }
}

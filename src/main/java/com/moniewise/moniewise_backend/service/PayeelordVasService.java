package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.request.AirtimePurchaseRequest;
import com.moniewise.moniewise_backend.dto.request.DataPurchaseRequest;
import com.moniewise.moniewise_backend.entity.PayeelordDataPlan;
import com.moniewise.moniewise_backend.entity.PayeelordVasTransaction;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.VasTransactionStatus;
import com.moniewise.moniewise_backend.enums.VasTransactionType;
import com.moniewise.moniewise_backend.psp.payeelord.PayeelordGateway;
import com.moniewise.moniewise_backend.psp.payeelord.dto.PayeelordAirtimePurchaseResponse;
import com.moniewise.moniewise_backend.psp.payeelord.dto.PayeelordDataPurchaseResponse;
import com.moniewise.moniewise_backend.psp.payeelord.dto.PayeelordWebhookPayload;
import com.moniewise.moniewise_backend.repository.PayeelordDataPlanRepository;
import com.moniewise.moniewise_backend.repository.PayeelordVasTransactionRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates PIN-gated Payeelord airtime/data purchases — the VAS analogue of
 * {@code WalletService#processWithdrawal}.
 *
 * <h3>Why this mirrors the withdrawal flow's transaction shape</h3>
 * The orchestrating {@code purchaseAirtime}/{@code purchaseData} methods are
 * deliberately NOT {@code @Transactional}: they make a slow external HTTP call to
 * Payeelord in the middle of the flow. Wrapping the whole thing in one DB
 * transaction would (a) hold a row lock on the wallet for the full HTTP round-trip,
 * and (b) roll back the debit + audit record if Payeelord times out — exactly the
 * "ambiguous outcome, but we silently lost our own record of it" scenario we most
 * need to avoid in a synchronous reseller integration.
 *
 * <p>So, exactly like {@code processWithdrawal} → {@code reserveWithdrawalForProvider}
 * → (gateway call) → {@code finalizeAcceptedWithdrawal}/{@code markWithdrawalFailed},
 * each DB-write step here is its own small {@code @Transactional} method, invoked
 * through the self-proxy ({@link #self}) so Spring's transactional advice actually
 * applies:
 *
 * <pre>
 *   openPurchase(...)        — locks wallet, debits sellingAmount, writes PENDING audit row
 *        ↓
 *   gateway.purchase*(...)   — the slow synchronous HTTP call (NOT inside a transaction)
 *        ↓
 *   finalize*Result(...)     — SUCCESSFUL, or reverse + REVERSED, or leave PENDING (ambiguous)
 * </pre>
 *
 * <h3>The three possible outcomes</h3>
 * <ul>
 *   <li><b>Successful</b> — mark {@code SUCCESSFUL}, keep the debit, notify the user.</li>
 *   <li><b>Definitively failed</b> — reverse the debit, mark {@code REVERSED}, notify the user.</li>
 *   <li><b>Ambiguous</b> (timeout / malformed response — see
 *       {@link PayeelordGateway.PayeelordAmbiguousResponseException}) — leave the
 *       transaction {@code PENDING} and the debit IN PLACE (Payeelord's float may
 *       already have been charged), log at CRITICAL level for manual reconciliation
 *       via {@code GET /data-transactions}, and surface a "we're verifying this"
 *       message to the user rather than a hard failure.</li>
 * </ul>
 */
@Service
public class PayeelordVasService {

    private static final Logger logger = LoggerFactory.getLogger(PayeelordVasService.class);

    @Autowired
    @Lazy
    private PayeelordVasService self;

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final UserService userService;
    private final PayeelordGateway gateway;
    private final PayeelordPricingService pricingService;
    private final PayeelordDataPlanRepository dataPlanRepository;
    private final PayeelordVasTransactionRepository transactionRepository;
    private final NotificationService notificationService;
    private final MonnieCacheInvalidationService monnieCacheInvalidationService;
    private final ObjectMapper objectMapper;

    public PayeelordVasService(UserRepository userRepository,
                               WalletRepository walletRepository,
                               UserService userService,
                               PayeelordGateway gateway,
                               PayeelordPricingService pricingService,
                               PayeelordDataPlanRepository dataPlanRepository,
                               PayeelordVasTransactionRepository transactionRepository,
                               NotificationService notificationService,
                               MonnieCacheInvalidationService monnieCacheInvalidationService,
                               ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.userService = userService;
        this.gateway = gateway;
        this.pricingService = pricingService;
        this.dataPlanRepository = dataPlanRepository;
        this.transactionRepository = transactionRepository;
        this.notificationService = notificationService;
        this.monnieCacheInvalidationService = monnieCacheInvalidationService;
        this.objectMapper = objectMapper;
    }

    // ── Airtime purchase ──────────────────────────────────────────────────────

    public PayeelordVasTransaction purchaseAirtime(Long userId, AirtimePurchaseRequest request) {
        User user = loadUser(userId);
        verifyPin(user, request.getTransactionPin());

        String network = request.getNetwork().trim().toUpperCase();
        String mobileNumber = request.getMobileNumber().trim();
        BigDecimal amount = request.getAmount();

        PayeelordPricingService.VasPricing pricing = pricingService.priceAirtime(amount);
        String reference = buildReference("VAS-AT", userId);

        logger.info("[PayeelordVAS] Opening airtime purchase: user={} ref={} {}",
                userId, reference, pricing.displayText());

        PayeelordVasTransaction txn = self.openPurchase(
                userId, VasTransactionType.AIRTIME, reference, network, mobileNumber, null, pricing);

        PayeelordAirtimePurchaseResponse response;
        try {
            response = gateway.purchaseAirtime(network, mobileNumber, amount);
        } catch (PayeelordGateway.PayeelordAmbiguousResponseException e) {
            self.markAmbiguous(txn.getId(), e.getMessage());
            throw new RuntimeException(
                    "We couldn't immediately confirm your airtime purchase with the provider — " +
                    "it may still go through. We'll update your transaction history shortly. " +
                    "If it doesn't reflect within a few minutes, contact support with reference " + reference + ".", e);
        }

        return self.finalizeAirtimeResult(txn.getId(), response);
    }

    // ── Data purchase ─────────────────────────────────────────────────────────

    public PayeelordVasTransaction purchaseData(Long userId, DataPurchaseRequest request) {
        User user = loadUser(userId);
        verifyPin(user, request.getTransactionPin());

        String mobileNumber = request.getMobileNumber().trim();

        PayeelordDataPlan plan = dataPlanRepository.findByDataId(request.getDataId().trim())
                .orElseThrow(() -> new IllegalArgumentException("That data plan is no longer available. Please pick another."));

        if (!plan.isActive()) {
            throw new IllegalArgumentException("That data plan is currently unavailable. Please pick another.");
        }

        PayeelordPricingService.VasPricing pricing = pricingService.priceDataPlan(plan);
        String reference = buildReference("VAS-DT", userId);

        logger.info("[PayeelordVAS] Opening data purchase: user={} ref={} plan={} ({}) {}",
                userId, reference, plan.getDataId(), plan.getPlanName(), pricing.displayText());

        PayeelordVasTransaction txn = self.openPurchase(
                userId, VasTransactionType.DATA, reference, plan.getNetworkName(), mobileNumber, plan, pricing);

        PayeelordDataPurchaseResponse response;
        try {
            response = gateway.purchaseData(plan.getNetworkId(), plan.getDataId(), mobileNumber);
        } catch (PayeelordGateway.PayeelordAmbiguousResponseException e) {
            self.markAmbiguous(txn.getId(), e.getMessage());
            throw new RuntimeException(
                    "We couldn't immediately confirm your data purchase with the provider — " +
                    "it may still go through. We'll update your transaction history shortly. " +
                    "If it doesn't reflect within a few minutes, contact support with reference " + reference + ".", e);
        }

        return self.finalizeDataResult(txn.getId(), response);
    }

    // ── Read-only queries ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PayeelordVasTransaction> getRecentTransactions(Long userId) {
        return transactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<PayeelordDataPlan> getActiveDataPlans() {
        return dataPlanRepository.findByActiveTrueOrderByNetworkNameAscCostPriceAsc();
    }

    @Transactional(readOnly = true)
    public List<PayeelordDataPlan> getActiveDataPlansForNetwork(String networkId) {
        return dataPlanRepository.findByNetworkIdAndActiveTrueOrderByCostPriceAsc(networkId);
    }

    // ── Transactional steps (mirrors WalletService.reserveWithdrawalForProvider /
    //    finalizeAcceptedWithdrawal / markWithdrawalFailed — small, focused, called via self-proxy) ──

    /**
     * Locks the wallet, validates the balance, debits {@code sellingAmount}, and
     * writes the {@code PENDING} audit row — all atomically, BEFORE we ever call
     * out to Payeelord. This guarantees we never charge Payeelord's float for a
     * purchase the user couldn't actually afford, and that an audit record exists
     * for every attempt (even ones that blow up mid-flight).
     */
    @Transactional
    public PayeelordVasTransaction openPurchase(Long userId,
                                                 VasTransactionType type,
                                                 String reference,
                                                 String network,
                                                 String mobileNumber,
                                                 PayeelordDataPlan dataPlan,
                                                 PayeelordPricingService.VasPricing pricing) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        BigDecimal balanceBefore = wallet.getBalance();
        BigDecimal sellingAmount = pricing.sellingAmount();

        if (balanceBefore.compareTo(sellingAmount) < 0) {
            throw new IllegalArgumentException(String.format(
                    "Insufficient balance. You have ₦%,.2f available, but this purchase costs ₦%,.2f. " +
                    "Please fund your wallet and try again.",
                    balanceBefore, sellingAmount));
        }

        wallet.setBalance(balanceBefore.subtract(sellingAmount));
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);
        BigDecimal balanceAfter = wallet.getBalance();
        monnieCacheInvalidationService.evictUserAfterCommit(userId);

        PayeelordVasTransaction txn = new PayeelordVasTransaction();
        txn.setUserId(userId);
        txn.setWalletId(wallet.getId());
        txn.setType(type);
        txn.setStatus(VasTransactionStatus.PENDING);
        txn.setReference(reference);
        txn.setNetwork(network);
        txn.setMobileNumber(mobileNumber);
        txn.setDataPlan(dataPlan);
        txn.setFaceAmount(pricing.faceAmount());
        txn.setCostAmount(pricing.costAmount());
        txn.setSellingAmount(sellingAmount);
        txn.setMarginAmount(pricing.marginAmount());
        txn.setBalanceBefore(balanceBefore);
        txn.setBalanceAfter(balanceAfter);
        txn.setCreatedAt(LocalDateTime.now());
        txn.setUpdatedAt(LocalDateTime.now());
        return transactionRepository.save(txn);
    }

    /**
     * Applies Payeelord's synchronous {@code /buy/airtime} response to the
     * transaction: SUCCESSFUL stays debited, FAILED gets reversed, PROCESSING
     * stays PENDING (Payeelord told us explicitly it's still resolving — very
     * different from the "we have no idea" ambiguous case, so we keep the debit
     * and record what we were told).
     */
    @Transactional
    public PayeelordVasTransaction finalizeAirtimeResult(Long txnId, PayeelordAirtimePurchaseResponse response) {
        PayeelordVasTransaction txn = transactionRepository.findById(txnId)
                .orElseThrow(() -> new IllegalArgumentException("VAS transaction not found: " + txnId));

        txn.setPayeelordTransactionId(response.getTransactionId());
        txn.setRawResponse(serialize(response));
        txn.setUpdatedAt(LocalDateTime.now());

        if (response.isSuccessful()) {
            txn.setStatus(VasTransactionStatus.SUCCESSFUL);
            transactionRepository.save(txn);
            logger.info("[PayeelordVAS] Airtime purchase SUCCESSFUL: ref={} providerTxnId={}",
                    txn.getReference(), response.getTransactionId());
            notifyAsync(txn.getUserId(), String.format(
                            "₦%,.2f airtime sent to %s on %s. Reference: %s",
                            txn.getFaceAmount(), txn.getMobileNumber(), txn.getNetwork(), txn.getReference()),
                    NotificationType.AIRTIME_PURCHASE_SUCCESS);
            return txn;
        }

        if (response.isProcessing()) {
            transactionRepository.save(txn); // remains PENDING — Payeelord said so explicitly
            logger.info("[PayeelordVAS] Airtime purchase PROCESSING (left PENDING): ref={} providerTxnId={}",
                    txn.getReference(), response.getTransactionId());
            return txn;
        }

        // Definitive failure — reverse the debit.
        reverseDebit(txn);
        txn.setStatus(VasTransactionStatus.REVERSED);
        txn.setFailureReason(response.getMessage() != null ? response.getMessage() : "Airtime purchase failed.");
        transactionRepository.save(txn);
        logger.warn("[PayeelordVAS] Airtime purchase FAILED → reversed: ref={} reason={}",
                txn.getReference(), txn.getFailureReason());
        notifyAsync(txn.getUserId(), String.format(
                        "Your airtime purchase of ₦%,.2f to %s could not be completed (%s). " +
                        "₦%,.2f has been refunded to your wallet. Reference: %s",
                        txn.getFaceAmount(), txn.getMobileNumber(), txn.getFailureReason(),
                        txn.getSellingAmount(), txn.getReference()),
                NotificationType.AIRTIME_PURCHASE_FAILED);
        return txn;
    }

    /** Same logic as {@link #finalizeAirtimeResult}, mapped onto the data-purchase response shape. */
    @Transactional
    public PayeelordVasTransaction finalizeDataResult(Long txnId, PayeelordDataPurchaseResponse response) {
        PayeelordVasTransaction txn = transactionRepository.findById(txnId)
                .orElseThrow(() -> new IllegalArgumentException("VAS transaction not found: " + txnId));

        txn.setPayeelordTransactionId(response.getTransactionId());
        txn.setRawResponse(serialize(response));
        txn.setUpdatedAt(LocalDateTime.now());

        String planLabel = response.getPlanName() != null
                ? response.getPlanName()
                : (txn.getDataPlan() != null ? txn.getDataPlan().getPlanName() : "data plan");

        if (response.isSuccessful()) {
            txn.setStatus(VasTransactionStatus.SUCCESSFUL);
            transactionRepository.save(txn);
            logger.info("[PayeelordVAS] Data purchase SUCCESSFUL: ref={} providerTxnId={}",
                    txn.getReference(), response.getTransactionId());
            notifyAsync(txn.getUserId(), String.format(
                            "%s sent to %s on %s. Reference: %s",
                            planLabel, txn.getMobileNumber(), txn.getNetwork(), txn.getReference()),
                    NotificationType.DATA_PURCHASE_SUCCESS);
            return txn;
        }

        if (response.isProcessing()) {
            transactionRepository.save(txn);
            logger.info("[PayeelordVAS] Data purchase PROCESSING (left PENDING): ref={} providerTxnId={}",
                    txn.getReference(), response.getTransactionId());
            return txn;
        }

        reverseDebit(txn);
        txn.setStatus(VasTransactionStatus.REVERSED);
        txn.setFailureReason(response.getMessage() != null ? response.getMessage() : "Data purchase failed.");
        transactionRepository.save(txn);
        logger.warn("[PayeelordVAS] Data purchase FAILED → reversed: ref={} reason={}",
                txn.getReference(), txn.getFailureReason());
        notifyAsync(txn.getUserId(), String.format(
                        "Your purchase of %s for %s could not be completed (%s). " +
                        "₦%,.2f has been refunded to your wallet. Reference: %s",
                        planLabel, txn.getMobileNumber(), txn.getFailureReason(),
                        txn.getSellingAmount(), txn.getReference()),
                NotificationType.DATA_PURCHASE_FAILED);
        return txn;
    }

    /**
     * Records an ambiguous outcome WITHOUT reversing the debit — see class Javadoc
     * for why. Leaves the transaction {@code PENDING} with a note for ops to
     * reconcile via {@code GET /data-transactions}, and logs at CRITICAL level
     * (an ambiguous synchronous-purchase outcome should page someone, not hide in
     * the logs).
     */
    @Transactional
    public void markAmbiguous(Long txnId, String note) {
        transactionRepository.findById(txnId).ifPresent(txn -> {
            if (txn.getStatus() != VasTransactionStatus.PENDING) {
                return; // already finalized through some other path — don't clobber it
            }
            txn.setFailureReason("AMBIGUOUS OUTCOME — " + note +
                    " — wallet debit was deliberately NOT reversed (Payeelord's float may have been charged). " +
                    "Requires manual reconciliation via GET /data-transactions before any user-facing resolution.");
            txn.setUpdatedAt(LocalDateTime.now());
            transactionRepository.save(txn);

            logger.error("[PayeelordVAS][CRITICAL][NEEDS-RECONCILIATION] Ambiguous purchase outcome: " +
                            "ref={} userId={} type={} sellingAmount={} — manual reconciliation required NOW.",
                    txn.getReference(), txn.getUserId(), txn.getType(), txn.getSellingAmount());
        });
    }

    /**
     * Records a webhook delivery as a <b>secondary audit confirmation</b> —
     * stamping {@code webhookConfirmedAt} on the matching transaction.
     *
     * <h3>Why this is intentionally lightweight</h3>
     * Unlike Rubies/Providus/SecureWave (genuinely asynchronous PSPs whose webhook
     * IS the source of truth for settlement), Payeelord's purchase endpoints answer
     * synchronously — {@code finalizeAirtimeResult}/{@code finalizeDataResult}
     * already drove the transaction to {@code SUCCESSFUL}/{@code REVERSED}/{@code PENDING}
     * before this webhook could possibly arrive. Payeelord's own docs describe the
     * webhook firing "only after the purchase endpoint returns its JSON response" —
     * i.e. strictly after-the-fact. Standing up the full {@code WebhookEvent}
     * idempotency/state-machine machinery (built for PSPs that need it to drive
     * settlement) would be architectural overkill for what is, here, just a
     * "yes, we also told you about this" ping. Re-deliveries are naturally
     * idempotent: the {@code webhookConfirmedAt == null} guard below means a
     * duplicate delivery is a harmless no-op.
     *
     * <p>Matches by {@code payeelordTransactionId} — the one identifier we can be
     * confident the webhook payload echoes back, since it's what Payeelord itself
     * assigned to the transaction (see {@link PayeelordVasTransaction#getPayeelordTransactionId()}).
     * If no local row matches (e.g. the id format differs from what we stored, or
     * this is some other account's transaction hitting our endpoint), we simply log
     * a warning — there is nothing to reverse or reconcile from a pure audit ping.
     */
    @Transactional
    public void recordWebhookConfirmation(PayeelordWebhookPayload payload) {
        if (payload == null) {
            logger.warn("[PayeelordVAS][WEBHOOK] Received an unparseable payload — nothing to record");
            return;
        }

        String providerTxnId = payload.extractProviderTransactionId();
        if (providerTxnId == null || providerTxnId.isBlank()) {
            logger.warn("[PayeelordVAS][WEBHOOK] Payload carried no recognisable transaction id — " +
                            "event={} id={} — recording as audit-only miss (nothing to confirm against)",
                    payload.getEvent(), payload.getId());
            return;
        }

        transactionRepository.findByPayeelordTransactionId(providerTxnId).ifPresentOrElse(txn -> {
            if (txn.getWebhookConfirmedAt() != null) {
                logger.debug("[PayeelordVAS][WEBHOOK] Duplicate delivery ignored — ref={} providerTxnId={} " +
                        "already confirmed at {}", txn.getReference(), providerTxnId, txn.getWebhookConfirmedAt());
                return;
            }
            txn.setWebhookConfirmedAt(LocalDateTime.now());
            txn.setUpdatedAt(LocalDateTime.now());
            transactionRepository.save(txn);
            logger.info("[PayeelordVAS][WEBHOOK] Confirmed via webhook — ref={} providerTxnId={} " +
                            "event={} providerStatus={} localStatus={}",
                    txn.getReference(), providerTxnId, payload.getEvent(),
                    payload.extractProviderStatus(), txn.getStatus());
        }, () -> logger.warn("[PayeelordVAS][WEBHOOK] No local transaction found for providerTxnId={} " +
                        "(event={}) — could be a casing/format mismatch with what we stored, or a " +
                        "transaction that never reached finalize*Result; nothing to reverse from an audit ping",
                providerTxnId, payload.getEvent()));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Reverses the wallet debit for a failed purchase. Called from within the
     * same {@code @Transactional} finalize step that marks the transaction
     * {@code REVERSED} — both happen atomically, hence the entity's documented
     * {@code PENDING → FAILED → REVERSED} flow collapses to a single observable
     * {@code PENDING → REVERSED} transition (no external observer ever sees a
     * dangling FAILED-but-not-yet-reversed state).
     */
    private void reverseDebit(PayeelordVasTransaction txn) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(txn.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        wallet.setBalance(wallet.getBalance().add(txn.getSellingAmount()));
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);
        monnieCacheInvalidationService.evictUserAfterCommit(txn.getUserId());
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private void verifyPin(User user, String rawPin) {
        if (!userService.verifyTransactionPin(user, rawPin)) {
            throw new IllegalArgumentException("Invalid transaction PIN");
        }
    }

    private String buildReference(String prefix, Long userId) {
        return prefix + "-" + userId + "-" + System.currentTimeMillis();
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            logger.warn("[PayeelordVAS] Could not serialize provider response for audit log: {}", e.getMessage());
            return null;
        }
    }

    /** Fire-and-forget — a notification failure must never roll back a financial transaction. */
    private void notifyAsync(Long userId, String message, NotificationType type) {
        CompletableFuture.runAsync(() -> {
            try {
                notificationService.sendNotification(
                        userId.toString(), message, type, null, null, "VIEW_ACTIVITY", "/activity");
            } catch (Exception e) {
                logger.error("[PayeelordVAS] Failed to send {} notification for userId={}", type, userId, e);
            }
        });
    }
}

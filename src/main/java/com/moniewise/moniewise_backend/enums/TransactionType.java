package com.moniewise.moniewise_backend.enums;

public enum TransactionType {
    BUDGET_COMPLETION_REFUND,
    WALLET_DEPOSIT,
    WALLET_DEDUCTION,
    ENVELOPE_DISBURSEMENT,
    DISBURSEMENT_REFUNDED,
    BUDGET_UNALLOCATED_REFUNDED,
    BUDGET_ALLOCATION,
    BUDGET_EXTENSION,
    STRICT_LOCK_ROLLBACK,
    BUDGET_CREATION_FEE,
    ENVELOPE_TO_ENVELOPE,
    ENVELOPE_TO_EXTERNAL,
    FAILED,
    USER_TO_USER,
    WALLET_TO_BUDGET,
    WALLET_TO_EXTERNAL,
    WALLET_TO_USER,
    WALLET_DEPOSIT_FEE,
    ENVELOPE_TO_USER,
    USER_TO_ENVELOPE,
    ROLLOVER_REFUND,
    WALLET_WITHDRAWAL,
    WALLET_WITHDRAWAL_FEE,
    SAVINGS_DEPOSIT,
    SAVINGS_WITHDRAWAL,
    ENVELOPE_EXTERNAL_TRANSFER_FEE,
    P2P_RUBIES_SETTLEMENT,
    /**
     * Wallet-side debit log for the NIP + service fee charged at envelope-to-bank
     * transfer initiation.  Unlike the envelope-side {@link #ENVELOPE_EXTERNAL_TRANSFER_FEE}
     * (which is an internal accounting entry), this type IS user-visible — it
     * explains the wallet balance drop that accompanies an envelope transfer.
     *
     * <p>Reference pattern: {@code WFT-{envelopeTransferRef}}
     * Status lifecycle: PROCESSING (on initiation) → COMPLETED (on TSQ/webhook success)
     *                                              → FAILED    (on TSQ/webhook failure, fee refunded)
     */
    WALLET_ENVELOPE_TRANSFER_FEE,
    /**
     * Internal Rubies-to-Rubies P2P used to physically move the Moniewise markup fee
     * from the user's Rubies wallet into the Moniewise revenue wallet.
     * These transactions are platform-internal and must NOT appear in user-facing
     * transaction history (do not add to the findUserVisibleTransactions type set).
     * Reference pattern: REV-{originalTransferRef}
     */
    MARKUP_FEE_COLLECTION,

    /**
     * Internal Rubies-to-Rubies P2P used to physically move the full payment for an
     * airtime/data (VAS) purchase from the user's Rubies wallet into the Moniewise
     * revenue/internal wallet. Platform-internal — must NOT appear in user-facing
     * history. Reference pattern: REV-{vasReference}
     */
    VAS_PAYMENT_COLLECTION

    }

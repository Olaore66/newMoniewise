package com.moniewise.moniewise_backend.psp;

import com.moniewise.moniewise_backend.entity.User;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PaymentGateway {

    String getProviderName();

    Map<String, String> createVirtualAccount(User user);

    String resolveAccount(String bankCode, String accountNumber);

    List<Map<String, Object>> getSupportedBanks();

    String initiateTransfer(String bankCode, String accountNumber, String accountName, BigDecimal amount, String uniqueReference, String narration);

    boolean updateWithdrawalBankInfo(String email, String bankName, String accountName, String bankCode, String accountNumber);

    Map<String, Object> getWithdrawalBankInfo(String email);

    String initiateWithdrawal(String email, BigDecimal amount, String narration);

    default String extractWebhookEventType(String rawPayload) {
        return "UNKNOWN";
    }

    default String extractWebhookReference(String rawPayload) {
        return null;
    }

    default boolean validateWebhookSignature(String signature, String rawPayload) {
        return true;
    }

    default Optional<BigDecimal> fetchWalletBalance(String walletReference) {
        return Optional.empty();
    }

    default Optional<String> fetchTransactionStatus(String providerReference) {
        return Optional.empty();
    }

    /**
     * Initiates an outbound NIP transfer with full sender + recipient context.
     *
     * <p>Rubies requires the sender's account number ({@code debitAccountNumber})
     * explicitly, which the original {@link #initiateTransfer} signature omits.
     * Existing gateways (Providus, SecureWave) delegate to the old signature by default.
     *
     * @param debitAccountNumber  sender's account number at the PSP
     * @param debitAccountName    sender's account name
     * @param creditBankCode      recipient's bank NIP code
     * @param creditBankName      recipient's bank name
     * @param creditAccountNumber recipient's account number
     * @param creditAccountName   recipient's account name (from name-enquiry)
     * @param amount              transfer amount in NGN
     * @param reference           unique transaction reference
     * @param narration           transfer narration
     * @return provider transaction reference / session ID
     */
    default String initiateTransferWithContext(
            String debitAccountNumber, String debitAccountName,
            String creditBankCode, String creditBankName,
            String creditAccountNumber, String creditAccountName,
            BigDecimal amount, String reference, String narration) {
        // Default: delegate to old signature (Providus, SecureWave)
        return initiateTransfer(creditBankCode, creditAccountNumber, creditAccountName,
                amount, reference, narration);
    }
}

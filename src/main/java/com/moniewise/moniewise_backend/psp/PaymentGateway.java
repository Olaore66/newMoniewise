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
}

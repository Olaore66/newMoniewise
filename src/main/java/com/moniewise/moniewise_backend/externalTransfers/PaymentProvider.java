package com.moniewise.moniewise_backend.externalTransfers;

import com.moniewise.moniewise_backend.entity.User;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
public interface PaymentProvider {
    // 1. Verify the Name (e.g., "058" + "0123456789" -> "Emeka Johnson")
    String resolveAccount(String bankCode, String accountNumber);

    // 👇 ADD THIS NEW METHOD TO SATISFY THE PaymentGateway INTERFACE 👇
    Map<String, String> createVirtualAccount(User user);

    // 2. Send the Money
    // Returns a "Provider Reference" (Their receipt ID)
    String initiateTransfer(String bankCode, String accountNumber, String accountName, BigDecimal amount, String uniqueReference, String narration);
}
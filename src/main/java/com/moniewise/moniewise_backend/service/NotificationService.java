package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
//@Profile("dev") // Only active during development
public class NotificationService {

    // Stub for sending welcome email
    public String sendWelcomeEmail(String email, String accountNumber, String bankName, BigDecimal balance) {
        String emailContent = String.format(
                "[STUB] Email sent to: %s\n" +
                        "Wallet Details:\n" +
                        "- Account: %s\n" +
                        "- Bank: %s\n" +
                        "- Balance: $%.2f\n" +
                        "Funding Options:\n" +
                        "1. Bank Transfer (Use account above)\n" +
                        "2. Third-Party: https://your-platform.com/fund",
                email, accountNumber, bankName, balance
        );

        return emailContent; // Log to console
    }

    // Stub for SMS
    public String sendWelcomeSms(String phoneNumber, String accountNumber, String bankName, BigDecimal balance) {
        String smsContent = String.format(
                "[STUB] SMS to %s: Wallet created. Acc/%s, Bank/%s. Fund via bank transfer or app.",
                phoneNumber, accountNumber, bankName
        );

       return smsContent; // Log to console
    }

    // Combined notification
    public void sendWelcomeNotification(User user, Wallet wallet) {
        sendWelcomeEmail(user.getEmail(), wallet.getAccountNumber(), wallet.getBankName(), wallet.getBalance());

        if (user.getPhone() != null) {
            sendWelcomeSms(user.getPhone(), wallet.getAccountNumber(), wallet.getBankName(), wallet.getBalance());
        }
    }

    public String sendNotification(String toString, String message) {
        return message;
    }
}
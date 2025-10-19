package com.moniewise.moniewise_backend.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.moniewise.moniewise_backend.entity.Notification;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.NotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;

import javax.annotation.PostConstruct;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class NotificationService {
    private final FirebaseMessaging firebaseMessaging;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;
    private final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    @Value("${spring.profiles.active:stub}")
    private String activeProfile;

    @Value("${twilio.account.sid:YOUR_TWILIO_ACCOUNT_SID}")
    private String twilioAccountSid;

    @Value("${twilio.auth.token:YOUR_TWILIO_AUTH_TOKEN}")
    private String twilioAuthToken;

    @Value("${twilio.phone.number:+15551234567}")
    private String twilioPhoneNumber;

    @Value("${spring.mail.from:moniewise@example.com}")
    private String fromEmail;

    @Autowired
    public NotificationService(
            @Autowired(required = false) FirebaseMessaging firebaseMessaging,
            UserRepository userRepository,
            NotificationRepository notificationRepository,
            @Autowired(required = false) JavaMailSender mailSender
    ) {
        this.firebaseMessaging = firebaseMessaging;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.mailSender = mailSender;
    }

    @PostConstruct
    public void initTwilio() {
        if (twilioAccountSid == null || twilioAuthToken == null ||
                "stub".equals(activeProfile) ||
                twilioAccountSid.equals("YOUR_TWILIO_ACCOUNT_SID") ||
                twilioAuthToken.equals("YOUR_TWILIO_AUTH_TOKEN")) {
            logger.info("[STUB] Twilio not initialized due to placeholder credentials or stub mode");
        } else {
            Twilio.init(twilioAccountSid, twilioAuthToken);
            logger.info("Twilio initialized with account SID: {}", twilioAccountSid);
        }
    }

    public void sendNotification(String userId, String message, NotificationType type, Long budgetId, Long envelopeId, String actionType, String redirectUrl) {
        try {
            Notification notification = new Notification();
            notification.setUserId(Long.valueOf(userId));
            notification.setMessage(message);
            notification.setType(type);
            notification.setCreatedAt(LocalDateTime.now());
            notification.setRead(false);
            notification.setBudgetId(budgetId);
            notification.setEnvelopeId(envelopeId);
            notification.setActionType(actionType);
            notification.setRedirectUrl(redirectUrl);
            notificationRepository.save(notification);
            logger.info("Saved notification for user {}: type={}, message={}", userId, type, message);

            User user = userRepository.findById(Long.valueOf(userId)).orElse(null);
            if (user == null) {
                logger.warn("User {} not found for notification", userId);
                return;
            }
            if (user.getFcmToken() != null && firebaseMessaging != null && !"stub".equals(activeProfile)) {
                Message fcmMessage = Message.builder()
                        .setToken(user.getFcmToken())
                        .setNotification(com.google.firebase.messaging.Notification.builder()
                                .setTitle("Moniewise")
                                .setBody(message)
                                .build())
                        .putData("type", type.toString())
                        .putData("budgetId", budgetId != null ? budgetId.toString() : "")
                        .putData("envelopeId", envelopeId != null ? envelopeId.toString() : "")
                        .putData("actionType", actionType != null ? actionType : "")
                        .putData("redirectUrl", redirectUrl != null ? redirectUrl : "")
                        .build();
                String response = firebaseMessaging.send(fcmMessage);
                logger.info("Sent FCM notification to user {}: {}", userId, response);
            } else {
                logger.info("[STUB] FCM notification for user {}: type={}, message={}", userId, type, message);
            }
        } catch (Exception e) {
            logger.error("Failed to send notification to user {}: {}", userId, e.getMessage());
        }
    }

    public void sendNotification(String userId, String message, NotificationType type) {
        sendNotification(userId, message, type, null, null, null, null);
    }

    public void sendWelcomeEmail(String email, String accountNumber, String bankName, BigDecimal balance) {
        String emailContent = String.format(
                "Welcome to Moniewise!\n" +
                        "Your wallet is ready:\n" +
                        "- Account: %s\n" +
                        "- Bank: %s\n" +
                        "- Balance: ₦%.2f\n" +
                        "Fund your wallet via:\n" +
                        "1. Bank Transfer (Use account above)\n" +
                        "2. Third-Party: https://moniewise.com/fund",
                accountNumber, bankName, balance
        );

        if ("stub".equals(activeProfile) || "dev".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Email to {}:\n{}", email, emailContent);
            return;
        }

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
            helper.setFrom(fromEmail);
            helper.setTo(email);
            helper.setSubject("Welcome to Moniewise!");
            helper.setText(emailContent, true);
            mailSender.send(mimeMessage);
            logger.info("Sent welcome email to {}", email);
        } catch (MessagingException e) {
            logger.error("Failed to send welcome email to {}: {}", email, e.getMessage());
        }
    }

    public void sendWelcomeSms(String phoneNumber, String accountNumber, String bankName, BigDecimal balance) {
        String smsContent = String.format(
                "Moniewise: Wallet created. Acc/%s, Bank/%s. Fund via bank transfer or app.",
                accountNumber, bankName
        );

        if ("stub".equals(activeProfile) || "dev".equals(activeProfile) ||
                twilioAccountSid == null || twilioAuthToken == null ||
                twilioAccountSid.equals("YOUR_TWILIO_ACCOUNT_SID") ||
                twilioAuthToken.equals("YOUR_TWILIO_AUTH_TOKEN")) {
            logger.info("[STUB] SMS to {}: {}", phoneNumber, smsContent);
            return;
        }

        try {
            new MessageCreator(
                    new PhoneNumber(phoneNumber),
                    new PhoneNumber(twilioPhoneNumber),
                    smsContent
            ).create();
            logger.info("Sent welcome SMS to {}", phoneNumber);
        } catch (Exception e) {
            logger.error("Failed to send welcome SMS to {}: {}", phoneNumber, e.getMessage());
        }
    }

    public void sendWelcomeNotification(User user, Wallet wallet) {
        sendWelcomeEmail(user.getEmail(), wallet.getAccountNumber(), wallet.getBankName(), wallet.getBalance());
        if (user.getPhone() != null && !user.getPhone().isEmpty()) {
            sendWelcomeSms(user.getPhone(), wallet.getAccountNumber(), wallet.getBankName(), wallet.getBalance());
        }
        String message = String.format("Welcome to Moniewise! Your wallet (Acc/%s, Bank/%s) is ready.",
                wallet.getAccountNumber(), wallet.getBankName());
        sendNotification(user.getId().toString(), message, NotificationType.WELCOME);
    }
}
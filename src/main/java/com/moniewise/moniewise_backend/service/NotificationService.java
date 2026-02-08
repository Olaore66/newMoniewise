package com.moniewise.moniewise_backend.service;

import com.google.firebase.messaging.*;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.NotificationPriority;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.NotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

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

    private final TemplateEngine templateEngine;
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
            @Autowired(required = false) JavaMailSender mailSender,
            TemplateEngine templateEngine) {
        this.firebaseMessaging = firebaseMessaging;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
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

    // =========================================================================
// UPDATED MASTER METHOD (Passes Type to FCM)
// =========================================================================
    public void sendNotification(
            String userId,
            String message,
            NotificationType type,
            Long budgetId,
            Long envelopeId,
            String actionType,
            String redirectUrl) {

        // 1. FILTER: Check Priority
        NotificationPriority priority = getPriority(type);


        try {
            Long uId = Long.valueOf(userId);

            // 2. SAVE TO DB (Entity Notification)
            com.moniewise.moniewise_backend.entity.Notification notification = new com.moniewise.moniewise_backend.entity.Notification();
            notification.setUserId(uId);
            notification.setMessage(message);
            notification.setType(type);
            notification.setCreatedAt(LocalDateTime.now());
            notification.setRead(false);
            notification.setBudgetId(budgetId);
            notification.setEnvelopeId(envelopeId);
            notification.setActionType(actionType);
            notification.setRedirectUrl(redirectUrl);

            notificationRepository.save(notification);

            // 3. SEND PUSH (Only For High Priority)
            if (priority == NotificationPriority.HIGH) {
                User user = userRepository.findById(uId).orElse(null);

                if (user != null && user.getFcmToken() != null && !user.getFcmToken().isEmpty() && !"stub".equals(activeProfile)) {

                    String dynamicTitle = getNotificationTitle(type);

                    // FIX: Pass 'type' into this method so we can group them
                    sendFCMMessage(user, dynamicTitle, message, actionType, redirectUrl, type);
                }
            }

        } catch (Exception e) {
            logger.error("Notification error for user {}: {}", userId, e.getMessage());
        }

        if (priority == NotificationPriority.LOW) {
            logger.info("Skipping LOW priority notification: {}", type);
            return;
        }

        if (firebaseMessaging == null) {
            System.out.println("⚠️ Skipping notification: Firebase is not initialized.");
            return;
        }

    }

    // Helper to generate dynamic, engaging titles based on the event type
    private String getNotificationTitle(NotificationType type) {
        if (type == null) return "Wisemonie";

        switch (type) {
            // --- 💰 MONEY IN (Credit) ---
            case WALLET_FUNDED:
            case WALLET_DEPOSIT:
            case REFUND_ISSUED:
            case DISBURSEMENT_REFUNDED:
            case BUDGET_UNALLOCATED_REFUNDED:
                return "Credit Alert 🚀";

            // --- 💸 MONEY OUT (Debit) ---
            case WITHDRAWAL:
            case EXTERNAL_TRANSFER:
            case ENVELOPE_TRANSFER: // Sending P2P
            case BUDGET_CREATION_FEE:
                return "Debit Alert 💸";

            // --- 🔓 DISBURSEMENTS (The "Sweet Spot") ---
            case DISBURSEMENT:
            case DISBURSEMENT_SUCCESS:
            case DISBURSEMENT_READY:
                return "Funds Released 🔓";
            case PRE_DISBURSEMENT:
            case DISBURSEMENT_REMINDER:
                return "Disbursement Ready ⏳";
            case EXPIRED_DISBURSEMENT:
            case DISBURSEMENT_FAILED:
                return "Disbursement Expired ❌";

            // --- ⚠️ WARNINGS & LIMITS ---
            case INSUFFICIENT_BALANCE:
                return "Transaction Declined ⛔";
            case LOW_BALANCE_WARNING:
            case ENVELOPE_LOW_BALANCE:
                return "Low Balance Warning 📉";
            case LIMIT_REACHED:
            case BUDGET_LIMIT_WARNING:
                return "Spending Limit Hit ⚠️";
            case EMERGENCY_USED:
                return "Emergency Fund Used 🚨";

            // --- 🎯 BUDGETING & ENVELOPES ---
            case BUDGET_CREATION:
            case BUDGET_CREATION_SUCCESS:
                return "Budget Active 🎯";
            case BUDGET_COMPLETED:
            case GOAL_ACHIEVED:
                return "Goal Smashed! 🏆";
            case ENVELOPE_CREATED:
                return "New Envelope ✉️";
            case ENVELOPE_UPDATED:
            case BUDGET_UPDATED:
            case ENVELOPE_UNLOCKED:
                return "Update Successful ✅";
            case ENVELOPE_LOCKED:
                return "Envelope Locked 🔒";
            case BUDGET_END:
            case BUDGET_EXPIRED:
                return "Budget Ended 🏁";
            case BUDGET_END_SOON:
            case BUDGET_ENDING_SOON:
                return "Budget Ending Soon ⏳";
            case MATURITY_ALERT:
                return "Maturity Alert 📅";
            case WEEKLY_SUMMARY:
                return "Weekly Recap 📊";

            // --- 👋 GENERAL & SYSTEM ---
            case WELCOME:
                return "Welcome to Wisemonie app👋";
            case SYSTEM:
                return "System Update 📢";
            case POSITIVE_NUDGE:
                return "Keep it up! 💪";

            default:
                return "Wisemonie Notification";
        }
    }

    // =========================================================================
    // UPDATED FCM LOGIC (Golden Payload Implementation)
    // =========================================================================
    private void sendFCMMessage(User user, String title, String body, String actionType, String redirectUrl, NotificationType type) {
        try {
            // 1. Determine the Shared Key (For grouping/replacing old alerts)
            String collapseKey = getGroupKey(type);

            // 2. Android Config (The Fix for Pop-ups & Channel Lock)
            AndroidConfig androidConfig = AndroidConfig.builder()
                    .setTtl(86400 * 1000) // 24 hours
                    .setPriority(AndroidConfig.Priority.HIGH) // Critical for background delivery
                    .setNotification(AndroidNotification.builder()
                            .setChannelId("moniewise_alerts_v5") // 👈 CRITICAL: Must match Flutter
                            .setSound("wisemonie")               // 👈 Android Sound (no extension)
                            .setDefaultSound(false)              // Force custom sound
                            .setPriority(AndroidNotification.Priority.MAX) // Heads-up notification
                            .setVisibility(AndroidNotification.Visibility.PUBLIC)
                            .setClickAction("FLUTTER_NOTIFICATION_CLICK")
//                            .setTag(collapseKey)                 // Grouping key
                            .build())
                    .build();

            // 3. iOS Config (The Fix for Sound)
            ApnsConfig apnsConfig = ApnsConfig.builder()
//                    .putHeader("apns-collapse-id", collapseKey)
                    .setAps(Aps.builder()
                            .setSound("wisemonie.wav")           // 👈 iOS Sound (needs extension)
                            .setContentAvailable(true)           // Wakes app for background processing
                            .setThreadId(collapseKey)
                            .build())
                    .build();

            // 4. Shared Notification Payload (Title/Body)
            // This ensures the system tray shows the text immediately
            com.google.firebase.messaging.Notification fcmNotification =
                    com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build();

            // 5. Build Data Map (Navigation Logic)
            Message.Builder messageBuilder = Message.builder()
                    .setToken(user.getFcmToken())
                    .setNotification(fcmNotification) // 👈 Includes "notification" block
                    .setAndroidConfig(androidConfig)  // 👈 Includes Channel ID
                    .setApnsConfig(apnsConfig);       // 👈 Includes iOS Sound

            // Add Data Fields for Flutter
            messageBuilder.putData("click_action", "FLUTTER_NOTIFICATION_CLICK");
            if (actionType != null) messageBuilder.putData("actionType", actionType);
            if (redirectUrl != null) messageBuilder.putData("redirectUrl", redirectUrl);

            // Add Title/Body to Data as well (Backup for foreground handling)
            messageBuilder.putData("title", title);
            messageBuilder.putData("body", body);

            // 6. Send
            firebaseMessaging.send(messageBuilder.build());
            logger.info("Sent FCM to user {}: {}", user.getId(), title);

        } catch (FirebaseMessagingException e) {
            String errorCode = e.getMessagingErrorCode().toString();
            if (errorCode.equals("UNREGISTERED") || errorCode.equals("NOT_FOUND") || errorCode.equals("INVALID_ARGUMENT")) {
                logger.warn("🚨 Token for user {} is dead. Removing it.", user.getId());
                user.setFcmToken(null);
                userRepository.save(user);
            } else {
                logger.error("Failed to send FCM message: {}", e.getMessage());
            }
        }
    }
//    private void sendFCMMessage(User user, String title, String body, String actionType, String redirectUrl, NotificationType type) {
//        try {
//            // 1. Determine the Shared Key
//            String collapseKey = getGroupKey(type);
//
//            // 2. Android Config (Replaces old alerts on Android)
//            AndroidConfig androidConfig = AndroidConfig.builder()
//                    .setTtl(86400 * 1000)
//                    .setPriority(AndroidConfig.Priority.HIGH)
//                    .setNotification(AndroidNotification.builder()
//                            .setClickAction("FLUTTER_NOTIFICATION_CLICK")
//                            .setTag(collapseKey) // Android "Replace" Logic
//                            .build())
//                    .build();
//
//            // 3. iOS Config (The New Part 🍎)
//            ApnsConfig apnsConfig = ApnsConfig.builder()
//                    .putHeader("apns-collapse-id", collapseKey) // <--- iOS "Replace" Logic
//                    .setAps(Aps.builder()
//                            .setSound("default")
//                            .setThreadId(collapseKey) // <--- Also groups them nicely in the UI
//                            .build())
//                    .build();
//
//            // 4. Build Notification Payload
//            com.google.firebase.messaging.Notification fcmNotification =
//                    com.google.firebase.messaging.Notification.builder()
//                            .setTitle(title)
//                            .setBody(body)
//                            .build();
//
//            // 5. Build Final Message
//            Message.Builder messageBuilder = Message.builder()
//                    .setToken(user.getFcmToken())
//                    .setNotification(fcmNotification)
//                    .setAndroidConfig(androidConfig)
//                    .setApnsConfig(apnsConfig) // <--- Attach iOS Config
//                    .putData("click_action", "FLUTTER_NOTIFICATION_CLICK");
//
//            if (actionType != null) messageBuilder.putData("actionType", actionType);
//            if (redirectUrl != null) messageBuilder.putData("redirectUrl", redirectUrl);
//
//            firebaseMessaging.send(messageBuilder.build());
//
//        } catch (FirebaseMessagingException e) {
//            String errorCode = e.getMessagingErrorCode().toString();
//            if (errorCode.equals("UNREGISTERED") || errorCode.equals("NOT_FOUND") || errorCode.equals("INVALID_ARGUMENT")) {
//                logger.warn("🚨 Token for user {} is dead. Removing it.", user.getId());
//                user.setFcmToken(null);
//                userRepository.save(user);
//            } else {
//                logger.error("Failed to send FCM message: {}", e.getMessage());
//            }
//        }
//    }
    // =========================================================================
// NEW HELPER: Define Groups
// =========================================================================
    private String getGroupKey(NotificationType type) {
        if (type == null) return "GENERAL";

        return switch (type) {
            // All these will bundle under "Funds Released"
            case DISBURSEMENT, DISBURSEMENT_SUCCESS, DISBURSEMENT_READY -> "DISBURSEMENTS";

            // All these will bundle under "Money Sent/Received"
            case WALLET_DEPOSIT, WALLET_FUNDED, ENVELOPE_TRANSFER -> "TRANSACTIONS";

            // Warnings bundle together
            case LOW_BALANCE_WARNING, BUDGET_LIMIT_WARNING -> "WARNINGS";

            default -> "GENERAL";
        };
    }
    // =========================================================================
    // 2. THE HELPER METHOD (Short version for simple alerts)
    // =========================================================================
    public void sendNotification(String userId, String message, NotificationType type) {
        // Just call the Master method with nulls for the extras
        sendNotification(userId, message, type, null, null, null, null);
    }

    private NotificationPriority getPriority(NotificationType type) {
        return switch (type) {
            case WALLET_DEPOSIT,
                    WALLET_FUNDED,
                    ENVELOPE_TRANSFER,
                    EXTERNAL_TRANSFER,
                    DISBURSEMENT,
                    DISBURSEMENT_SUCCESS,
                    LOW_BALANCE_WARNING,
                    INSUFFICIENT_BALANCE -> NotificationPriority.HIGH;

            case BUDGET_LIMIT_WARNING,
                    BUDGET_END_SOON,
                    DISBURSEMENT_READY,
                    DISBURSEMENT_FAILED,
                    GOAL_ACHIEVED,
                    WELCOME -> NotificationPriority.MEDIUM;

            default -> NotificationPriority.LOW;
        };
    }

    @Async // <--- THIS IS THE MAGIC KEY
    public void sendWelcomeEmail(String email, String accountNumber, String bankName, BigDecimal balance) {

        if ("stub".equals(activeProfile) || mailSender == null) return;

        try {
            // 1. Prepare the Data (Context)
            Context context = new Context();
            context.setVariable("accountNumber", accountNumber);
            context.setVariable("bankName", bankName != null ? bankName : "Wema Bank"); // Fallback if null

            // 2. Process the HTML Template
            String htmlContent = templateEngine.process("welcome-email", context);

            // 3. Send the Email
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true); // true = multipart/HTML

            helper.setFrom(fromEmail); // Uses your verified Brevo email
            helper.setTo(email);
            helper.setSubject("🚀 Welcome to Wisemonie! Your Account is Ready");
            helper.setText(htmlContent, true); // true = Send as HTML

            mailSender.send(mimeMessage);
            logger.info("Sent HTML welcome email to {}", email);

        } catch (MessagingException e) {
            logger.error("Failed to send welcome email to {}: {}", email, e.getMessage());
        }
    }

    @Async
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

    @Async // Don't make the user wait for the email
    public void sendOtpEmail(String email, String otpCode) {
        if ("stub".equals(activeProfile) || mailSender == null) return;

        try {
            Context context = new Context();
            context.setVariable("otpCode", otpCode); // Pass the code to HTML

            String htmlContent = templateEngine.process("otp-email", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail);
            helper.setTo(email);
            helper.setSubject("🔐 Wisemonie Verification Code: " + otpCode);
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            logger.info("Sent OTP email to {}", email);

        } catch (Exception e) {
            logger.error("Failed to send OTP email to {}: {}", email, e.getMessage());
        }
    }

    @Async // Runs in background (No connection leaks!)
    public void sendPasswordResetEmail(String to, String userName, String resetLink) {

        // Stop if in stub mode or mailSender is missing
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Would send Password Reset email to {}", to);
            return;
        }

        try {
            // 1. Prepare Data for Thymeleaf
            Context context = new Context();
            context.setVariable("userName", userName);   // Matches <span th:text="${userName}">
            context.setVariable("resetLink", resetLink); // Matches <a th:href="${resetLink}">

            // 2. Render the HTML
            // Ensure you have src/main/resources/templates/reset-password.html
            String htmlContent = templateEngine.process("reset-password", context);

            // 3. Configure the Email
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail); // ✅ REUSES YOUR WORKING SENDER EMAIL!
            helper.setTo(to);
            helper.setSubject("🔒 Reset Your Wisemonie Password");
            helper.setText(htmlContent, true);

            // 4. Send
            mailSender.send(mimeMessage);
            logger.info("✅ Sent password reset email to {}", to);

        } catch (Exception e) {
            logger.error("❌ Failed to send password reset email to {}: {}", to, e.getMessage());
        }
    }

    @Async
    public void sendPasswordResetOtp(String to, String userName, String otpCode) {

        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending Password Reset OTP {} to {}", otpCode, to);
            return;
        }

        try {
            // 1. Prepare Thymeleaf Context
            Context context = new Context();
            context.setVariable("userName", userName);
            context.setVariable("otpCode", otpCode); // ✅ Passes code to HTML

            // 2. Render Template (Uses 'otp-email.html')
            String htmlContent = templateEngine.process("otp-email", context);

            // 3. Configure Email
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail); // Reuses your working sender
            helper.setTo(to);
            helper.setSubject("🔑 Password Reset Code: " + otpCode);
            helper.setText(htmlContent, true);

            // 4. Send
            mailSender.send(mimeMessage);
            logger.info("✅ Sent Password Reset OTP to {}", to);

        } catch (Exception e) {
            logger.error("❌ Failed to send Reset OTP to {}: {}", to, e.getMessage());
        }
    }
}
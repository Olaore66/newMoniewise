package com.moniewise.moniewise_backend.service;

import com.google.firebase.messaging.*;
import com.moniewise.moniewise_backend.config.GenericNotificationEvent;
import com.moniewise.moniewise_backend.entity.Notification;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.NotificationPriority;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.NotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.twilio.Twilio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import javax.annotation.PostConstruct;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final FirebaseMessaging firebaseMessaging;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${spring.profiles.active:prod}")
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
    // 1. EVENT-DRIVEN LISTENER (The New Standard)
    // =========================================================================

    /**
     * 🟢 FINTECH BEST PRACTICE:
     * Only handle the notification AFTER the transaction commits successfully.
     * Prevents "Ghost Notifications" where a user gets an alert but no money moved.
     */
//    @Async
//    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
//    public void handleNotificationEvent(GenericNotificationEvent event) {
//        try {
//            // 1. Centralized Message Generation
//            String message = generateMessage(event.getType(), event.getParams());
//            NotificationPriority priority = getPriority(event.getType());
//
//            // 🛑 NEW: Determine if this notification should live in the DB forever
//            boolean shouldSaveToDatabase = shouldPersistToDatabase(event.getType());
//
//            // 2. Persist to DB (ONLY if it's an important event)
//            if (shouldSaveToDatabase) {
//                Notification notification = new Notification();
//                notification.setUserId(Long.valueOf(event.getUserId()));
//                notification.setMessage(message);
//                notification.setType(event.getType());
//                notification.setCreatedAt(LocalDateTime.now());
//                notification.setBudgetId(event.getContextId1());
//                notification.setEnvelopeId(event.getContextId2());
//                notification.setRedirectUrl(event.getActionUrl());
//                notification.setRead(false);
//                notificationRepository.save(notification);
//            }
//
//            // 3. Send FCM Push (We want to send pushes for MORE things than we save)
//            // Example: We push a 15-min warning to their phone, but we don't save it to the DB inbox.
//            Long userId = Long.valueOf(event.getUserId());
//            String fcmToken = userRepository.findFcmTokenById(userId);
//
//            if (fcmToken != null && !fcmToken.isEmpty() && !"stub".equals(activeProfile)) {
//                if (firebaseMessaging != null) {
//                    String dynamicTitle = getNotificationTitle(event.getType());
//                    sendFCMMessage(fcmToken, dynamicTitle, message, null, event.getActionUrl(), event.getType(), userId);
//                } else {
//                    logger.warn("⚠️ Skipping FCM: Firebase is not initialized.");
//                }
//            }
//
//        } catch (Exception e) {
//            logger.error("Failed to process notification event for user {}", event.getUserId(), e);
//        }
//    }

    // 👇 ADD THIS HELPER METHOD 👇
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleNotificationEvent(GenericNotificationEvent event) {
        try {
            String message = generateMessage(event.getType(), event.getParams());
            NotificationPriority priority = getPriority(event.getType());
            boolean shouldSaveToDatabase = shouldPersistToDatabase(event.getType());

            // 1. Save to App Inbox (If important)
            if (shouldSaveToDatabase) {
                Notification notification = new Notification();
                notification.setUserId(Long.valueOf(event.getUserId()));
                notification.setMessage(message);
                notification.setType(event.getType());
                notification.setCreatedAt(LocalDateTime.now());
                notification.setBudgetId(event.getContextId1());
                notification.setEnvelopeId(event.getContextId2());
                notification.setRedirectUrl(event.getActionUrl());
                notification.setRead(false);
                notificationRepository.save(notification);
            }

            // 2. Send FCM Push (Only for HIGH or MEDIUM priority)
            if (priority == NotificationPriority.HIGH || priority == NotificationPriority.MEDIUM) {
                Long userId = Long.valueOf(event.getUserId());
                String fcmToken = userRepository.findFcmTokenById(userId);

                if ("stub".equals(activeProfile)) {
                    logger.info("🛑 [STUB MODE] Simulated Push Notification to User {}: {}", userId, message);
                } else if (fcmToken != null && !fcmToken.isEmpty()) {
                    if (firebaseMessaging != null) {
                        String dynamicTitle = getNotificationTitle(event.getType());
                        sendFCMMessage(fcmToken, dynamicTitle, message, null, event.getActionUrl(), event.getType(), userId);
                    } else {
                        logger.warn("⚠️ FCM is not initialized. Cannot send push.");
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to process notification event for user {}", event.getUserId(), e);
        }
    }

    /**
     * Determines which events are permanently saved in the user's in-app Inbox.
     * Spammy events (like 5-minute warnings) return false.
     */
    private boolean shouldPersistToDatabase(NotificationType type) {
        if (type == null) return false;

        return switch (type) {
            // ❌ DO NOT SAVE TO INBOX (Transient, Nudges, or Bundled Noise)
            case PRE_DISBURSEMENT, DISBURSEMENT_REMINDER, POSITIVE_NUDGE, WELCOME,
                    BUDGET_CREATION_FEE, ENVELOPE_CREATED -> false; // <--- Added here!

            // ✅ SAVE TO INBOX (Financial / Important)
            case WALLET_FUNDED, WALLET_DEPOSIT, REFUND_ISSUED,
                    WITHDRAWAL, EXTERNAL_TRANSFER, ENVELOPE_TRANSFER,
                    DISBURSEMENT_SUCCESS, EXPIRED_DISBURSEMENT, DISBURSEMENT_FAILED,
                    INSUFFICIENT_BALANCE, LOW_BALANCE_WARNING, ENVELOPE_LOW_BALANCE,
                    LIMIT_REACHED, BUDGET_LIMIT_WARNING, EMERGENCY_USED,
                    BUDGET_CREATION, BUDGET_COMPLETED,
                    ENVELOPE_UPDATED, ENVELOPE_LOCKED, ENVELOPE_UNLOCKED,
                    BUDGET_END, BUDGET_END_SOON, SYSTEM -> true;

            default -> true;
        };
    }

   /**
     * 🟢 CENTRALIZED COPY: Premium Fintech Notification Phrasing
     */
    private String generateMessage(NotificationType type, Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "You have a new update from Wisemonie.";

        return switch (type) {
            // ── Credit / Money In ────────────────────────────────────────────────
            case WALLET_FUNDED, WALLET_DEPOSIT -> {
                String amount = formatAmount(params.getOrDefault("amount", "0"));
                String sender = (String) params.get("senderName"); // Ensure this matches what you pass in the event

                if (sender != null && !sender.isEmpty()) {
                    yield String.format("₦%s has been credited to your wallet from %s.", amount, sender);
                } else {
                    yield String.format("Your wallet has been funded with ₦%s.", amount);
                }
            }

            // ── Transfers (Envelope to Envelope) ─────────────────────────────────
            case ENVELOPE_TRANSFER -> {
                String amount = formatAmount(params.getOrDefault("amount", "0"));
                String remaining = formatAmount(params.getOrDefault("remaining", "0"));
                String recipient = (String) params.get("recipient");

                if (recipient != null) {
                    yield String.format("You successfully sent ₦%s to %s.", amount, recipient);
                } else {
                    // Internal transfer
                    yield String.format("You moved ₦%s. You have ₦%s left to spend.", amount, remaining);
                }
            }

            // ── External Transfers ───────────────────────────────────────────────
            case EXTERNAL_TRANSFER -> {
                String amount = formatAmount(params.getOrDefault("amount", "0"));
                String recipient = (String) params.get("recipient");
                yield String.format("Your transfer of ₦%s to %s was successful.", amount, recipient);
            }

            // ── Budgets ──────────────────────────────────────────────────────────
            case BUDGET_CREATION -> {
                String amount = formatAmount(params.getOrDefault("allocated", "0"));
                String name = (String) params.get("budgetName");
                String fee = formatAmount(params.getOrDefault("fee", "0"));
                String envCount = String.valueOf(params.getOrDefault("envelopeCount", "your"));

                yield String.format("And we're live! 🎯 Your '%s' budget is set up with ₦%s across %s envelopes. (Includes ₦%s setup fee).",
                        name, amount, envCount, fee);
            }

            case BUDGET_CREATION_FEE -> {
                String amount = formatAmount(params.getOrDefault("amount", "0"));
                yield String.format("A budget creation fee of ₦%s was deducted from your wallet.", amount);
            }

            case BUDGET_COMPLETED -> {
                String amount = formatAmount(params.getOrDefault("refunded", "0"));
                String name = (String) params.get("budgetName");
                yield String.format("Great job! Your budget '%s' has ended. ₦%s of unused funds has been returned to your wallet.", name, amount);
            }

            // ── Envelope/Disbursement Events ─────────────────────────────────────
            case ENVELOPE_CREATED -> {
                String name = (String) params.get("envelopeName");
                String amount = formatAmount(params.getOrDefault("amount", "0"));
                yield String.format("You've set aside ₦%s in your new '%s' envelope.", amount, name);
            }

            case DISBURSEMENT_SUCCESS -> {
                String amount = formatAmount(params.getOrDefault("amount", "0"));
                String name = (String) params.get("envelopeName");
                yield String.format("₦%s has been unlocked in your '%s' envelope. It's ready to spend!", amount, name);
            }

            case PRE_DISBURSEMENT -> {
                String amount = formatAmount(params.getOrDefault("amount", "0"));
                String name = (String) params.get("envelopeName");
                yield String.format("Get ready! ₦%s will be unlocked in your '%s' envelope shortly.", amount, name);
            }

            case EXPIRED_DISBURSEMENT -> {
                String name = (String) params.get("envelopeName");
                yield String.format("The spending window for your '%s' envelope has closed. The funds remain safely in your vault.", name);
            }

            default -> "You have a new update regarding your account.";
        };
    }
    /**
     * Helper to format amount consistently (₦1,234.00)
     */
    private String formatAmount(Object value) {
        try {
            BigDecimal amount = new BigDecimal(value.toString());
            return String.format("%,.2f", amount);
        } catch (Exception e) {
            return value.toString();
        }
    }

    /**
     * Helper to add optional " • From: ..." part only if value exists
     */
    private String optionalPart(String format, Object value) {
        return value != null && !value.toString().trim().isEmpty()
                ? String.format(" • " + format, value)
                : "";
    }

    // =========================================================================
    // 2. LEGACY METHODS (For backwards compatibility during migration)
    // =========================================================================

    public void sendNotification(String userId, String message, NotificationType type) {
        sendNotification(userId, message, type, null, null, null, null);
    }

    public void sendNotification(
            String userId,
            String message,
            NotificationType type,
            Long budgetId,
            Long envelopeId,
            String actionType,
            String redirectUrl) {

        NotificationPriority priority = getPriority(type);

        try {
            Long uId = Long.valueOf(userId);

            // Save to DB
            Notification notification = new Notification();
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

            // Send Push
            if (priority == NotificationPriority.HIGH) {
                User user = userRepository.findById(uId).orElse(null);

                if (user != null && user.getFcmToken() != null && !user.getFcmToken().isEmpty() && !"stub".equals(activeProfile)) {
                    if (firebaseMessaging != null) {
                        // ✅ FIXED: Use the lightweight query here too
                        String fcmToken = userRepository.findFcmTokenById(uId);
                        String dynamicTitle = getNotificationTitle(type);
                        sendFCMMessage(fcmToken, dynamicTitle, message, actionType, redirectUrl, type, uId);
                    } else {
                        logger.warn("⚠️ Skipping FCM: Firebase is not initialized.");
                    }
                }
            } else {
                logger.info("Skipping LOW/MEDIUM priority FCM push: {}", type);
            }

        } catch (Exception e) {
            logger.error("Notification error for user {}: {}", userId, e.getMessage());
        }
    }

    // =========================================================================
    // 3. CORE FCM LOGIC
    // =========================================================================
    private void sendFCMMessage(String fcmToken, String title, String body, String actionType, String redirectUrl, NotificationType type, Long userId) {
        try {
            String collapseKey = getGroupKey(type);

            // 1. Define the Visible Notification (For System Tray)
            // ✅ THIS IS THE MISSING PIECE
            com.google.firebase.messaging.Notification notificationPayload =
                    com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build();

            AndroidConfig androidConfig = AndroidConfig.builder()
                    .setTtl(86400 * 1000) // 24 hours
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setNotification(AndroidNotification.builder()
                            .setChannelId("wisemonie_alerts_v2") // Must match Flutter Channel
                            .setSound("wisemonie")
                            .setDefaultSound(false)
                            .setTitle(title)
                            .setBody(body)
                            .setPriority(AndroidNotification.Priority.MAX)
                            .setVisibility(AndroidNotification.Visibility.PUBLIC)
                            .setClickAction("FLUTTER_NOTIFICATION_CLICK")
                            .build())
                    .build();

            ApnsConfig apnsConfig = ApnsConfig.builder()
                    .setAps(Aps.builder()
                            .setSound("wisemonie.wav")
                            .setContentAvailable(true)
                            .setThreadId(collapseKey)
                            .build())
                    .build();

            Message.Builder messageBuilder = Message.builder()
                    .setToken(fcmToken) // 👈 Use the string directly
                    .setNotification(notificationPayload)
                    .setAndroidConfig(androidConfig)
                    .setApnsConfig(apnsConfig);

            // Data Payload for Flutter navigation
            messageBuilder.putData("click_action", "FLUTTER_NOTIFICATION_CLICK");
            if (actionType != null) messageBuilder.putData("actionType", actionType);
            if (redirectUrl != null) messageBuilder.putData("redirectUrl", redirectUrl);

            // Text Payload for UI
            messageBuilder.putData("title", title);
            messageBuilder.putData("body", body);

            firebaseMessaging.send(messageBuilder.build());
//            logger.info("Sent FCM to user {}: {}", user.getId(), title);

        } catch (FirebaseMessagingException e) {
            String errorCode = e.getMessagingErrorCode().toString();
            if (errorCode.equals("UNREGISTERED") || errorCode.equals("NOT_FOUND") || errorCode.equals("INVALID_ARGUMENT")) {
                logger.warn("🚨 Token for user {} is dead. Removing it.", userId);
                // ✅ FIXED: Direct DB update instead of fetching User entity
                try {
                    userRepository.clearFcmToken(userId);
                } catch (Exception ex) {
                    logger.error("Failed to clear dead token for user {}", userId, ex);
                }
            } else {
                logger.error("Failed to send FCM message: {}", e.getMessage());
            }
        }
    }

    // =========================================================================
    // 4. CONFIGURATION HELPERS
    // =========================================================================

    private String getNotificationTitle(NotificationType type) {
        if (type == null) return "Wisemonie";

        return switch (type) {
            case WALLET_FUNDED, WALLET_DEPOSIT, REFUND_ISSUED, DISBURSEMENT_REFUNDED, BUDGET_UNALLOCATED_REFUNDED -> "Credit Alert 🚀";
            case WITHDRAWAL, EXTERNAL_TRANSFER, ENVELOPE_TRANSFER, BUDGET_CREATION_FEE -> "Debit Alert 💸";
            case DISBURSEMENT, DISBURSEMENT_SUCCESS, DISBURSEMENT_READY -> "Funds Released 🔓";
            case PRE_DISBURSEMENT, DISBURSEMENT_REMINDER -> "Disbursement Ready ⏳";
            case EXPIRED_DISBURSEMENT, DISBURSEMENT_FAILED -> "Disbursement Expired ❌";
            case INSUFFICIENT_BALANCE -> "Transaction Declined ⛔";
            case LOW_BALANCE_WARNING, ENVELOPE_LOW_BALANCE -> "Low Balance Warning 📉";
            case LIMIT_REACHED, BUDGET_LIMIT_WARNING -> "Spending Limit Hit ⚠️";
            case EMERGENCY_USED -> "Emergency Fund Used 🚨";
            case BUDGET_CREATION, BUDGET_CREATION_SUCCESS -> "Budget Active 🎯";
            case BUDGET_COMPLETED, GOAL_ACHIEVED -> "Goal Smashed! 🏆";
            case ENVELOPE_CREATED -> "New Envelope ✉️";
            case ENVELOPE_UPDATED, BUDGET_UPDATED, ENVELOPE_UNLOCKED -> "Update Successful ✅";
            case ENVELOPE_LOCKED -> "Envelope Locked 🔒";
            case BUDGET_END, BUDGET_EXPIRED -> "Budget Ended 🏁";
            case BUDGET_END_SOON, BUDGET_ENDING_SOON -> "Budget Ending Soon ⏳";
            case MATURITY_ALERT -> "Maturity Alert 📅";
            case WEEKLY_SUMMARY -> "Weekly Recap 📊";
            case WELCOME -> "Welcome to Wisemonie app👋";
            case SYSTEM -> "System Update 📢";
            case POSITIVE_NUDGE -> "Keep it up! 💪";
            default -> "Wisemonie Notification";
        };
    }

    private String getGroupKey(NotificationType type) {
        if (type == null) return "GENERAL";
        return switch (type) {
            case DISBURSEMENT, DISBURSEMENT_SUCCESS, DISBURSEMENT_READY -> "DISBURSEMENTS";
            case WALLET_DEPOSIT, WALLET_FUNDED, ENVELOPE_TRANSFER -> "TRANSACTIONS";
            case LOW_BALANCE_WARNING, BUDGET_LIMIT_WARNING -> "WARNINGS";
            default -> "GENERAL";
        };
    }

    private NotificationPriority getPriority(NotificationType type) {
        return switch (type) {
            case WALLET_DEPOSIT, WALLET_FUNDED, ENVELOPE_TRANSFER, EXTERNAL_TRANSFER,
                    LOW_BALANCE_WARNING, INSUFFICIENT_BALANCE, DISBURSEMENT, DISBURSEMENT_SUCCESS, DISBURSEMENT_READY, PRE_DISBURSEMENT, BUDGET_COMPLETED -> NotificationPriority.HIGH;

            case BUDGET_LIMIT_WARNING, BUDGET_END_SOON, DISBURSEMENT_FAILED,
                    GOAL_ACHIEVED, WELCOME -> NotificationPriority.MEDIUM;
            default -> NotificationPriority.LOW;
        };
    }

    // =========================================================================
    // 5. EMAIL & SMS METHODS
    // =========================================================================
    @Async
    public void sendWelcomeEmail(String email, String accountNumber, String bankName, BigDecimal balance) {
        if ("stub".equals(activeProfile) || mailSender == null) return;
        try {
            Context context = new Context();
            context.setVariable("accountNumber", accountNumber);
            context.setVariable("bankName", bankName != null ? bankName : "Wema Bank");

            String htmlContent = templateEngine.process("welcome-email", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail);
            helper.setTo(email);
            helper.setSubject("🚀 Welcome to Wisemonie! Your Account is Ready");
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            logger.info("Sent HTML welcome email to {}", email);
        } catch (MessagingException e) {
            logger.error("Failed to send welcome email to {}: {}", email, e.getMessage());
        }
    }
    @Async
    public void sendOtpEmail(String email, String otpCode) {
        if ("stub".equals(activeProfile) || mailSender == null) return;
        try {
            Context context = new Context();
            context.setVariable("otpCode", otpCode);

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
    @Async
    public void sendPasswordResetOtp(String to, String userName, String otpCode) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending Password Reset OTP {} to {}", otpCode, to);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("userName", userName);
            context.setVariable("otpCode", otpCode);

            String htmlContent = templateEngine.process("otp-email", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject("🔑 Password Reset Code: " + otpCode);
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            logger.info("✅ Sent Password Reset OTP to {}", to);
        } catch (Exception e) {
            logger.error("❌ Failed to send Reset OTP to {}: {}", to, e.getMessage());
        }
    }
}
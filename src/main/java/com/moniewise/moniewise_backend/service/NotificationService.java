package com.moniewise.moniewise_backend.service;

import com.google.firebase.messaging.*;
import com.moniewise.moniewise_backend.config.GenericNotificationEvent;
import com.moniewise.moniewise_backend.dto.response.NotificationBulkReadResponse;
import com.moniewise.moniewise_backend.entity.Notification;
import com.moniewise.moniewise_backend.enums.NotificationPriority;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.NotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.twilio.Twilio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import javax.annotation.PostConstruct;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.moniewise.moniewise_backend.entity.OutboxEvent;
import com.moniewise.moniewise_backend.repository.OutboxEventRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final FirebaseMessaging firebaseMessaging;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AuthSessionService authSessionService;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    /** Self-proxy so the short prepare/finalize transactions below actually get their
     *  own transaction when called from the (non-transactional) deliverOutboxEvent
     *  orchestrator — self-invocation would otherwise bypass the proxy. */
    @Autowired
    @Lazy
    private NotificationService self;

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

    @Value("${app.base-url:http://localhost:9000}")
    private String appBaseUrl;

    @Autowired
    public NotificationService(
            @Autowired(required = false) FirebaseMessaging firebaseMessaging,
            UserRepository userRepository,
            NotificationRepository notificationRepository,
            OutboxEventRepository outboxEventRepository,
            AuthSessionService authSessionService,
            @Autowired(required = false) JavaMailSender mailSender,
            TemplateEngine templateEngine) {
        this.firebaseMessaging = firebaseMessaging;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.authSessionService = authSessionService;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    @Transactional
    public NotificationBulkReadResponse markAllNotificationsAsRead(Long userId) {
        int updatedCount = notificationRepository.markAllAsReadForUser(userId);
        long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(userId);
        return new NotificationBulkReadResponse(updatedCount, unreadCount);
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
     * ðŸŸ¢ FINTECH BEST PRACTICE:
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
//            // ðŸ›‘ NEW: Determine if this notification should live in the DB forever
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
//                    sendFCMMessage(fcmToken, dynamicTitle, message, null,
//                                    event.getActionUrl(), event.getType(), userId,
//                                    event.getContextId2()); // contextId2 = envelopeId
//                } else {
//                    logger.warn("âš ï¸ Skipping FCM: Firebase is not initialized.");
//                }
//            }
//
//        } catch (Exception e) {
//            logger.error("Failed to process notification event for user {}", event.getUserId(), e);
//        }
//    }

    // ðŸ‘‡ ADD THIS HELPER METHOD ðŸ‘‡
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleNotificationEvent(GenericNotificationEvent event) {
        try {
            String message = generateMessage(event.getType(), event.getParams());
            NotificationPriority priority = getPriority(event.getType());
            boolean shouldSaveToDatabase = shouldPersistToDatabase(event.getType());
            boolean pushEligible = priority == NotificationPriority.HIGH || priority == NotificationPriority.MEDIUM;

            Long userId = Long.valueOf(event.getUserId());
            List<String> fcmTokens = pushEligible ? authSessionService.getActiveFcmTokens(userId) : List.of();

            // 1. Save to App Inbox (If important)
            if (shouldSaveToDatabase) {
                Notification notification = new Notification();
                notification.setUserId(userId);
                notification.setMessage(message);
                notification.setType(event.getType());
                notification.setCreatedAt(LocalDateTime.now());
                notification.setBudgetId(event.getContextId1());
                notification.setEnvelopeId(event.getContextId2());
                notification.setRedirectUrl(event.getActionUrl());
                notification.setRead(false);
                // No active token right now (e.g. mid logout/re-login) — flag so
                // redeliverMissedPushes() can catch up once a fresh token registers.
                notification.setPushSent(!pushEligible || !fcmTokens.isEmpty());
                notificationRepository.save(notification);
            }

            // 2. Send FCM Push (Only for HIGH or MEDIUM priority)
            if (pushEligible) {
                if ("stub".equals(activeProfile)) {
                    logger.info("ðŸ›‘ [STUB MODE] Simulated Push Notification to User {}: {}", userId, message);
                } else if (!fcmTokens.isEmpty()) {
                    if (firebaseMessaging != null) {
                        String dynamicTitle = getNotificationTitle(event.getType());
                        for (String fcmToken : fcmTokens) {
                            sendFCMMessage(fcmToken, dynamicTitle, message, null,
                                    event.getActionUrl(), event.getType(), userId,
                                    event.getContextId2()); // contextId2 = envelopeId
                        }
                    } else {
                        logger.warn("âš ï¸ FCM is not initialized. Cannot send push.");
                    }
                } else {
                    logger.info("[FCM] No active token for user {} - queued for redelivery on next token registration", userId);
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
            // âŒ DO NOT SAVE TO INBOX (Transient, Nudges, or Bundled Noise)
            case PRE_DISBURSEMENT, DISBURSEMENT_REMINDER, POSITIVE_NUDGE, WELCOME,
                    BUDGET_CREATION_FEE, ENVELOPE_CREATED -> false; // <--- Added here!

            // âœ… SAVE TO INBOX (Financial / Important)
            case WALLET_FUNDED, WALLET_DEPOSIT, REFUND_ISSUED,
                    WITHDRAWAL, EXTERNAL_TRANSFER, ENVELOPE_TRANSFER,
                    DISBURSEMENT_SUCCESS, EXPIRED_DISBURSEMENT, DISBURSEMENT_FAILED,
                    INSUFFICIENT_BALANCE, LOW_BALANCE_WARNING, ENVELOPE_LOW_BALANCE,
                    LIMIT_REACHED, BUDGET_LIMIT_WARNING, EMERGENCY_USED,
                    BUDGET_CREATION, BUDGET_COMPLETED,
                    ENVELOPE_UPDATED, ENVELOPE_LOCKED, ENVELOPE_UNLOCKED,
                    BUDGET_END, BUDGET_END_SOON, BUDGET_ENDS_TODAY, SYSTEM -> true;

            default -> true;
        };
    }

   /**
     * ðŸŸ¢ CENTRALIZED COPY: Premium Fintech Notification Phrasing
     */
        private String generateMessage(NotificationType type, Map<String, Object> params) {
        try {
            if (type == null) {
                return "Notification";
            }

            if (params == null || params.isEmpty()) {
                return "You have a new update from Wisemonie.";
            }

            return switch (type) {
                case WALLET_FUNDED, WALLET_DEPOSIT -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    String sender = safeText(params.get("senderName"), null);

                    if (sender != null) {
                        yield "\u20A6" + amount + " has been credited to your wallet from " + sender + ".";
                    }
                    yield "Your wallet has been funded with \u20A6" + amount + ".";
                }
                case ENVELOPE_TRANSFER -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    String remaining = formatAmount(params.getOrDefault("remaining", "0"));
                    String recipient = safeText(params.get("recipient"), null);

                    if (recipient != null) {
                        yield "You successfully sent \u20A6" + amount + " to " + recipient + ".";
                    }
                    yield "You moved \u20A6" + amount + ". You have \u20A6" + remaining + " left to spend.";
                }
                case EXTERNAL_TRANSFER -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    String recipient = safeText(params.get("recipient"), "the recipient");
                    yield "Your transfer of \u20A6" + amount + " to " + recipient + " was successful.";
                }
                case BUDGET_CREATION -> {
                    String amount = formatAmount(params.getOrDefault("allocated", "0"));
                    String name = safeText(params.get("budgetName"), "your");
                    String fee = formatAmount(params.getOrDefault("fee", "0"));
                    String envCount = safeText(params.get("envelopeCount"), "your");
                    yield "And we're live! Your '" + name + "' budget is set up with \u20A6" + amount
                            + " across " + envCount + " envelopes. (Includes \u20A6" + fee + " setup fee).";
                }
                case BUDGET_CREATION_FEE -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    yield "A budget creation fee of \u20A6" + amount + " was deducted from your wallet.";
                }
                case BUDGET_COMPLETED -> {
                    String amount = formatAmount(params.getOrDefault("refunded", "0"));
                    String name = safeText(params.get("budgetName"), "your");
                    yield "Great job! Your budget '" + name + "' has ended. \u20A6" + amount
                            + " of unused funds has been returned to your wallet.";
                }
                case ENVELOPE_CREATED -> {
                    String name = safeText(params.get("envelopeName"), "new");
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    yield "You've set aside \u20A6" + amount + " in your new '" + name + "' envelope.";
                }
                case DISBURSEMENT_SUCCESS -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    String name = safeText(params.get("envelopeName"), "selected");
                    yield "\u20A6" + amount + " has been unlocked in your '" + name + "' envelope. It's ready to spend!";
                }
//                case PRE_DISBURSEMENT -> {
//                    String amount = formatAmount(params.getOrDefault("amount", "0"));
//                    String name = safeText(params.get("envelopeName"), "selected");
//                    String time = safeText(params.get("time"), "shortly");
//                    yield "Get ready! \u20A6" + amount + " will be unlocked in your '" + name + "' envelope in " + time + ".";
//                }
                case EXPIRED_DISBURSEMENT -> {
                    String name = safeText(params.get("envelopeName"), "selected");
                    yield "The spending window for your '" + name + "' envelope has closed. The funds remain safely in your vault.";
                }
                case BUDGET_ENDS_TODAY -> {
                    String name = safeText(params.get("budgetName"), "your");
                    yield "Today is the last day of your '" + name + "' budget.";
                }
                default -> "You have a new update regarding your account.";
            };
        } catch (Exception e) {
            logger.error("Failed to generate notification message for type {}", type, e);
            return "Notification";
        }
    }
    /**
     * Helper to format amount consistently (â‚¦1,234.00)
     */
        private String formatAmount(Object value) {
        if (value == null) {
            return "0.00";
        }

        try {
            BigDecimal amount = new BigDecimal(value.toString());
            return String.format("%,.2f", amount);
        } catch (Exception e) {
            return value.toString();
        }
    }

    private String safeText(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }

        String text = value.toString().trim();
        return text.isEmpty() ? fallback : text;
    }
    /**
     * Helper to add optional " • From: ..." part only if value exists
     */
        private String optionalPart(String label, Object value) {
        String safeValue = safeText(value, null);
        if (safeValue == null) {
            return "";
        }
        return " • " + label + safeValue;
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
        // Enqueue onto the transactional outbox -- the single canonical delivery path.
        // Called inside a business @Transactional method, the outbox row commits/rolls
        // back atomically with that change (no ghost notifications). The outbox worker
        // then delivers it with retries + idempotency + one consistent priority rule.
        // The pre-formatted message rides in the payload so the worker doesn't re-build it.
        try {
            Long uId = Long.valueOf(userId);

            Map<String, Object> payload = new java.util.HashMap<>();
            if (message != null) payload.put("__message", message);
            if (actionType != null) payload.put("__actionType", actionType);
            if (redirectUrl != null) payload.put("__redirectUrl", redirectUrl);

            OutboxEvent event = new OutboxEvent();
            event.setEventType(type.name());
            event.setUserId(uId);
            event.setBudgetId(budgetId);
            event.setEnvelopeId(envelopeId);
            event.setPayload(payload);
            event.setStatus("PENDING");
            event.setCreatedAt(LocalDateTime.now());
            // Generous default TTL so nothing is dropped during offline / worker-downtime windows.
            event.setTtlSeconds(259_200L); // 72h
            outboxEventRepository.save(event);
        } catch (Exception e) {
            // A notification enqueue failure must never roll back the business change.
            logger.error("Failed to enqueue notification type {} for user {}", type, userId, e);
        }
    }

    // =========================================================================
    // 3. CORE FCM LOGIC
    // =========================================================================

    /**
     * Per-type FCM Time-To-Live (milliseconds).
     *
     * FCM honours TTL: if the device is offline when we push, FCM holds the message
     * for at most {@code ttlMs}. After that it drops it silently — exactly what we want
     * for time-sensitive nudges (PRE_DISBURSEMENT) but NOT for financial events.
     *
     * PRE_DISBURSEMENT / DISBURSEMENT_REMINDER  -> 45 min  (stale nudge is useless)
     * DISBURSEMENT_SUCCESS / credits / transfers -> 72 h   (must arrive eventually)
     * BUDGET_END_SOON / warnings                -> 24 h
     * LOW_BALANCE / ENVELOPE_LOW_BALANCE        ->  6 h
     * Default                                   -> 24 h
     */
    private long computeFcmTtlMs(NotificationType type) {
        if (type == null) return 86_400_000L;
        return switch (type) {
            case PRE_DISBURSEMENT, DISBURSEMENT_REMINDER                  -> 2_700_000L;   // 45 min
            case DISBURSEMENT_SUCCESS, DISBURSEMENT_READY, DISBURSEMENT,
                 WALLET_FUNDED, WALLET_DEPOSIT, EXTERNAL_TRANSFER,
                 ENVELOPE_TRANSFER, REFUND_ISSUED, DISBURSEMENT_REFUNDED,
                 BUDGET_UNALLOCATED_REFUNDED                              -> 259_200_000L; // 72 h
            case LOW_BALANCE_WARNING, ENVELOPE_LOW_BALANCE               -> 21_600_000L;  //  6 h
            default                                                       -> 86_400_000L;  // 24 h
        };
    }

    /**
     * Sends one FCM push notification.
     *
     * @param envelopeId used to build a per-envelope collapse key so that
     *                   multiple disbursement notifications are NOT silently
     *                   merged into one on Android.
     */
    private void sendFCMMessage(String fcmToken, String title, String body, String actionType,
                                String redirectUrl, NotificationType type, Long userId, Long envelopeId) {
        try {
            // ── Unique collapse key per envelope ─────────────────────────────────────
            // The old code used a single "DISBURSEMENTS" key for every disbursement
            // notification, which caused Android to keep only the *last* one and silently
            // discard all earlier ones.  A per-envelope key ensures every notification
            // for a different envelope is shown independently.
            String collapseKey = getGroupKey(type, userId, envelopeId);

            // ── Per-type FCM TTL ──────────────────────────────────────────────────────
            long ttlMs = computeFcmTtlMs(type);

            // 1. Define the Visible Notification (For System Tray)
            com.google.firebase.messaging.Notification notificationPayload =
                    com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build();

            AndroidConfig androidConfig = AndroidConfig.builder()
                    .setTtl(ttlMs)
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
                    .setToken(fcmToken)
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

            String messageId = firebaseMessaging.send(messageBuilder.build());
            logger.info("[FCM] Delivered to user {} type={} envelope={} ttlMs={} messageId={}",
                    userId, type, envelopeId, ttlMs, messageId);

        } catch (FirebaseMessagingException e) {
            String errorCode = e.getMessagingErrorCode() != null
                    ? e.getMessagingErrorCode().toString() : "UNKNOWN";

            if (errorCode.equals("UNREGISTERED") || errorCode.equals("NOT_FOUND")
                    || errorCode.equals("INVALID_ARGUMENT")) {
                // Dead token — clean it up but don’t retry (retrying with a dead
                // token will never succeed).
                logger.warn("[FCM] Dead token for user {} (code={}). Removing from sessions.", userId, errorCode);
                try {
                    authSessionService.clearDeadFcmToken(fcmToken);
                } catch (Exception ex) {
                    logger.error("[FCM] Failed to clear dead token for user {}", userId, ex);
                }
                // Do NOT rethrow — dead token errors are permanent, not retriable.
            } else {
                // Transient error (QUOTA_EXCEEDED, INTERNAL, UNAVAILABLE, etc.)
                // Rethrow so the outbox worker marks the event as PENDING and retries.
                logger.error("[FCM] Transient error for user {} (code={}). Will retry via outbox.", userId, errorCode, e);
                throw new RuntimeException("FCM transient failure: " + errorCode, e);
            }
        } catch (Exception e) {
            // Any other unexpected error — rethrow for outbox retry
            logger.error("[FCM] Unexpected error sending push to user {}", userId, e);
            throw new RuntimeException("FCM unexpected failure", e);
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
            case PRE_DISBURSEMENT, DISBURSEMENT_REMINDER -> "Funds Unlocking Soon ⏳";
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
            case BUDGET_ENDS_TODAY -> "Budget Ends Today ⏰";
            case MATURITY_ALERT -> "Maturity Alert 📅";
            case WEEKLY_SUMMARY -> "Weekly Recap 📊";
            case WELCOME -> "Welcome to Wisemonie 👋";
            case SYSTEM -> "System Update 📢";
            case POSITIVE_NUDGE -> "Keep it up! 💪";
            default -> "Wisemonie Notification";
        };
    }
    /**
     * Builds an Android/APNs collapse key for a notification.
     *
     * IMPORTANT: disbursement types get a PER-ENVELOPE unique key.
     * Using a shared "DISBURSEMENTS" key caused Android to keep only the
     * last notification and silently discard all earlier ones, so users
     * with multiple envelopes would miss every disbursement except the last.
     *
     * Transaction types still share a collapse key so that a rapid burst of
     * wallet-top-up events is consolidated — that's intentional and user-friendly.
     */
    private String getGroupKey(NotificationType type, Long userId, Long envelopeId) {
        if (type == null) return "GENERAL";
        return switch (type) {
            // Per-envelope key: each disbursement is an independent financial event
            case DISBURSEMENT, DISBURSEMENT_SUCCESS, DISBURSEMENT_READY ->
                    "DISB_" + (userId != null ? userId : "0")
                            + "_" + (envelopeId != null ? envelopeId : "0");
            case WALLET_DEPOSIT, WALLET_FUNDED, ENVELOPE_TRANSFER -> "TRANSACTIONS";
            case LOW_BALANCE_WARNING, BUDGET_LIMIT_WARNING -> "WARNINGS";
            default -> "GENERAL";
        };
    }

    private NotificationPriority getPriority(NotificationType type) {
        return switch (type) {
            case WALLET_DEPOSIT, WALLET_FUNDED, ENVELOPE_TRANSFER, EXTERNAL_TRANSFER,
                    LOW_BALANCE_WARNING, INSUFFICIENT_BALANCE, DISBURSEMENT, DISBURSEMENT_SUCCESS, DISBURSEMENT_READY, BUDGET_COMPLETED,
                    SAVINGS_GOAL_CREATED, SAVINGS_DEPOSIT, GOAL_ACHIEVED,
                    // "Your money is ready" is the single most important savings push —
                    // it was missing here, falling to default LOW = push never sent.
                    SAVINGS_MATURED,
                    ADMIN_PAYEELORD_LOW_BALANCE -> NotificationPriority.HIGH;

            case BUDGET_LIMIT_WARNING, BUDGET_END_SOON, BUDGET_ENDS_TODAY, DISBURSEMENT_FAILED,
                    SAVINGS_MATURING_SOON,
                    WELCOME -> NotificationPriority.MEDIUM;
            default -> NotificationPriority.LOW;
        };
    }

    // =========================================================================
    // 5. EMAIL & SMS METHODS
    // =========================================================================

    /**
     * Returns the absolute public URL for the Wisemonie logo.
     * The logo is served by Spring Boot from {@code static/images/wisemonie-logo.png}.
     * All email templates reference it via {@code th:src="${logoUrl}"} so the image
     * renders in every email client (webmail, mobile, desktop) without CID issues.
     */
    private String logoUrl() {
        return appBaseUrl + "/images/wisemonie-logo.png";
    }

    /** No-op kept for backward compatibility — CID approach replaced by hosted URL. */
    private void attachLogo(MimeMessageHelper helper) {
        // intentionally empty — logo is now served via public URL (logoUrl())
    }

    @Async
    public void sendWelcomeEmail(String email, String firstName, String accountNumber, String bankName, BigDecimal balance) {
        if ("stub".equals(activeProfile) || mailSender == null) return;
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("firstName", firstName != null && !firstName.isBlank() ? firstName : "there");
            context.setVariable("accountNumber", accountNumber);
            context.setVariable("bankName", bankName != null ? bankName : "Rubies MFB");

            String htmlContent = templateEngine.process("welcome-email", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail);
            helper.setTo(email);
            helper.setSubject("🎊 Welcome to Wisemonie! Your Account is Ready");
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent HTML welcome email to {}", email);
        } catch (MessagingException e) {
            logger.error("Failed to send welcome email to {}", email, e);
        }
    }

    /**
     * "Complete your profile" nudge email for users who signed up but never
     * finished KYC/profile (and therefore never got a wallet). Sent on a
     * cadence by {@code IncompleteSignupLifecycleManager}: ~24h after signup,
     * then every 5 days, until either the profile is completed or the
     * registration is purged at the 30-day mark.
     *
     * @param email               recipient
     * @param firstName           best-effort first name (falls back to "there")
     * @param completeProfileLink deep link back into the app's onboarding flow
     * @param daysSinceSignup     used only to pick a fitting subject line/tone
     * @param daysRemaining       days left before the account is purged — shown
     *                            only when {@code showUrgencyNotice} is true
     * @param showUrgencyNotice   true for the later reminders (close to the
     *                            30-day cutoff), renders the soft warning block
     */
    @Async
    public void sendOnboardingReminderEmail(String email, String firstName, String completeProfileLink,
                                             int daysSinceSignup, int daysRemaining, boolean showUrgencyNotice) {
        if ("stub".equals(activeProfile) || mailSender == null) return;
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("firstName", firstName != null && !firstName.isBlank() ? firstName : "there");
            context.setVariable("completeProfileLink", completeProfileLink);
            context.setVariable("daysRemaining", daysRemaining);
            context.setVariable("showUrgencyNotice", showUrgencyNotice);

            String htmlContent = templateEngine.process("onboarding-reminder", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail);
            helper.setTo(email);
            helper.setSubject(buildOnboardingReminderSubject(daysSinceSignup, showUrgencyNotice));
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent onboarding-reminder email (day {} since signup, urgent={}) to {}",
                    daysSinceSignup, showUrgencyNotice, email);
        } catch (MessagingException e) {
            logger.error("Failed to send onboarding-reminder email to {}", email, e);
        }
    }

    /**
     * Sent once, ~7 days before a savings goal's maturityDate (see
     * SavingsLifeCycleManager — guarded by SavingsGoal.maturityReminderSent so
     * it never goes out twice for the same goal).
     */
    @Async
    public void sendSavingsMaturingSoonEmail(String email, String firstName, String goalName,
                                              BigDecimal principal, BigDecimal accruedInterest,
                                              BigDecimal projectedPayout, String maturityDateLabel,
                                              int daysRemaining) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Savings maturing-soon email for '{}' ({} day(s) left) to {}", goalName, daysRemaining, email);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("firstName", firstName != null && !firstName.isBlank() ? firstName : "there");
            context.setVariable("goalName", goalName);
            context.setVariable("principal", formatAmount(principal));
            context.setVariable("accruedInterest", formatAmount(accruedInterest));
            context.setVariable("projectedPayout", formatAmount(projectedPayout));
            context.setVariable("maturityDateLabel", maturityDateLabel);
            context.setVariable("daysRemaining", daysRemaining);

            String htmlContent = templateEngine.process("savings-maturing-soon", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail);
            helper.setTo(email);
            helper.setSubject("⏳ Your '" + goalName + "' savings is maturing soon");
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent savings-maturing-soon email for '{}' ({} day(s) left) to {}",
                    goalName, daysRemaining, email);
        } catch (MessagingException e) {
            logger.error("Failed to send savings-maturing-soon email to {}", email, e);
        }
    }

    /**
     * Sent once, the day a savings goal's status flips to MATURED (see
     * SavingsLifeCycleManager). Distinct from sendSavingsMaturingSoonEmail()
     * above, which fires a week earlier as a heads-up, not on maturity itself.
     */
    @Async
    public void sendSavingsMaturedEmail(String email, String firstName, String goalName,
                                         BigDecimal principal, BigDecimal accruedInterest,
                                         BigDecimal totalPayout) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Savings matured email for '{}' to {}", goalName, email);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("firstName", firstName != null && !firstName.isBlank() ? firstName : "there");
            context.setVariable("goalName", goalName);
            context.setVariable("principal", formatAmount(principal));
            context.setVariable("accruedInterest", formatAmount(accruedInterest));
            context.setVariable("totalPayout", formatAmount(totalPayout));

            String htmlContent = templateEngine.process("savings-matured", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail);
            helper.setTo(email);
            helper.setSubject("🎉 Your '" + goalName + "' savings has matured!");
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent savings-matured email for '{}' to {}", goalName, email);
        } catch (MessagingException e) {
            logger.error("Failed to send savings-matured email to {}", email, e);
        }
    }

    private String buildOnboardingReminderSubject(int daysSinceSignup, boolean urgent) {
        if (urgent) {
            return "⏳ Your Wisemonie account is waiting — don't lose your spot";
        }
        if (daysSinceSignup <= 1) {
            return "👋 You're one step away from a calmer relationship with money";
        }
        return "Still thinking it over? Your Wisemonie account is right where you left it";
    }

    @Async
    public void sendOtpEmail(String email, String otpCode) {
        if ("stub".equals(activeProfile) || mailSender == null) return;
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("otpCode", otpCode);

            String htmlContent = templateEngine.process("otp-email", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail);
            helper.setTo(email);
            helper.setSubject("📩 Wisemonie Verification Code: " + otpCode);
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent OTP email to {}", email);
        } catch (Exception e) {
            logger.error("Failed to send OTP email to {}", email, e);
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
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("userName", userName);
            context.setVariable("otpCode", otpCode);

            String htmlContent = templateEngine.process("otp-email", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject("🔓 Password Reset Code: " + otpCode);
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("🔓 Sent Password Reset OTP to {}", to);
        } catch (Exception e) {
            logger.error("⚠️ Failed to send Reset OTP to {}", to, e);
        }
    }
    /**
     * Sent immediately after a successful transaction PIN change.
     * Passive security alert — no action required if the user made the change.
     * Fired async so the API response is not delayed.
     */
    @Async
    public void sendTransactionPinChangedAlert(String to, String userName, String changedAt) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending Transaction PIN changed alert to {}", to);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("userName", userName);
            context.setVariable("email", to);
            context.setVariable("changedAt", changedAt);

            String htmlContent = templateEngine.process("pin-changed-alert", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject("🔐 Your Wisemonie transaction PIN was changed");
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent transaction PIN changed alert to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send transaction PIN changed alert to {}", to, e);
        }
    }

    @Async
    public void sendTransactionPinResetOtp(String to, String userName, String otpCode) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending Transaction PIN Reset OTP {} to {}", otpCode, to);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("userName", userName);
            context.setVariable("otpCode", otpCode);

            String htmlContent = templateEngine.process("otp-email", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject("Transaction PIN Reset Code: " + otpCode);
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent Transaction PIN Reset OTP to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send Transaction PIN Reset OTP to {}", to, e);
        }
    }

    /**
     * Delivers a single outbox event, idempotently, keeping FCM <b>outside</b> any DB
     * transaction:
     * <ol>
     *   <li>{@link #prepareDelivery} (short txn) applies the terminal/stale guards and
     *       writes the in-app inbox row atomically with the {@code inboxSaved} flag,
     *       then returns the push plan (or {@code null} when there's nothing to push);</li>
     *   <li>the FCM push loop runs here holding no transaction / row lock, so slow or
     *       failing FCM calls never tie up a DB connection;</li>
     *   <li>{@link #finalizeDelivery} (short txn) records which tokens were delivered and
     *       marks the event PROCESSED, or schedules a retry.</li>
     * </ol>
     * Safe to retry: the inbox row is written at most once, each device token is pushed
     * at most once, and failures never rethrow.
     */
    public void deliverOutboxEvent(Long eventId) {
        DeliveryPlan plan;
        try {
            plan = self.prepareDelivery(eventId);
        } catch (Exception e) {
            logger.error("[OUTBOX] Failed to prepare event {}", eventId, e);
            return;
        }
        if (plan == null) {
            return; // terminal, stale, or nothing to push — already finalized in-txn
        }

        // --- FCM push: NO DB transaction / row lock is held across these network calls ---
        Set<String> delivered = new java.util.LinkedHashSet<>(plan.alreadyDelivered);
        String transientError = null;
        for (String token : plan.tokensToPush) {
            try {
                sendFCMMessage(token, plan.title, plan.message, plan.actionType, plan.redirectUrl,
                        plan.type, plan.userId, plan.envelopeId);
                delivered.add(token); // delivered (or dead token cleaned) — don't resend
            } catch (RuntimeException fcmError) {
                transientError = fcmError.getMessage(); // stop; persist progress + retry below
                break;
            }
        }

        try {
            self.finalizeDelivery(eventId, delivered, transientError);
        } catch (Exception e) {
            logger.error("[OUTBOX] Failed to finalize event {}", eventId, e);
        }
    }

    /**
     * Short transaction: terminal/stale guards, atomic inbox write (+ inboxSaved), and
     * computes the push plan. Returns {@code null} when nothing needs pushing (in which
     * case the event is finalized here).
     */
    @Transactional
    public DeliveryPlan prepareDelivery(Long eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null) {
            return null;
        }
        String currentStatus = event.getStatus();
        if ("PROCESSED".equals(currentStatus) || "STALE".equals(currentStatus)
                || "FAILED".equals(currentStatus)) {
            return null; // already resolved by another pass
        }

        // Staleness guard — don't deliver an alert whose relevance window has passed.
        Long ttlSeconds = event.getTtlSeconds();
        if (ttlSeconds != null && ttlSeconds > 0
                && LocalDateTime.now().isAfter(event.getCreatedAt().plusSeconds(ttlSeconds))) {
            event.setStatus("STALE");
            event.setProcessedAt(LocalDateTime.now());
            event.setLastError("Skipped: expired (ttl=" + ttlSeconds + "s, type="
                    + event.getEventType() + ")");
            outboxEventRepository.save(event);
            logger.info("[OUTBOX] Skipping stale event {} type={}", event.getId(), event.getEventType());
            return null;
        }

        NotificationType type = NotificationType.valueOf(event.getEventType());
        Map<String, Object> params = event.getPayload();
        String message = resolveMessage(type, params);
        String redirectUrl = resolveRedirectUrl(event, params);
        String actionType = paramString(params, "__actionType");
        NotificationPriority priority = getPriority(type);
        boolean pushEligible = priority == NotificationPriority.HIGH
                || priority == NotificationPriority.MEDIUM;

        List<String> tokens = pushEligible ? getPushTokensForUser(event.getUserId()) : List.of();

        // In-app inbox — written once, atomically with the inboxSaved flag.
        if (!event.isInboxSaved() && shouldPersistToDatabase(type)) {
            Notification notification = new Notification();
            notification.setUserId(event.getUserId());
            notification.setMessage(message);
            notification.setType(type);
            notification.setCreatedAt(LocalDateTime.now());
            notification.setBudgetId(event.getBudgetId());
            notification.setEnvelopeId(event.getEnvelopeId());
            notification.setActionType(actionType);
            notification.setRedirectUrl(redirectUrl);
            notification.setRead(false);
            // No active token right now (e.g. mid logout/re-login) — flag so
            // redeliverMissedPushes() catches up once a fresh token registers.
            notification.setPushSent(!pushEligible || !tokens.isEmpty());
            notificationRepository.save(notification);
            event.setInboxSaved(true);
        }

        // Which tokens still need a push (skip any a prior attempt already delivered).
        Set<String> alreadyDelivered = parseTokenSet(event.getDeliveredTokens());
        List<String> tokensToPush = new ArrayList<>();
        if (pushEligible && !"stub".equals(activeProfile) && firebaseMessaging != null) {
            for (String token : tokens) {
                if (!alreadyDelivered.contains(token)) {
                    tokensToPush.add(token);
                }
            }
        }

        if (tokensToPush.isEmpty()) {
            // Nothing to push (not eligible, no active token, or all already delivered).
            // redeliverMissedPushes() covers the no-token case via the pushSent=false flag.
            if (pushEligible && tokens.isEmpty()) {
                logger.info("[OUTBOX] No active token for user {} — will redeliver on next token registration",
                        event.getUserId());
            }
            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            event.setLastError(null);
            outboxEventRepository.save(event);
            logger.info("[OUTBOX] Delivered event {} type={} (no push)", event.getId(), event.getEventType());
            return null;
        }

        // Persist the inboxSaved flag now; the actual FCM happens outside this transaction.
        outboxEventRepository.save(event);

        DeliveryPlan plan = new DeliveryPlan();
        plan.message = message;
        plan.title = getNotificationTitle(type);
        plan.redirectUrl = redirectUrl;
        plan.actionType = actionType;
        plan.type = type;
        plan.userId = event.getUserId();
        plan.envelopeId = event.getEnvelopeId();
        plan.tokensToPush = tokensToPush;
        plan.alreadyDelivered = alreadyDelivered;
        return plan;
    }

    /**
     * Short transaction: records the tokens actually delivered and marks the event
     * PROCESSED, or schedules a retry when a transient FCM error interrupted the push.
     */
    @Transactional
    public void finalizeDelivery(Long eventId, Set<String> deliveredTokens, String transientError) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null) {
            return;
        }
        event.setDeliveredTokens(joinTokens(deliveredTokens));
        if (transientError != null) {
            scheduleRetry(event, transientError);
        } else {
            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            event.setLastError(null);
            logger.info("[OUTBOX] Delivered event {} type={}", event.getId(), event.getEventType());
        }
        outboxEventRepository.save(event);
    }

    /** Push plan produced by {@link #prepareDelivery}, consumed by the FCM loop. */
    private static final class DeliveryPlan {
        String message;
        String title;
        String redirectUrl;
        String actionType;
        NotificationType type;
        Long userId;
        Long envelopeId;
        List<String> tokensToPush;
        Set<String> alreadyDelivered;
    }

    /** Bumps retry count and moves the event to PENDING (retry) or FAILED (exhausted). */
    private void scheduleRetry(OutboxEvent event, String error) {
        int retryCount = event.getRetryCount() + 1;
        event.setRetryCount(retryCount);
        event.setLastError(error);
        if (retryCount >= 5) {
            event.setStatus("FAILED");
            logger.error("[OUTBOX] Event {} type={} FAILED permanently after {} attempts: {}",
                    event.getId(), event.getEventType(), retryCount, error);
        } else {
            event.setStatus("PENDING");
            logger.warn("[OUTBOX] Event {} type={} attempt {} failed — will retry: {}",
                    event.getId(), event.getEventType(), retryCount, error);
        }
    }

    /** Prefer the caller's pre-formatted message ("__message"); else generate one from params. */
    private String resolveMessage(NotificationType type, Map<String, Object> params) {
        String pre = paramString(params, "__message");
        return (pre != null) ? pre : generateMessage(type, params);
    }

    /** Prefer an explicit "__redirectUrl" from the payload; else derive one from context ids. */
    private String resolveRedirectUrl(OutboxEvent event, Map<String, Object> params) {
        String pre = paramString(params, "__redirectUrl");
        return (pre != null) ? pre : buildRedirectUrl(event);
    }

    /** Null-safe read of a string payload value; returns null for a missing/blank entry. */
    private static String paramString(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object v = params.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    private String buildRedirectUrl(OutboxEvent event) {
        if (event.getEnvelopeId() != null) {
            return "/envelopes/" + event.getEnvelopeId();
        }
        if (event.getBudgetId() != null) {
            return "/budgets/" + event.getBudgetId();
        }
        return "/notifications";
    }

    private static Set<String> parseTokenSet(String joined) {
        if (joined == null || joined.isBlank()) {
            return new java.util.LinkedHashSet<>();
        }
        return new java.util.LinkedHashSet<>(java.util.Arrays.asList(joined.split("\\|\\|")));
    }

    private static String joinTokens(Set<String> tokens) {
        return tokens.isEmpty() ? null : String.join("||", tokens);
    }

    private List<String> getPushTokensForUser(Long userId) {
        List<String> tokens = new java.util.ArrayList<>();

        try {
            List<String> activeTokens = authSessionService.getActiveFcmTokens(userId);
            if (activeTokens != null) {
                tokens.addAll(activeTokens);
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch active FCM tokens for user {}", userId, e);
        }

        try {
            String fallbackToken = userRepository.findFcmTokenById(userId);
            if (fallbackToken != null && !fallbackToken.isBlank()) {
                tokens.add(fallbackToken);
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch fallback FCM token for user {}", userId, e);
        }

        return tokens.stream()
                .filter(token -> token != null && !token.isBlank())
                .distinct()
                .toList();
    }

    /**
     * Sweeps recent notifications that were saved to the inbox but never pushed
     * (pushSent=false — no active FCM token at send time) and redelivers them.
     *
     * Called right after a fresh FCM token registers (see UserController's
     * POST /users/fcm-token), which is exactly the moment a device that was
     * unreachable becomes reachable again — e.g. a user who got auto-logged-out
     * by session timeout right as a scheduled disbursement fired, then logged
     * back in a few minutes later.
     *
     * Bounded to a 2-hour lookback so this never resurfaces stale notifications
     * (and, on first deploy, never floods a user with their entire history —
     * every pre-existing row defaults pushSent=false but falls outside the window).
     */
    @Transactional
    public void redeliverMissedPushes(Long userId) {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
            List<Notification> missed = notificationRepository
                    .findByUserIdAndPushSentFalseAndCreatedAtAfter(userId, cutoff);
            if (missed.isEmpty()) return;

            List<String> fcmTokens = getPushTokensForUser(userId);
            if (fcmTokens.isEmpty()) return;

            int redelivered = 0;
            for (Notification n : missed) {
                NotificationPriority priority = getPriority(n.getType());
                if (priority != NotificationPriority.HIGH && priority != NotificationPriority.MEDIUM) {
                    continue;
                }
                try {
                    String title = getNotificationTitle(n.getType());
                    for (String token : fcmTokens) {
                        sendFCMMessage(token, title, n.getMessage(), n.getActionType(),
                                n.getRedirectUrl(), n.getType(), userId, n.getEnvelopeId());
                    }
                    n.setPushSent(true);
                    redelivered++;
                } catch (Exception e) {
                    logger.warn("[FCM] Redelivery attempt failed for notification {} (user {})",
                            n.getId(), userId, e);
                }
            }
            notificationRepository.saveAll(missed);
            logger.info("[FCM] Redelivery sweep for user {}: {} of {} candidate(s) sent",
                    userId, redelivered, missed.size());
        } catch (Exception e) {
            logger.warn("[FCM] Failed to redeliver missed pushes for user {}", userId, e);
        }
    }
}



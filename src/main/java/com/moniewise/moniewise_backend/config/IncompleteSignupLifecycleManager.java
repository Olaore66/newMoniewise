package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.service.AuthSessionService;
import com.moniewise.moniewise_backend.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages the lifecycle of "abandoned signups" — users who created an account
 * (a {@code users} row exists) but never finished onboarding, so they have
 * neither a {@code wallets} row nor a {@code kyc_profiles} row.
 *
 * <p>Rather than letting these pile up forever (which is exactly how the
 * Rubies-sandbox duplicate-BVN mess happened) or deleting them the moment
 * someone gets distracted mid-signup, we run the cadence top fintechs use:
 *
 * <ul>
 *   <li><b>Day 1</b>  — first "complete your profile" nudge (~24h after signup)</li>
 *   <li><b>Day 2–30</b> — daily email + push reminders
 *       (the last 5 are flagged "urgent" so the email shows a soft warning
 *       that the slot will be released soon)</li>
 *   <li><b>Every 2 hours</b> — FCM-only push nudges between the daily emails</li>
 *   <li><b>Day 30</b> — if STILL incomplete, the registration is purged along
 *       with every dependent row, in the same leaf-to-root order as the
 *       manual cleanup script (transaction history → budgets/envelopes →
 *       kyc/wallet → user)</li>
 * </ul>
 *
 * This protects two things at once: it gives genuinely-interested users every
 * reasonable chance (and a friendly nudge) to finish, while keeping
 * {@code users}/{@code kyc_profiles} free of dead rows that would otherwise
 * collide with the BVN/phone/email uniqueness constraints we're restoring.
 */
@Component
public class IncompleteSignupLifecycleManager {

    private static final Logger logger = LoggerFactory.getLogger(IncompleteSignupLifecycleManager.class);

    /** First nudge lands ~24h after signup. */
    private static final int FIRST_REMINDER_DAY = 1;

    /** Every nudge after the first one is spaced this many days apart. */
    private static final int REMINDER_INTERVAL_DAYS = 1;

    /** Show the "your slot will be released soon" notice once we're this close to the purge. */
    private static final int URGENCY_THRESHOLD_DAYS = 5;

    /** Registrations that are still incomplete after this many days get purged. */
    private static final int PURGE_AFTER_DAYS = 30;

    private static final String COMPLETE_PROFILE_ROUTE = "/complete_profile/";
    private static final long PUSH_TTL_SECONDS = 7_200L;

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AuthSessionService authSessionService;
    private final NamedParameterJdbcTemplate jdbc;

    public IncompleteSignupLifecycleManager(UserRepository userRepository,
                                             NotificationService notificationService,
                                             AuthSessionService authSessionService,
                                             NamedParameterJdbcTemplate jdbc) {
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.authSessionService = authSessionService;
        this.jdbc = jdbc;
    }

    /**
     * Runs once a day at 9:00 AM server time — late enough that the email
     * lands in someone's morning inbox rather than at 1 AM.
     * CRON syntax: Seconds Minutes Hours DayOfMonth Month DayOfWeek
     */
    @Scheduled(cron = "0 0 9 * * ?")
    @Transactional
    public void processIncompleteSignups() {
        List<User> incomplete = userRepository.findIncompleteSignups();
        if (incomplete.isEmpty()) {
            return;
        }

        logger.info("[ONBOARDING] Reviewing {} incomplete signup(s)", incomplete.size());

        LocalDateTime now = LocalDateTime.now();
        List<Long> toPurge = new ArrayList<>();

        for (User user : incomplete) {
            long daysSinceSignup = ChronoUnit.DAYS.between(user.getCreatedAt(), now);

            if (daysSinceSignup >= PURGE_AFTER_DAYS) {
                toPurge.add(user.getId());
                continue;
            }

            if (isReminderDue(user, daysSinceSignup)) {
                sendReminder(user, daysSinceSignup);
            }
        }

        if (!toPurge.isEmpty()) {
            purgeIncompleteSignups(toPurge);
        }
    }

    // ───────────────────────────── REMINDERS ─────────────────────────────

    private boolean isReminderDue(User user, long daysSinceSignup) {
        int sentSoFar = user.getOnboardingReminderCount();

        long nextDueOnDay = (sentSoFar == 0)
                ? FIRST_REMINDER_DAY
                : FIRST_REMINDER_DAY + (long) sentSoFar * REMINDER_INTERVAL_DAYS;

        if (daysSinceSignup < nextDueOnDay) {
            return false;
        }

        // Guard against double-sends if the job runs more than once on the same day
        // (e.g. an app restart re-triggers the scheduler shortly after the first run).
        if (user.getLastOnboardingReminderAt() != null
                && user.getLastOnboardingReminderAt().toLocalDate().isEqual(LocalDate.now())) {
            return false;
        }

        return true;
    }

    private void sendReminder(User user, long daysSinceSignup) {
        String firstName = extractFirstName(user);
        long daysRemaining = Math.max(0, PURGE_AFTER_DAYS - daysSinceSignup);
        boolean urgent = daysRemaining <= URGENCY_THRESHOLD_DAYS;

        notificationService.sendOnboardingReminderEmail(
                user.getEmail(),
                firstName,
                (int) daysSinceSignup,
                (int) daysRemaining,
                urgent
        );

        sendPushIfReachable(user, urgent);

        user.setOnboardingReminderCount(user.getOnboardingReminderCount() + 1);
        user.setLastOnboardingReminderAt(LocalDateTime.now());
        userRepository.save(user);

        logger.info("[ONBOARDING] Sent reminder #{} (email+push) to {} (day {} since signup, {} day(s) until purge{})",
                user.getOnboardingReminderCount(), user.getEmail(), daysSinceSignup, daysRemaining,
                urgent ? ", URGENT" : "");
    }

    /**
     * Runs every 2 hours — sends FCM-only push to incomplete users so they
     * re-open the app and finish onboarding. No email on this cadence.
     */
    @Scheduled(cron = "0 0 */2 * * ?")
    public void pushIncompleteSignups() {
        List<User> incomplete = userRepository.findIncompleteSignups();
        if (incomplete.isEmpty()) {
            return;
        }

        int sent = 0;
        for (User user : incomplete) {
            long hoursSinceSignup = ChronoUnit.HOURS.between(user.getCreatedAt(), LocalDateTime.now());
            if (hoursSinceSignup < 1) {
                continue;
            }
            try {
                if (sendPushIfReachable(user, false)) {
                    sent++;
                }
            } catch (Exception e) {
                logger.debug("[ONBOARDING] Push failed for user={}: {}", user.getId(), e.getMessage());
            }
        }

        if (sent > 0) {
            logger.info("[ONBOARDING] 2h push cycle: sent {} onboarding push(es)", sent);
        }
    }

    private boolean sendPushIfReachable(User user, boolean urgent) {
        if (!hasPushTarget(user.getId())) {
            return false;
        }
        String title = urgent
                ? "Your account will be removed soon"
                : "Complete your profile to start using Wisemonie";
        String body = urgent
                ? "Finish setting up your profile now to keep your Wisemonie account."
                : "You're almost there! Complete your profile to unlock your wallet and start budgeting.";
        notificationService.enqueuePushOnlyNotification(
                user.getId(),
                NotificationType.ONBOARDING_REMINDER,
                title,
                body,
                COMPLETE_PROFILE_ROUTE,
                PUSH_TTL_SECONDS);
        return true;
    }

    private boolean hasPushTarget(Long userId) {
        if (userId == null) return false;
        try {
            if (!authSessionService.getActiveFcmTokens(userId).isEmpty()) return true;
        } catch (Exception ignored) {}
        try {
            String fallback = userRepository.findFcmTokenById(userId);
            return fallback != null && !fallback.isBlank();
        } catch (Exception ignored) {}
        return false;
    }

    /** Mirrors the same best-effort name extraction used for the welcome email. */
    private String extractFirstName(User user) {
        try {
            if (user.getProfileData() != null) {
                Object nameObj = user.getProfileData().get("name");
                if (nameObj != null) {
                    String fullName = nameObj.toString().trim();
                    return fullName.contains(" ") ? fullName.substring(0, fullName.indexOf(" ")) : fullName;
                }
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        return "";
    }

    // ───────────────────────────── PURGE ─────────────────────────────

    /**
     * Deletes a batch of stale, never-completed registrations and every row
     * that hangs off them — in the exact same leaf-to-root order as the
     * manual SQL cleanup script, so nothing trips a foreign-key violation
     * and nothing is left orphaned.
     * <p>
     * Deliberately implemented with explicit, ordered {@code DELETE}s rather
     * than {@code ON DELETE CASCADE} — we never want a future user-deletion
     * to silently cascade-wipe financial/audit history. This purge path is
     * the one narrow exception, reserved for accounts that never became real
     * customers in the first place (no wallet, no KYC, 30 days of silence).
     */
    @Transactional
    public void purgeIncompleteSignups(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }

        logger.info("[ONBOARDING] Purging {} stale incomplete signup(s) (>{} days, never finished profile): {}",
                userIds.size(), PURGE_AFTER_DAYS, userIds);

        Map<String, Object> params = Map.of("ids", userIds);

        // ── Leaf / dependent tables first ──
        deleteByUserId("transaction_requests", params);
        deleteByUserId("withdrawals", params);
        deleteByUserId("transaction_logs", params);
        deleteByUserId("outbox_events", params);
        deleteByUserId("notifications", params);
        deleteByUserId("pending_disbursements", params);
        deleteByUserId("analytics_logs", params);
        deleteByUserId("revenue_logs", params);
        deleteByUserId("otps", params);
        deleteByUserId("auth_sessions", params);
        deleteByUserId("beneficiaries", params);
        deleteByUserId("user_badges", params);
        deleteByUserId("user_goals", params);
        deleteByUserId("leaderboard_entries", params);
        deleteByUserId("savings_goals", params);
        deleteByUserId("user_tier_assignments", params);
        deleteByUserId("user_subscriptions", params);
        deleteByUserId("user_legal_acceptances", params);

        // Email-keyed tables (no FK, but hold dead references — clean for hygiene)
        deleteByEmailOfUserId("password_resets", params);
        deleteByEmailOfUserId("transaction_pin_resets", params);

        // Mid-level: envelopes hang off budgets, budgets hang off the user
        jdbc.update("""
                DELETE FROM envelopes
                WHERE budget_id IN (SELECT id FROM budgets WHERE user_id IN (:ids))
                """, params);
        deleteByUserId("budgets", params);

        // Near-root tables (defensive — these users have neither by definition,
        // but running it keeps the purge symmetric with the manual script)
        deleteByUserId("kyc_profiles", params);
        deleteByUserId("wallets", params);

        // Root
        int deleted = jdbc.update("DELETE FROM users WHERE id IN (:ids)", params);

        logger.info("[ONBOARDING] Purge complete — removed {} stale registration(s) and all dependent rows", deleted);
    }

    private void deleteByUserId(String table, Map<String, Object> params) {
        jdbc.update("DELETE FROM " + table + " WHERE user_id IN (:ids)", params);
    }

    private void deleteByEmailOfUserId(String table, Map<String, Object> params) {
        jdbc.update("DELETE FROM " + table + " WHERE email IN (SELECT email FROM users WHERE id IN (:ids))", params);
    }
}

package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.EngagementNudgeNotification;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.EngagementNudgeCampaign;
import com.moniewise.moniewise_backend.enums.EngagementNudgeChannel;
import com.moniewise.moniewise_backend.enums.EngagementNudgeSegment;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.BudgetEngagementNudgeRepository;
import com.moniewise.moniewise_backend.repository.EngagementNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.SalaryNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

@Service
public class HowToUseWisemonieNudgeService {

    private static final Logger logger = LoggerFactory.getLogger(HowToUseWisemonieNudgeService.class);
    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final String OCCASION_KEY = "HOW_TO_USE_WISEMONIE";
    private static final String WHATSAPP_CHANNEL_URL = "https://whatsapp.com/channel/0029Vb6kU683bbUy3azQF047";
    private static final String GUIDE_VIDEO_TITLE = "HOW TO USE WISEMONIE";
    private static final String EMAIL_TAG = "Start here";
    private static final String EMAIL_CTA = "Watch the guide";
    private static final long GUIDE_PUSH_TTL_SECONDS = 86_400L;
    private static final int MAX_TRACKED_MESSAGES_PER_DAY = 2;

    private final UserRepository userRepository;
    private final EngagementNudgeNotificationRepository nudgeRepository;
    private final SalaryNudgeNotificationRepository salaryNudgeRepository;
    private final BudgetEngagementNudgeRepository legacyBudgetNudgeRepository;
    private final NotificationService notificationService;
    private final AuthSessionService authSessionService;

    @Autowired
    @Lazy
    private HowToUseWisemonieNudgeService self;

    @Value("${moniewise.engagement.how-to-use.enabled:true}")
    private boolean enabled;

    @Value("${moniewise.engagement.how-to-use.batch-size:250}")
    private int batchSize;

    @Value("${moniewise.engagement.how-to-use.signup-lookback-days:15}")
    private int signupLookbackDays;

    @Value("${moniewise.engagement.how-to-use.repeat-interval-days:5}")
    private int repeatIntervalDays;

    public HowToUseWisemonieNudgeService(UserRepository userRepository,
                                         EngagementNudgeNotificationRepository nudgeRepository,
                                         SalaryNudgeNotificationRepository salaryNudgeRepository,
                                         BudgetEngagementNudgeRepository legacyBudgetNudgeRepository,
                                         NotificationService notificationService,
                                         AuthSessionService authSessionService) {
        this.userRepository = userRepository;
        this.nudgeRepository = nudgeRepository;
        this.salaryNudgeRepository = salaryNudgeRepository;
        this.legacyBudgetNudgeRepository = legacyBudgetNudgeRepository;
        this.notificationService = notificationService;
        this.authSessionService = authSessionService;
    }

    @Scheduled(cron = "${moniewise.engagement.how-to-use.cron:0 0 11 * * ?}", zone = "Africa/Lagos")
    public void processHowToUseNudges() {
        if (!enabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        LocalDateTime createdAfter = now.minusDays(Math.max(1, signupLookbackDays));
        LocalDateTime sentAfter = now.minusDays(Math.max(1, repeatIntervalDays));
        int pageSize = Math.max(1, batchSize);
        int sent = 0;
        Long afterUserId = 0L;

        while (true) {
            List<User> users = userRepository.findRecentWalletUsersNeedingHowToUseNudgeAfter(
                    afterUserId,
                    createdAfter,
                    now,
                    sentAfter,
                    pageSize);
            if (users.isEmpty()) {
                break;
            }

            for (User user : users) {
                afterUserId = user.getId();
                try {
                    if (self.sendHowToUseNudgeIfAllowed(user, now)) {
                        sent++;
                    }
                } catch (DataIntegrityViolationException e) {
                    logger.info("[HOW-TO-USE] Duplicate guide nudge skipped for user={}", user.getId());
                } catch (Exception e) {
                    logger.error("[HOW-TO-USE] Failed guide nudge for user={}", user.getId(), e);
                }
            }

            if (users.size() < pageSize) {
                break;
            }
        }

        if (sent > 0) {
            logger.info("[HOW-TO-USE] Sent {} guide nudge(s)", sent);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendHowToUseNudgeIfAllowed(User user, LocalDateTime now) {
        if (user == null || user.getId() == null || user.isDeleted() || user.isTestAccount() || !hasEmail(user)) {
            return false;
        }

        boolean pushAvailable = hasPushTarget(user.getId());
        if (!hasDailyRoom(user.getId(), now, pushAvailable)) {
            return false;
        }
        if (wasSentRecently(user.getId(), now)) {
            return false;
        }

        GuideCopy copy = selectCopy(user.getId(), now);
        String firstName = extractFirstName(user);
        String emailTitle = firstName + ", " + lowerFirstLetter(copy.emailTitle());
        String emailFooter = "Open the Wisemonie WhatsApp channel and watch the pinned video: \""
                + GUIDE_VIDEO_TITLE
                + "\". It shows wallet funding, budget creation, envelopes, disbursement and spending from the plan.";

        record(user.getId(), EngagementNudgeChannel.EMAIL, copy.key(), now);
        notificationService.sendEngagementNudgeEmail(
                user.getEmail(),
                firstName,
                emailTitle,
                EMAIL_TAG,
                emailTitle,
                copy.emailBody(),
                emailFooter,
                EMAIL_CTA,
                WHATSAPP_CHANNEL_URL);

        if (pushAvailable) {
            record(user.getId(), EngagementNudgeChannel.PUSH, copy.key(), now);
            notificationService.enqueueExternalPushOnlyNotification(
                    user.getId(),
                    NotificationType.HOW_TO_USE_WISEMONIE,
                    pushTitle(firstName, copy),
                    copy.pushBody(),
                    WHATSAPP_CHANNEL_URL,
                    GUIDE_PUSH_TTL_SECONDS);
        }

        return true;
    }

    private boolean wasSentRecently(Long userId, LocalDateTime now) {
        return nudgeRepository.countByUserIdAndCampaignAndSentAtAfter(
                userId,
                EngagementNudgeCampaign.HOW_TO_USE_WISEMONIE,
                now.minusDays(Math.max(1, repeatIntervalDays))) > 0;
    }

    private boolean hasDailyRoom(Long userId, LocalDateTime now, boolean pushAvailable) {
        int plannedMessages = pushAvailable ? 2 : 1;
        return totalTrackedMessagesToday(userId, now.toLocalDate()) + plannedMessages <= MAX_TRACKED_MESSAGES_PER_DAY;
    }

    private int totalTrackedMessagesToday(Long userId, LocalDate today) {
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        int engagementCount = nudgeRepository.countByUserIdAndSentDate(userId, today);
        int salaryCount = salaryNudgeRepository.countByUserIdAndSentDate(userId, today);
        int legacyBudgetCount = legacyBudgetNudgeRepository.existsByUserIdAndLastSentAtBetween(userId, start, end)
                ? 2
                : 0;
        return engagementCount + salaryCount + legacyBudgetCount;
    }

    private boolean hasPushTarget(Long userId) {
        try {
            if (!authSessionService.getActiveFcmTokens(userId).isEmpty()) {
                return true;
            }
        } catch (Exception e) {
            logger.debug("[HOW-TO-USE] Could not read active FCM sessions for user={}: {}", userId, e.getMessage());
        }

        try {
            String fallbackToken = userRepository.findFcmTokenById(userId);
            return fallbackToken != null && !fallbackToken.isBlank();
        } catch (Exception e) {
            logger.debug("[HOW-TO-USE] Could not read fallback FCM token for user={}: {}", userId, e.getMessage());
            return false;
        }
    }

    private void record(Long userId,
                        EngagementNudgeChannel channel,
                        String copyKey,
                        LocalDateTime sentAt) {
        EngagementNudgeNotification nudge = new EngagementNudgeNotification();
        nudge.setUserId(userId);
        nudge.setCampaign(EngagementNudgeCampaign.HOW_TO_USE_WISEMONIE);
        nudge.setSegment(EngagementNudgeSegment.NO_ACTIVE_BUDGET);
        nudge.setChannel(channel);
        nudge.setOccasionKey(OCCASION_KEY);
        nudge.setCopyKey(copyKey);
        nudge.setSentDate(sentAt.toLocalDate());
        nudge.setSentAt(sentAt);
        nudgeRepository.save(nudge);
    }

    private GuideCopy selectCopy(Long userId, LocalDateTime now) {
        Set<String> sentRecently = new HashSet<>(
                nudgeRepository.findCopyKeysSentSince(userId, now.minusMonths(3)));
        List<GuideCopy> eligible = guideCopies().stream()
                .filter(copy -> !sentRecently.contains(copy.key()))
                .toList();

        List<GuideCopy> pool = eligible.isEmpty() ? guideCopies() : eligible;
        return pool.get(new Random(Objects.hash(userId, now.toLocalDate())).nextInt(pool.size()));
    }

    private List<GuideCopy> guideCopies() {
        return List.of(
                new GuideCopy(
                        "how-to-use-01",
                        "your Wisemonie account is ready \uD83E\uDDED",
                        "You already did the brave part: opening the account. If the next step feels unclear, do not abandon it. Watch the video titled \""
                                + GUIDE_VIDEO_TITLE
                                + "\" on our WhatsApp channel; it shows how to fund your wallet, create or refresh envelopes and spend from a plan without mental maths.",
                        "Watch the guide \uD83C\uDFA5",
                        "Your Wisemonie wallet is ready. Open our WhatsApp channel and watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\" to use it with confidence."),
                new GuideCopy(
                        "how-to-use-02",
                        "start with one small plan \uD83C\uDF31",
                        "Money pressure often starts when everything sits in one big, confusing balance. The \""
                                + GUIDE_VIDEO_TITLE
                                + "\" video shows how to fund Wisemonie, split money into food, transport, savings and giving, then let the app help you keep discipline.",
                        "Start with one plan \uD83C\uDF31",
                        "Watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\" on the Wisemonie WhatsApp channel and set up a simple money plan."),
                new GuideCopy(
                        "how-to-use-03",
                        "no pressure, just a clearer first step \uD83D\uDE0C",
                        "Sometimes people sign up because they want better control, then pause because the first move is not obvious. We made a short guide for that. Open the WhatsApp channel and watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\" so your wallet and budget can work together as a real spending plan.",
                        "Quick first-step guide \uD83D\uDE0C",
                        "Not sure what to do next? Watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\" on our WhatsApp channel and make your next money move clearer."),
                new GuideCopy(
                        "how-to-use-04",
                        "turn the account into a plan \uD83C\uDFAF",
                        "A Wisemonie account becomes powerful when you tell the money what to do before pressure arrives. The guide video walks you through funding, creating envelopes, setting disbursement rules and spending from the right plan.",
                        "Turn it into a plan \uD83C\uDFAF",
                        "Your account is open. Watch the Wisemonie guide on WhatsApp to fund, budget and spend from envelopes."),
                new GuideCopy(
                        "how-to-use-05",
                        "your money can feel easier this month \uD83D\uDC9A",
                        "The app is not just a wallet; it is a way to remove the stress of calculating every spend in your head. Watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\" on our WhatsApp channel and see how to plan food, transport, family support, giving and savings before the month gets loud.",
                        "Make this month easier \uD83D\uDC9A",
                        "Open our WhatsApp channel and watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\". It shows how Wisemonie helps reduce money stress."),
                new GuideCopy(
                        "how-to-use-06",
                        "let Wisemonie show you the rhythm \uD83D\uDCDD",
                        "If you opened Wisemonie because you are tired of impulse spending, you are in the right place. The guide video shows how to put money in, create a budget, choose when money should be available and spend directly from the plan.",
                        "See how it works \uD83D\uDCDD",
                        "The Wisemonie guide is waiting on our WhatsApp channel. Watch it and learn how to fund, plan and spend calmly.")
        );
    }

    private String pushTitle(String firstName, GuideCopy copy) {
        if (firstName == null || firstName.isBlank() || "there".equalsIgnoreCase(firstName)) {
            return copy.pushTitle();
        }
        return firstName + ", " + lowerFirstLetter(copy.pushTitle());
    }

    private String extractFirstName(User user) {
        Optional<String> profileName = extractProfileName(user.getProfileData());
        if (profileName.isPresent()) {
            return profileName.get();
        }

        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            return "there";
        }
        String prefix = email.substring(0, email.indexOf("@") > 0 ? email.indexOf("@") : email.length()).trim();
        return prefix.isBlank() ? "there" : prefix;
    }

    private Optional<String> extractProfileName(Map<String, Object> profileData) {
        if (profileData == null || profileData.isEmpty()) {
            return Optional.empty();
        }

        String firstName = textValue(profileData.get("firstName"));
        if (firstName != null) {
            return Optional.of(firstName);
        }

        String fullName = textValue(profileData.get("name"));
        if (fullName == null) {
            return Optional.empty();
        }

        int firstSpace = fullName.indexOf(" ");
        return Optional.of(firstSpace > 0 ? fullName.substring(0, firstSpace) : fullName);
    }

    private String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private boolean hasEmail(User user) {
        return user.getEmail() != null && !user.getEmail().isBlank();
    }

    private String lowerFirstLetter(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int firstCodePoint = value.codePointAt(0);
        int lowerCodePoint = Character.toLowerCase(firstCodePoint);
        return new String(Character.toChars(lowerCodePoint)) + value.substring(Character.charCount(firstCodePoint));
    }

    private record GuideCopy(String key,
                             String emailTitle,
                             String emailBody,
                             String pushTitle,
                             String pushBody) {
    }
}

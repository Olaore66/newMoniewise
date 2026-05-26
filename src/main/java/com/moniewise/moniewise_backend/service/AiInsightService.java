package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.response.AiDashboardNextActionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class AiInsightService {

    private static final Logger logger = LoggerFactory.getLogger(AiInsightService.class);
    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter NEXT_AVAILABLE_FORMATTER =
        DateTimeFormatter.ofPattern("EEE, d MMM - h:mm a");
    private static final Duration SOON_DISBURSEMENT_WINDOW = Duration.ofHours(6);
    private static final Duration RECENT_RELEASE_WINDOW = Duration.ofHours(24);

    private final UserService userService;
    private final BudgetRepository budgetRepository;
    private final WalletRepository walletRepository;
    private final GeminiService geminiService;
    private final AiPromptService aiPromptService;
    private final ObjectMapper objectMapper;

    public AiInsightService(
        UserService userService,
        BudgetRepository budgetRepository,
        WalletRepository walletRepository,
        GeminiService geminiService,
        AiPromptService aiPromptService,
        ObjectMapper objectMapper
    ) {
        this.userService = userService;
        this.budgetRepository = budgetRepository;
        this.walletRepository = walletRepository;
        this.geminiService = geminiService;
        this.aiPromptService = aiPromptService;
        this.objectMapper = objectMapper;
    }

    public AiDashboardNextActionResponse getDashboardNextAction(String email) {
        // Build the context outside the Gemini try/catch — but guard against any
        // transient DB/wallet error so a single bad query never surfaces as a 500
        // and silently kills the AI card in the Flutter app.
        DashboardActionContext context;
        try {
            context = buildContext(email);
        } catch (Exception e) {
            logger.warn("AiInsightService: buildContext failed for {}: {}", email, e.getMessage());
            return buildFallback(new DashboardActionContext());
        }

        AiDashboardNextActionResponse deterministic = buildDeterministicRecommendation(context);
        if (context.candidates.isEmpty()) {
            return deterministic != null ? deterministic : buildFallback(context);
        }

        try {
            String candidatesJson = objectMapper.writeValueAsString(buildCandidatePayloads(context.candidates));
            String prompt = aiPromptService.buildDashboardNextActionPrompt(
                context.userName,
                context.walletBalance.doubleValue(),
                context.activeBudget != null,
                context.completedBudget != null,
                context.hasLinkedSettlementAccount,
                context.activeBudget != null ? context.activeBudget.getName() : "",
                context.daysUntilActiveBudgetEnds,
                candidatesJson
            );

            String rawText = geminiService.generateText(prompt);
            String cleanedText = cleanJson(rawText);
            AiDashboardNextActionResponse response =
                objectMapper.readValue(cleanedText, AiDashboardNextActionResponse.class);

            return sanitizeResponse(response, context, deterministic);
        } catch (Exception e) {
            logger.warn("AiInsightService: Gemini dashboard action failed, using deterministic response: {}", e.getMessage());
            return deterministic != null ? deterministic : buildFallback(context);
        }
    }

    private DashboardActionContext buildContext(String email) {
        User user = userService.findByEmail(email);
        Optional<Wallet> walletOpt = walletRepository.findByUserId(user.getId());
        Budget activeBudget = budgetRepository
            .findTopByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), BudgetStatus.ACTIVE)
            .orElse(null);
        Budget completedBudget = budgetRepository
            .findTopByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), BudgetStatus.COMPLETED)
            .orElse(null);

        List<Budget> budgetsWithEnvelopes = budgetRepository.findByUserIdWithEnvelopes(user.getId());
        List<Budget> activeBudgets = budgetsWithEnvelopes.stream()
            .filter(budget -> budget.getStatus() == BudgetStatus.ACTIVE)
            .toList();

        DashboardActionContext context = new DashboardActionContext();
        context.userName = user.getName() != null && !user.getName().isBlank()
            ? user.getName()
            : "there";
        context.walletBalance = walletOpt.map(Wallet::getBalance).orElse(BigDecimal.ZERO);
        context.hasLinkedSettlementAccount = walletOpt
            .map(wallet -> wallet.getSettlementAccountNumber() != null
                && !wallet.getSettlementAccountNumber().isBlank())
            .orElse(false);
        context.activeBudget = activeBudget;
        context.completedBudget = completedBudget;
        context.hasBudgetHistory = !activeBudgets.isEmpty() || completedBudget != null;
        context.activeBudgets = activeBudgets;
        if (activeBudget != null && activeBudget.getEndDate() != null) {
            long days = ChronoUnit.DAYS.between(LocalDate.now(LAGOS_ZONE), activeBudget.getEndDate());
            context.daysUntilActiveBudgetEnds = (int) Math.max(0, days);
        }
        context.candidates = rankCandidates(context);
        return context;
    }

    private List<ActionCandidate> rankCandidates(DashboardActionContext context) {
        List<ActionCandidate> candidates = new ArrayList<>();

        if (context.hasBudgetHistory && context.walletBalance.compareTo(BigDecimal.ZERO) <= 0) {
            candidates.add(baseCandidate(
                "Boss, your wallet is empty 👀",
                "Fund your wallet so your budget can actually do something. No money in = no plan running.",
                "Fund wallet",
                "fund_wallet",
                "high",
                "Wallet balance is zero while the user still has budget history.",
                1000
            ));
        }

        List<ActionCandidate> spendableEnvelopeCandidates = buildSpendableEnvelopeCandidates(context.activeBudgets);
        candidates.addAll(spendableEnvelopeCandidates);
        if (spendableEnvelopeCandidates.isEmpty()) {
            List<ActionCandidate> dueDisbursementCandidates = buildDueDisbursementCandidates(context.activeBudgets);
            candidates.addAll(dueDisbursementCandidates.isEmpty()
                ? buildNoSpendableEnvelopeCandidates(context.activeBudgets)
                : dueDisbursementCandidates);
        } else {
            candidates.addAll(buildUpcomingEnvelopeCandidates(context.activeBudgets));
        }

        if (context.activeBudget != null
            && context.daysUntilActiveBudgetEnds >= 0
            && context.daysUntilActiveBudgetEnds <= 3) {
            String daysText = context.daysUntilActiveBudgetEnds == 0
                ? "today"
                : "in " + context.daysUntilActiveBudgetEnds + " day(s)";
            ActionCandidate endingSoon = baseCandidate(
                context.activeBudget.getName() + " budget ends " + daysText,
                "Your " + context.activeBudget.getName() + " budget closes " + daysText + ". Review your envelope spending before it wraps up.",
                "Review budget",
                "review_active_budget",
                "urgent",
                "Active budget ends within 3 days.",
                900
            );
            endingSoon.budgetId = context.activeBudget.getId();
            endingSoon.budgetName = context.activeBudget.getName();
            candidates.add(endingSoon);
        }

        if (context.activeBudget == null && context.completedBudget == null) {
            candidates.add(baseCandidate(
                "Let's build your first budget 🎯",
                "Give your wallet balance a real job — build a spending plan with envelopes that match how you actually live.",
                "Start budget",
                "create_budget",
                "high",
                "The user has no budget history yet.",
                800
            ));
        }

        if (context.activeBudget == null && context.completedBudget != null) {
            candidates.add(baseCandidate(
                "New cycle, new plan — let's go 📅",
                "Your last budget wrapped up. Set up a fresh one so your money doesn't float around without a job this month.",
                "Create budget",
                "create_budget",
                "high",
                "The user has budget history but no active budget right now.",
                700
            ));
        }

        if (!context.hasLinkedSettlementAccount) {
            double score = context.walletBalance.compareTo(new BigDecimal("50000")) > 0 ? 620 : 420;
            candidates.add(baseCandidate(
                "Link your payout account",
                "Add your account details so when it's time to withdraw, everything's ready and friction-free.",
                "Set account",
                "set_account",
                "normal",
                "The user has not linked a payout account yet.",
                score
            ));
        }

        Map<String, ActionCandidate> unique = new LinkedHashMap<>();
        for (ActionCandidate candidate : candidates) {
            unique.putIfAbsent(candidate.uniqueKey(), candidate);
        }

        List<ActionCandidate> ranked = new ArrayList<>(unique.values());
        ranked.sort(Comparator.comparingDouble(ActionCandidate::score).reversed());
        return ranked.size() > 5 ? ranked.subList(0, 5) : ranked;
    }

    private List<ActionCandidate> buildSpendableEnvelopeCandidates(List<Budget> activeBudgets) {
        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        List<ActionCandidate> candidates = new ArrayList<>();

        for (Budget budget : activeBudgets) {
            if (budget.getEnvelopes() == null) {
                continue;
            }
            for (Envelope envelope : budget.getEnvelopes()) {
                if (!isEnvelopeSpendable(envelope) || !hasHealthyRemaining(envelope)) {
                    continue;
                }

                BigDecimal availableAmount = resolveSpendableAmount(envelope);
                double score = scoreSpendableEnvelope(budget, envelope, now, availableAmount);
                boolean disbursementReached = hasDisbursementReached(envelope, now) || wasRecentlyReleased(envelope, now);
                String message = disbursementReached
                    ? envelope.getName() + " has reached disbursement time. NGN "
                        + formatMoney(availableAmount) + " is ready to spend from " + budget.getName() + "."
                    : budget.getName() + " has NGN " + formatMoney(availableAmount)
                        + " available now in " + envelope.getName() + ".";
                ActionCandidate candidate = baseCandidate(
                    "Spend from " + envelope.getName(),
                    message,
                    "Go to " + envelope.getName(),
                    "review_active_budget",
                    "high",
                    disbursementReached
                        ? "This envelope's disbursement time has reached and it has spendable balance."
                        : "This envelope is currently unlocked and still has healthy spendable balance.",
                    score
                );
                candidate.budgetId = budget.getId();
                candidate.budgetName = budget.getName();
                candidate.envelopeId = envelope.getId();
                candidate.envelopeName = envelope.getName();
                candidate.amountValue = availableAmount != null ? availableAmount.doubleValue() : null;
                if (disbursementReached) {
                    candidate.nextAvailableAt = "now";
                    candidate.countdownText = "right now";
                }
                candidates.add(candidate);
            }
        }

        candidates.sort(Comparator.comparingDouble(ActionCandidate::score).reversed());
        return candidates.size() > 3 ? candidates.subList(0, 3) : candidates;
    }

    private List<ActionCandidate> buildUpcomingEnvelopeCandidates(List<Budget> activeBudgets) {
        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        List<ActionCandidate> candidates = new ArrayList<>();

        for (Budget budget : activeBudgets) {
            if (budget.getEnvelopes() == null) {
                continue;
            }
            for (Envelope envelope : budget.getEnvelopes()) {
                LocalDateTime nextDisbursementAt = envelope.getNextDisbursementAt();
                if (nextDisbursementAt == null || !nextDisbursementAt.isAfter(now) || !hasFutureValue(envelope)) {
                    continue;
                }

                BigDecimal upcomingAmount = resolveUpcomingAmount(envelope);
                candidates.add(buildUpcomingEnvelopeCandidate(budget, envelope, upcomingAmount, now, false));
            }
        }

        candidates.sort(Comparator.comparingDouble(ActionCandidate::score).reversed());
        return candidates.size() > 2 ? candidates.subList(0, 2) : candidates;
    }

    private List<ActionCandidate> buildDueDisbursementCandidates(List<Budget> activeBudgets) {
        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        List<ActionCandidate> candidates = new ArrayList<>();

        for (Budget budget : activeBudgets) {
            if (budget.getEnvelopes() == null) {
                continue;
            }
            for (Envelope envelope : budget.getEnvelopes()) {
                if (!hasDisbursementReached(envelope, now) || hasHealthyRemaining(envelope) || !hasFutureValue(envelope)) {
                    continue;
                }

                ActionCandidate candidate = baseCandidate(
                    envelope.getName() + " is due now",
                    envelope.getName() + " has reached disbursement time. Give it a moment; your naira is at the door.",
                    "Open " + envelope.getName(),
                    "review_active_budget",
                    "high",
                    "This envelope's scheduled disbursement time has reached, but no spendable balance is visible yet.",
                    790
                );
                candidate.budgetId = budget.getId();
                candidate.budgetName = budget.getName();
                candidate.envelopeId = envelope.getId();
                candidate.envelopeName = envelope.getName();
                candidate.amountValue = Optional.ofNullable(resolveUpcomingAmount(envelope))
                    .map(BigDecimal::doubleValue)
                    .orElse(null);
                candidate.nextAvailableAt = "now";
                candidate.countdownText = "right now";
                candidates.add(candidate);
            }
        }

        candidates.sort(Comparator.comparingDouble(ActionCandidate::score).reversed());
        return candidates.size() > 2 ? candidates.subList(0, 2) : candidates;
    }

    private List<ActionCandidate> buildNoSpendableEnvelopeCandidates(List<Budget> activeBudgets) {
        List<ActionCandidate> upcoming = buildUpcomingEnvelopeCandidates(activeBudgets, true);
        if (!upcoming.isEmpty()) {
            return List.of(upcoming.get(0));
        }

        Optional<Budget> firstActiveBudget = activeBudgets.stream().findFirst();
        if (firstActiveBudget.isEmpty()) {
            return List.of();
        }

        Budget budget = firstActiveBudget.get();
        ActionCandidate candidate = baseCandidate(
            "No envelope is ready yet",
            "No disbursement has reached yet. Tiny patience flex: your budget is keeping your naira in line.",
            "View budget",
            "review_active_budget",
            "normal",
            "No active envelope currently has spendable balance or a scheduled release to surface.",
            520
        );
        candidate.budgetId = budget.getId();
        candidate.budgetName = budget.getName();
        return List.of(candidate);
    }

    private List<ActionCandidate> buildUpcomingEnvelopeCandidates(
        List<Budget> activeBudgets,
        boolean noSpendablePrimary
    ) {
        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        List<ActionCandidate> candidates = new ArrayList<>();

        for (Budget budget : activeBudgets) {
            if (budget.getEnvelopes() == null) {
                continue;
            }
            for (Envelope envelope : budget.getEnvelopes()) {
                LocalDateTime nextDisbursementAt = envelope.getNextDisbursementAt();
                if (nextDisbursementAt == null || !nextDisbursementAt.isAfter(now) || !hasFutureValue(envelope)) {
                    continue;
                }

                BigDecimal upcomingAmount = resolveUpcomingAmount(envelope);
                candidates.add(buildUpcomingEnvelopeCandidate(
                    budget,
                    envelope,
                    upcomingAmount,
                    now,
                    noSpendablePrimary
                ));
            }
        }

        candidates.sort(Comparator.comparingDouble(ActionCandidate::score).reversed());
        int limit = noSpendablePrimary ? 1 : 2;
        return candidates.size() > limit ? candidates.subList(0, limit) : candidates;
    }

    private ActionCandidate buildUpcomingEnvelopeCandidate(
        Budget budget,
        Envelope envelope,
        BigDecimal upcomingAmount,
        LocalDateTime now,
        boolean noSpendablePrimary
    ) {
        LocalDateTime nextDisbursementAt = envelope.getNextDisbursementAt();
        String countdown = formatCountdown(nextDisbursementAt);
        boolean soon = isCloseToDisbursement(nextDisbursementAt, now);
        double score = scoreUpcomingEnvelope(budget, envelope, upcomingAmount, now);

        String title;
        String message;
        String priority;
        String reason;

        if (noSpendablePrimary) {
            if (soon) {
                title = envelope.getName() + " releases " + countdown;
                message = "Nothing is spendable yet, but " + envelope.getName()
                    + " releases " + countdown + ". Hold steady; your naira is warming up.";
                priority = "high";
                reason = "No envelope has reached disbursement time yet; this is the closest upcoming release.";
                score = Math.max(score, 760);
            } else {
                title = "No envelope is ready yet";
                message = "No disbursement has reached yet. Next up: " + envelope.getName()
                    + " " + countdown + ". Your budget is doing disciplined things.";
                priority = "normal";
                reason = "No envelope has reached disbursement time yet; this is the next scheduled release.";
                score = Math.max(score, 560);
            }
        } else if (soon) {
            title = envelope.getName() + " releases soon";
            message = envelope.getName() + " unlocks " + countdown
                + ". Hold steady; your naira is almost ready for duty.";
            priority = "normal";
            reason = "This envelope is close to its next scheduled disbursement time.";
            score += 80;
        } else {
            title = envelope.getName() + " unlocks next";
            message = "Next release: " + envelope.getName() + " " + countdown
                + ". Your plan is quietly doing the heavy lifting.";
            priority = "normal";
            reason = "This is an upcoming envelope that will unlock usable money next.";
        }

        ActionCandidate candidate = baseCandidate(
            title,
            message,
            "Go to " + envelope.getName(),
            "review_active_budget",
            priority,
            reason,
            score
        );
        candidate.budgetId = budget.getId();
        candidate.budgetName = budget.getName();
        candidate.envelopeId = envelope.getId();
        candidate.envelopeName = envelope.getName();
        candidate.amountValue = upcomingAmount != null ? upcomingAmount.doubleValue() : null;
        candidate.nextAvailableAt = formatNextAvailableAt(nextDisbursementAt);
        candidate.countdownText = countdown;
        return candidate;
    }

    private AiDashboardNextActionResponse sanitizeResponse(
        AiDashboardNextActionResponse response,
        DashboardActionContext context,
        AiDashboardNextActionResponse deterministic
    ) {
        if (response == null || !isAllowedActionType(response.getActionType())) {
            logger.warn(
                "AiInsightService: Gemini dashboard action rejected due to unsupported actionType={}",
                response != null ? response.getActionType() : null
            );
            return deterministic != null ? deterministic : buildFallback(context);
        }

        ActionCandidate selectedCandidate = findMatchingCandidate(response, context.candidates);
        if (selectedCandidate == null) {
            logger.warn(
                "AiInsightService: Gemini dashboard action rejected because no candidate matched actionType={}, budgetId={}, envelopeId={}",
                response.getActionType(),
                response.getBudgetId(),
                response.getEnvelopeId()
            );
            return deterministic != null ? deterministic : buildFallback(context);
        }

        AiDashboardNextActionResponse sanitized = mergeWithCandidate(response, selectedCandidate, "ai");
        sanitized.setAlternatives(resolveAlternatives(response.getAlternatives(), context.candidates, selectedCandidate));
        return sanitized;
    }

    private ActionCandidate findMatchingCandidate(
        AiDashboardNextActionResponse response,
        List<ActionCandidate> candidates
    ) {
        for (ActionCandidate candidate : candidates) {
            if (!candidate.actionType.equals(response.getActionType())) {
                continue;
            }
            if (candidate.envelopeId != null && response.getEnvelopeId() != null
                && candidate.envelopeId.equals(response.getEnvelopeId())) {
                return candidate;
            }
            if (candidate.budgetId != null && response.getBudgetId() != null
                && candidate.budgetId.equals(response.getBudgetId())) {
                return candidate;
            }
        }

        for (ActionCandidate candidate : candidates) {
            if (candidate.actionType.equals(response.getActionType())) {
                return candidate;
            }
        }
        return null;
    }

    private AiDashboardNextActionResponse mergeWithCandidate(
        AiDashboardNextActionResponse response,
        ActionCandidate candidate,
        String source
    ) {
        AiDashboardNextActionResponse merged = new AiDashboardNextActionResponse();
        merged.setTitle(valueOrDefault(response.getTitle(), candidate.title));
        merged.setMessage(valueOrDefault(response.getMessage(), candidate.message));
        merged.setCtaLabel(valueOrDefault(response.getCtaLabel(), candidate.ctaLabel));
        merged.setActionType(candidate.actionType);
        merged.setPriority(isAllowedPriority(response.getPriority()) ? response.getPriority() : candidate.priority);
        merged.setReason(valueOrDefault(response.getReason(), candidate.reason));
        merged.setSource(source);
        merged.setConfidence(normalizeConfidence(response.getConfidence(), source.equals("ai") ? 0.82 : 0.93));
        merged.setBudgetId(candidate.budgetId);
        merged.setBudgetName(candidate.budgetName);
        merged.setEnvelopeId(candidate.envelopeId);
        merged.setEnvelopeName(candidate.envelopeName);
        merged.setAmountValue(candidate.amountValue);
        merged.setNextAvailableAt(candidate.nextAvailableAt);
        merged.setCountdownText(candidate.countdownText);
        // Preserve AI-generated variants; clear them for server-ranked/fallback paths
        // (the "ai" source check ensures only Gemini responses carry variants through)
        if ("ai".equals(source) && response.getVariants() != null && !response.getVariants().isEmpty()) {
            merged.setVariants(response.getVariants());
        }
        return merged;
    }

    private List<AiDashboardNextActionResponse.AlternativeAction> resolveAlternatives(
        List<AiDashboardNextActionResponse.AlternativeAction> requested,
        List<ActionCandidate> candidates,
        ActionCandidate selectedCandidate
    ) {
        List<AiDashboardNextActionResponse.AlternativeAction> alternatives = new ArrayList<>();
        Set<String> usedKeys = new LinkedHashSet<>();
        usedKeys.add(selectedCandidate.uniqueKey());

        if (requested != null) {
            for (AiDashboardNextActionResponse.AlternativeAction item : requested) {
                ActionCandidate matched = findMatchingCandidate(item, candidates);
                if (matched == null || usedKeys.contains(matched.uniqueKey())) {
                    continue;
                }
                alternatives.add(toAlternative(matched, valueOrDefault(item.getReason(), matched.reason)));
                usedKeys.add(matched.uniqueKey());
                if (alternatives.size() >= 2) {
                    return alternatives;
                }
            }
        }

        for (ActionCandidate candidate : candidates) {
            if (usedKeys.contains(candidate.uniqueKey())) {
                continue;
            }
            alternatives.add(toAlternative(candidate, candidate.reason));
            usedKeys.add(candidate.uniqueKey());
            if (alternatives.size() >= 2) {
                break;
            }
        }

        return alternatives;
    }

    private ActionCandidate findMatchingCandidate(
        AiDashboardNextActionResponse.AlternativeAction response,
        List<ActionCandidate> candidates
    ) {
        if (response == null || response.getActionType() == null) {
            return null;
        }

        for (ActionCandidate candidate : candidates) {
            if (!candidate.actionType.equals(response.getActionType())) {
                continue;
            }
            if (candidate.envelopeId != null && response.getEnvelopeId() != null
                && candidate.envelopeId.equals(response.getEnvelopeId())) {
                return candidate;
            }
            if (candidate.budgetId != null && response.getBudgetId() != null
                && candidate.budgetId.equals(response.getBudgetId())) {
                return candidate;
            }
        }

        for (ActionCandidate candidate : candidates) {
            if (candidate.actionType.equals(response.getActionType())) {
                return candidate;
            }
        }
        return null;
    }

    private AiDashboardNextActionResponse.AlternativeAction toAlternative(ActionCandidate candidate, String reason) {
        AiDashboardNextActionResponse.AlternativeAction alternative = new AiDashboardNextActionResponse.AlternativeAction();
        alternative.setTitle(candidate.title);
        alternative.setActionType(candidate.actionType);
        alternative.setCtaLabel(candidate.ctaLabel);
        alternative.setReason(reason);
        alternative.setBudgetId(candidate.budgetId);
        alternative.setBudgetName(candidate.budgetName);
        alternative.setEnvelopeId(candidate.envelopeId);
        alternative.setEnvelopeName(candidate.envelopeName);
        return alternative;
    }

    private AiDashboardNextActionResponse buildDeterministicRecommendation(DashboardActionContext context) {
        if (context.candidates.isEmpty()) {
            return null;
        }

        ActionCandidate selected = context.candidates.get(0);
        AiDashboardNextActionResponse response = mergeWithCandidate(new AiDashboardNextActionResponse(), selected, "server_ranked");

        List<AiDashboardNextActionResponse.AlternativeAction> alternatives = new ArrayList<>();
        for (int i = 1; i < context.candidates.size() && alternatives.size() < 2; i++) {
            ActionCandidate candidate = context.candidates.get(i);
            alternatives.add(toAlternative(candidate, candidate.reason));
        }
        response.setAlternatives(alternatives);
        return response;
    }

    private AiDashboardNextActionResponse buildFallback(DashboardActionContext context) {
        AiDashboardNextActionResponse response = new AiDashboardNextActionResponse();
        response.setTitle("New cycle, new plan — let's go 📅");
        response.setMessage("Set up a fresh budget so your money has direction this month — not just vibes.");
        response.setCtaLabel("Create budget");
        response.setActionType("create_budget");
        response.setPriority("high");
        response.setReason("No stronger live dashboard action was available.");
        response.setSource("fallback");
        response.setConfidence(0.61);
        return response;
    }

    private ActionCandidate baseCandidate(
        String title,
        String message,
        String ctaLabel,
        String actionType,
        String priority,
        String reason,
        double score
    ) {
        ActionCandidate candidate = new ActionCandidate();
        candidate.title = title;
        candidate.message = message;
        candidate.ctaLabel = ctaLabel;
        candidate.actionType = actionType;
        candidate.priority = priority;
        candidate.reason = reason;
        candidate.score = score;
        return candidate;
    }

    private List<Map<String, Object>> buildCandidatePayloads(List<ActionCandidate> candidates) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (ActionCandidate candidate : candidates) {
            Map<String, Object> item = new HashMap<>();
            item.put("title", candidate.title);
            item.put("message", candidate.message);
            item.put("ctaLabel", candidate.ctaLabel);
            item.put("actionType", candidate.actionType);
            item.put("priority", candidate.priority);
            item.put("reason", candidate.reason);
            item.put("score", candidate.score);
            item.put("budgetId", candidate.budgetId);
            item.put("budgetName", candidate.budgetName);
            item.put("envelopeId", candidate.envelopeId);
            item.put("envelopeName", candidate.envelopeName);
            item.put("amountValue", candidate.amountValue);
            item.put("nextAvailableAt", candidate.nextAvailableAt);
            item.put("countdownText", candidate.countdownText);
            payloads.add(item);
        }
        return payloads;
    }

    private boolean isEnvelopeSpendable(Envelope envelope) {
        return envelope != null
            && envelope.getRemainingAmount() != null
            && envelope.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0
            && !Boolean.TRUE.equals(envelope.getHasMatured());
    }

    private boolean hasHealthyRemaining(Envelope envelope) {
        BigDecimal available = resolveSpendableAmount(envelope);
        BigDecimal reference = resolveReferenceAmount(envelope);
        if (available == null || available.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (reference == null || reference.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        return available.divide(reference, 6, RoundingMode.HALF_UP).compareTo(new BigDecimal("0.01")) > 0;
    }

    private boolean hasFutureValue(Envelope envelope) {
        if (envelope == null) {
            return false;
        }

        BigDecimal totalRemaining = envelope.getTotalRemainingAmount();
        BigDecimal amount = envelope.getAmount();
        BigDecimal remaining = envelope.getRemainingAmount();

        return (totalRemaining != null && totalRemaining.compareTo(BigDecimal.ZERO) > 0)
            || (amount != null && amount.compareTo(BigDecimal.ZERO) > 0)
            || (remaining != null && remaining.compareTo(BigDecimal.ZERO) > 0);
    }

    private BigDecimal resolveSpendableAmount(Envelope envelope) {
        if (envelope == null) {
            return BigDecimal.ZERO;
        }
        if (envelope.getRemainingAmount() != null && envelope.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0) {
            return envelope.getRemainingAmount();
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal resolveReferenceAmount(Envelope envelope) {
        if (envelope == null) {
            return null;
        }

        if (envelope.getConditions() != null) {
            Object limit = envelope.getConditions().get("limit");
            BigDecimal parsedLimit = parseBigDecimal(limit);
            if (parsedLimit != null && parsedLimit.compareTo(BigDecimal.ZERO) > 0) {
                return parsedLimit;
            }
        }

        if (envelope.getAmount() != null && envelope.getAmount().compareTo(BigDecimal.ZERO) > 0) {
            return envelope.getAmount();
        }
        return envelope.getTotalRemainingAmount();
    }

    private BigDecimal resolveUpcomingAmount(Envelope envelope) {
        if (envelope == null) {
            return null;
        }

        if (envelope.getConditions() != null) {
            Object limit = envelope.getConditions().get("limit");
            BigDecimal parsedLimit = parseBigDecimal(limit);
            if (parsedLimit != null && parsedLimit.compareTo(BigDecimal.ZERO) > 0) {
                return parsedLimit;
            }
        }

        if (envelope.getRemainingAmount() != null && envelope.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0) {
            return envelope.getRemainingAmount();
        }

        return envelope.getAmount();
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value != null) {
            try {
                return new BigDecimal(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private double scoreSpendableEnvelope(
        Budget budget,
        Envelope envelope,
        LocalDateTime now,
        BigDecimal availableAmount
    ) {
        double score = 0;
        BigDecimal reference = resolveReferenceAmount(envelope);
        if (reference != null && reference.compareTo(BigDecimal.ZERO) > 0) {
            double ratio = availableAmount.divide(reference, 6, RoundingMode.HALF_UP).doubleValue();
            score += Math.min(ratio, 1.0) * 40;
        }
        score += availableAmount.doubleValue() * 0.00002;

        if (envelope.getLastDisbursedAt() != null) {
            long hoursAgo = Math.max(0, Duration.between(envelope.getLastDisbursedAt(), now).toHours());
            double recency = hoursAgo >= 168 ? 0 : 1 - (hoursAgo / 168.0);
            score += recency * 16;
        }

        if (budget.getCreatedAt() != null && Duration.between(budget.getCreatedAt(), now).toDays() <= 30) {
            score += 8;
        }

        return score;
    }

    private double scoreUpcomingEnvelope(
        Budget budget,
        Envelope envelope,
        BigDecimal upcomingAmount,
        LocalDateTime now
    ) {
        LocalDateTime next = envelope.getNextDisbursementAt();
        if (next == null) {
            return -999999;
        }

        long minutesAway = Math.max(0, Duration.between(now, next).toMinutes());
        double score = 500 - (minutesAway / 10.0);
        if (upcomingAmount != null) {
            score += upcomingAmount.doubleValue() * 0.00001;
        }
        if (budget.getCreatedAt() != null && Duration.between(budget.getCreatedAt(), now).toDays() <= 30) {
            score += 6;
        }
        return score;
    }

    private String formatNextAvailableAt(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "soon";
        }
        return dateTime.atZone(LAGOS_ZONE).format(NEXT_AVAILABLE_FORMATTER);
    }

    private String formatCountdown(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "soon";
        }

        Duration duration = Duration.between(LocalDateTime.now(LAGOS_ZONE), dateTime);
        if (duration.isNegative() || duration.isZero()) {
            return "right now";
        }

        long totalMinutes = duration.toMinutes();
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes % (60 * 24)) / 60;
        long minutes = totalMinutes % 60;

        StringBuilder builder = new StringBuilder("in ");
        if (days > 0) {
            builder.append(days).append("d ");
        }
        if (hours > 0) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0 || (days == 0 && hours == 0)) {
            builder.append(minutes).append("m");
        }

        return builder.toString().trim();
    }

    private String formatMoney(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String cleanJson(String rawText) {
        if (rawText == null) {
            return "{}";
        }

        String cleaned = rawText.trim();
        if (cleaned.startsWith("```") && cleaned.endsWith("```")) {
            cleaned = cleaned.replace("```json", "")
                .replace("```JSON", "")
                .replace("```", "")
                .trim();
        }
        return cleaned;
    }

    private boolean isAllowedActionType(String value) {
        if (value == null) {
            return false;
        }
        return switch (value) {
            case "create_budget", "review_active_budget", "fund_wallet", "set_account", "open_notifications" -> true;
            default -> false;
        };
    }

    private boolean isAllowedPriority(String value) {
        if (value == null) {
            return false;
        }
        return switch (value) {
            case "normal", "high", "urgent" -> true;
            default -> false;
        };
    }

    private boolean hasDisbursementReached(Envelope envelope, LocalDateTime now) {
        return envelope != null
            && envelope.getNextDisbursementAt() != null
            && !envelope.getNextDisbursementAt().isAfter(now);
    }

    private boolean wasRecentlyReleased(Envelope envelope, LocalDateTime now) {
        if (envelope == null || envelope.getLastDisbursedAt() == null) {
            return false;
        }

        if (envelope.getCreatedAt() != null) {
            long minutesAfterCreation = Math.abs(Duration.between(
                envelope.getCreatedAt(),
                envelope.getLastDisbursedAt()
            ).toMinutes());
            if (minutesAfterCreation < 2) {
                return false;
            }
        }

        Duration sinceRelease = Duration.between(envelope.getLastDisbursedAt(), now);
        return !sinceRelease.isNegative() && sinceRelease.compareTo(RECENT_RELEASE_WINDOW) <= 0;
    }

    private boolean isCloseToDisbursement(LocalDateTime nextDisbursementAt, LocalDateTime now) {
        if (nextDisbursementAt == null || !nextDisbursementAt.isAfter(now)) {
            return false;
        }

        Duration untilRelease = Duration.between(now, nextDisbursementAt);
        return untilRelease.compareTo(SOON_DISBURSEMENT_WINDOW) <= 0;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Double normalizeConfidence(Double value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value < 0) {
            return 0.0;
        }
        if (value > 1) {
            return 1.0;
        }
        return value;
    }

    private static class DashboardActionContext {
        private String userName;
        private BigDecimal walletBalance;
        private boolean hasLinkedSettlementAccount;
        private boolean hasBudgetHistory;
        private Budget activeBudget;
        private Budget completedBudget;
        private List<Budget> activeBudgets = List.of();
        private List<ActionCandidate> candidates = List.of();
        /** -1 = no active budget; 0 = ends today; N = ends in N days */
        private int daysUntilActiveBudgetEnds = -1;
    }

    private static class ActionCandidate {
        private String title;
        private String message;
        private String ctaLabel;
        private String actionType;
        private String priority;
        private String reason;
        private Long budgetId;
        private String budgetName;
        private Long envelopeId;
        private String envelopeName;
        private Double amountValue;
        private String nextAvailableAt;
        private String countdownText;
        private double score;

        private String uniqueKey() {
            return actionType + "::" + (budgetId == null ? 0 : budgetId)
                + "::" + (envelopeId == null ? 0 : envelopeId)
                + "::" + (budgetName == null ? "" : budgetName)
                + "::" + (envelopeName == null ? "" : envelopeName);
        }

        private double score() {
            return score;
        }
    }
}

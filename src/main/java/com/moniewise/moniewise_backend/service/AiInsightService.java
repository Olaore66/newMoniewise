package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.response.AiDashboardNextActionResponse;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class AiInsightService {

    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter NEXT_AVAILABLE_FORMATTER =
        DateTimeFormatter.ofPattern("EEE, d MMM - h:mm a");

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
        DashboardActionContext context = buildContext(email);

        AiDashboardNextActionResponse deterministic = buildDeterministicRecommendation(context);
        if (deterministic != null) {
            return deterministic;
        }

        try {
            String prompt = aiPromptService.buildDashboardNextActionPrompt(
                context.userName,
                context.walletBalance.doubleValue(),
                context.activeBudget != null,
                context.completedBudget != null,
                context.hasLinkedSettlementAccount,
                context.activeBudget != null ? context.activeBudget.getName() : "none",
                context.activeBudget != null ? context.activeBudget.getId() : 0L
            );

            String rawText = geminiService.generateText(prompt);
            String cleanedText = cleanJson(rawText);
            AiDashboardNextActionResponse response =
                objectMapper.readValue(cleanedText, AiDashboardNextActionResponse.class);

            return sanitizeResponse(response, context);
        } catch (Exception e) {
            return buildFallback(context);
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
        context.spendableEnvelope = findSpendableEnvelope(activeBudgets);
        context.upcomingEnvelope = findUpcomingEnvelope(activeBudgets);
        return context;
    }

    private EnvelopeSnapshot findSpendableEnvelope(List<Budget> activeBudgets) {
        return activeBudgets.stream()
            .flatMap(budget -> budget.getEnvelopes().stream()
                .map(envelope -> new EnvelopeSnapshot(budget, envelope)))
            .filter(snapshot -> isEnvelopeSpendable(snapshot.envelope()))
            .sorted(
                Comparator
                    .comparing((EnvelopeSnapshot snapshot) -> snapshot.envelope().getLastDisbursedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(snapshot -> snapshot.envelope().getNextDisbursementAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(snapshot -> snapshot.budget().getCreatedAt(), Comparator.reverseOrder())
            )
            .findFirst()
            .orElse(null);
    }

    private EnvelopeSnapshot findUpcomingEnvelope(List<Budget> activeBudgets) {
        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        return activeBudgets.stream()
            .flatMap(budget -> budget.getEnvelopes().stream()
                .map(envelope -> new EnvelopeSnapshot(budget, envelope)))
            .filter(snapshot -> snapshot.envelope().getNextDisbursementAt() != null)
            .filter(snapshot -> snapshot.envelope().getNextDisbursementAt().isAfter(now))
            .filter(snapshot -> hasFutureValue(snapshot.envelope()))
            .sorted(
                Comparator
                    .comparing((EnvelopeSnapshot snapshot) -> snapshot.envelope().getNextDisbursementAt())
                    .thenComparing(snapshot -> snapshot.budget().getCreatedAt(), Comparator.reverseOrder())
            )
            .findFirst()
            .orElse(null);
    }

    private boolean isEnvelopeSpendable(Envelope envelope) {
        return envelope != null
            && envelope.getRemainingAmount() != null
            && envelope.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0
            && !Boolean.TRUE.equals(envelope.getHasMatured());
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

    private AiDashboardNextActionResponse sanitizeResponse(
        AiDashboardNextActionResponse response,
        DashboardActionContext context
    ) {
        if (response == null || !isAllowedActionType(response.getActionType())) {
            return buildFallback(context);
        }

        AiDashboardNextActionResponse deterministic = buildDeterministicRecommendation(context);
        if (deterministic != null) {
            return deterministic;
        }

        if (response.getTitle() == null || response.getTitle().isBlank()) {
            response.setTitle(defaultTitleFor(response.getActionType(), context));
        }

        if (response.getMessage() == null || response.getMessage().isBlank()) {
            response.setMessage(defaultMessageFor(response.getActionType(), context));
        }

        if (response.getCtaLabel() == null || response.getCtaLabel().isBlank()) {
            response.setCtaLabel(defaultCtaFor(response.getActionType()));
        }

        if (!isAllowedPriority(response.getPriority())) {
            response.setPriority(defaultPriorityFor(response.getActionType()));
        }

        if ("review_active_budget".equals(response.getActionType()) && context.activeBudget != null) {
            response.setBudgetId(context.activeBudget.getId());
            response.setBudgetName(context.activeBudget.getName());
        } else {
            response.setBudgetId(null);
            response.setBudgetName(null);
            response.setEnvelopeId(null);
            response.setEnvelopeName(null);
            response.setAmountValue(null);
            response.setNextAvailableAt(null);
            response.setCountdownText(null);
        }

        return response;
    }

    private AiDashboardNextActionResponse buildDeterministicRecommendation(DashboardActionContext context) {
        if (context.hasBudgetHistory && context.walletBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return baseAction(
                "Fund your wallet first",
                "Your wallet balance is zero right now. Add money so your active budget and upcoming spending plan can keep moving.",
                "Fund wallet",
                "fund_wallet",
                "high"
            );
        }

        if (context.spendableEnvelope != null) {
            EnvelopeSnapshot snapshot = context.spendableEnvelope;
            BigDecimal availableAmount = snapshot.envelope().getRemainingAmount();
            AiDashboardNextActionResponse response = baseAction(
                "Spend from " + snapshot.envelope().getName(),
                "Budget " + snapshot.budget().getName()
                    + " currently has unlocked money in envelope "
                    + snapshot.envelope().getName()
                    + ".",
                "Go to " + snapshot.envelope().getName(),
                "review_active_budget",
                "high"
            );
            response.setBudgetId(snapshot.budget().getId());
            response.setBudgetName(snapshot.budget().getName());
            response.setEnvelopeId(snapshot.envelope().getId());
            response.setEnvelopeName(snapshot.envelope().getName());
            response.setAmountValue(availableAmount != null ? availableAmount.doubleValue() : null);
            return response;
        }

        if (context.upcomingEnvelope != null) {
            EnvelopeSnapshot snapshot = context.upcomingEnvelope;
            LocalDateTime nextDisbursementAt = snapshot.envelope().getNextDisbursementAt();
            String formattedTime = formatNextAvailableAt(nextDisbursementAt);
            String countdownText = formatCountdown(nextDisbursementAt);
            BigDecimal upcomingAmount = resolveUpcomingAmount(snapshot.envelope());

            AiDashboardNextActionResponse response = baseAction(
                "No spendable amount yet",
                "Sorry, you don't have any spendable amount now.",
                "Go to " + snapshot.envelope().getName(),
                "review_active_budget",
                "high"
            );
            response.setBudgetId(snapshot.budget().getId());
            response.setBudgetName(snapshot.budget().getName());
            response.setEnvelopeId(snapshot.envelope().getId());
            response.setEnvelopeName(snapshot.envelope().getName());
            response.setAmountValue(upcomingAmount != null ? upcomingAmount.doubleValue() : null);
            response.setNextAvailableAt(formattedTime);
            response.setCountdownText(countdownText);
            return response;
        }

        return null;
    }

    private BigDecimal resolveUpcomingAmount(Envelope envelope) {
        if (envelope == null) {
            return null;
        }

        if (envelope.getConditions() != null) {
            Object limit = envelope.getConditions().get("limit");
            if (limit instanceof Number number) {
                return BigDecimal.valueOf(number.doubleValue());
            }
            if (limit != null) {
                try {
                    return new BigDecimal(limit.toString());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (envelope.getRemainingAmount() != null && envelope.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0) {
            return envelope.getRemainingAmount();
        }

        return envelope.getAmount();
    }

    private AiDashboardNextActionResponse buildFallback(DashboardActionContext context) {
        AiDashboardNextActionResponse deterministic = buildDeterministicRecommendation(context);
        if (deterministic != null) {
            return deterministic;
        }

        AiDashboardNextActionResponse response = new AiDashboardNextActionResponse();

        if (context.activeBudget == null && context.completedBudget == null) {
            response.setTitle("Create your first budget");
            response.setMessage("Turn your wallet balance into a clear spending plan with envelopes that match your goals.");
            response.setCtaLabel("Start budget");
            response.setActionType("create_budget");
            response.setPriority("high");
            return response;
        }

        if (context.activeBudget != null) {
            response.setTitle("Review " + context.activeBudget.getName());
            response.setMessage("Check your most recent active budget and make sure each envelope still reflects today's priorities.");
            response.setCtaLabel("Review budget");
            response.setActionType("review_active_budget");
            response.setPriority("high");
            response.setBudgetId(context.activeBudget.getId());
            response.setBudgetName(context.activeBudget.getName());
            return response;
        }

        if (!context.hasLinkedSettlementAccount && context.walletBalance.compareTo(new BigDecimal("50000")) <= 0) {
            response.setTitle("Link your payout account");
            response.setMessage("Set your account details now so withdrawals stay fast and friction-free when you need them.");
            response.setCtaLabel("Set account");
            response.setActionType("set_account");
            response.setPriority("normal");
            return response;
        }

        response.setTitle("Plan the next budget cycle");
        response.setMessage("You've completed a budget before. Start the next one while your recent spending pattern is still fresh.");
        response.setCtaLabel("Create budget");
        response.setActionType("create_budget");
        response.setPriority("normal");
        return response;
    }

    private AiDashboardNextActionResponse baseAction(
        String title,
        String message,
        String ctaLabel,
        String actionType,
        String priority
    ) {
        AiDashboardNextActionResponse response = new AiDashboardNextActionResponse();
        response.setTitle(title);
        response.setMessage(message);
        response.setCtaLabel(ctaLabel);
        response.setActionType(actionType);
        response.setPriority(priority);
        return response;
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

    private String defaultTitleFor(String actionType, DashboardActionContext context) {
        return switch (actionType) {
            case "fund_wallet" -> "Fund your wallet first";
            case "set_account" -> "Link your payout account";
            case "review_active_budget" -> context.activeBudget != null
                ? "Review " + context.activeBudget.getName()
                : "Review your active budget";
            case "open_notifications" -> "Catch up on alerts";
            default -> "Create your next budget";
        };
    }

    private String defaultMessageFor(String actionType, DashboardActionContext context) {
        return switch (actionType) {
            case "fund_wallet" -> "Add money so your next budget or transfer can move forward without delay.";
            case "set_account" -> "Add your payout details once so withdrawals are ready whenever you need them.";
            case "review_active_budget" -> "Open your active budget and keep each envelope aligned with your current plan.";
            case "open_notifications" -> "Check recent updates so you don't miss credits, disbursements, or important account events.";
            default -> "Build a fresh budget and give every naira a clear job.";
        };
    }

    private String defaultCtaFor(String actionType) {
        return switch (actionType) {
            case "fund_wallet" -> "Fund wallet";
            case "set_account" -> "Set account";
            case "review_active_budget" -> "Review budget";
            case "open_notifications" -> "View alerts";
            default -> "Create budget";
        };
    }

    private String defaultPriorityFor(String actionType) {
        return switch (actionType) {
            case "fund_wallet", "create_budget", "review_active_budget" -> "high";
            case "open_notifications" -> "urgent";
            default -> "normal";
        };
    }

    private static class DashboardActionContext {
        private String userName;
        private BigDecimal walletBalance;
        private boolean hasLinkedSettlementAccount;
        private boolean hasBudgetHistory;
        private Budget activeBudget;
        private Budget completedBudget;
        private EnvelopeSnapshot spendableEnvelope;
        private EnvelopeSnapshot upcomingEnvelope;
    }

    private record EnvelopeSnapshot(Budget budget, Envelope envelope) {}
}

package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.response.AiDashboardNextActionResponse;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class AiInsightService {

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
        return context;
    }

    private AiDashboardNextActionResponse sanitizeResponse(
        AiDashboardNextActionResponse response,
        DashboardActionContext context
    ) {
        if (response == null || !isAllowedActionType(response.getActionType())) {
            return buildFallback(context);
        }

        if ("set_account".equals(response.getActionType())
            && context.activeBudget != null
            && context.walletBalance.compareTo(BigDecimal.ZERO) > 0) {
            return buildFallback(context);
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
        }

        return response;
    }

    private AiDashboardNextActionResponse buildFallback(DashboardActionContext context) {
        AiDashboardNextActionResponse response = new AiDashboardNextActionResponse();

        if (context.activeBudget == null && context.completedBudget == null) {
            response.setTitle("Create your first budget");
            response.setMessage("Turn your wallet balance into a clear spending plan with envelopes that match your goals.");
            response.setCtaLabel("Start budget");
            response.setActionType("create_budget");
            response.setPriority("high");
            return response;
        }

        if (context.walletBalance.compareTo(BigDecimal.ZERO) <= 0) {
            response.setTitle("Fund your wallet first");
            response.setMessage("Add money so your next budget or transfer can move forward without interruption.");
            response.setCtaLabel("Fund wallet");
            response.setActionType("fund_wallet");
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
        private Budget activeBudget;
        private Budget completedBudget;
    }
}
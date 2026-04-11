package com.moniewise.moniewise_backend.service;

import org.springframework.stereotype.Service;

import com.moniewise.moniewise_backend.dto.request.AiStarterEnvelopeRequest;

@Service
public class AiPromptService {

    public String buildStarterEnvelopePrompt(AiStarterEnvelopeRequest request) {
        String goal = request.getGoal() == null ? "" : request.getGoal().trim();

        return """
            You are a budgeting assistant for a fintech app called Wisemonie.

            Return valid JSON only.
            Do not include markdown.
            Do not include commentary outside JSON.

            Rules:
            - Suggest between 3 and 6 envelopes
            - Total percentage must not exceed 100
            - Each percentage must be greater than 0
            - Use only these categories:
              savings, security, food, car, home, education, flight, tools, gift, work, internet, faith, groceries, lunch, more
            - Use only these condition types:
              daily, weekly, dynamic, emergency
            - Recommendations must be realistic for a Nigerian budgeting context
            - Keep envelope names short and user-friendly

            Input:
            - totalBudget: %s
            - durationDays: %s
            - goal: %s
            - currency: %s

            Return this exact JSON shape:
            {
              "title": "string",
              "reasoning": "string",
              "envelopes": [
                {
                  "name": "string",
                  "percentage": 0,
                  "conditionType": "daily|weekly|dynamic|emergency",
                  "category": "savings|security|food|car|home|education|flight|tools|gift|work|internet|faith|groceries|lunch|more"
                }
              ]
            }
            """.formatted(
                request.getTotalBudget(),
                request.getDurationDays(),
                goal,
                request.getCurrency()
        );
    }

    public String buildBudgetAllocationPrompt(AiStarterEnvelopeRequest request) {
        String goal = request.getGoal() == null ? "" : request.getGoal().trim();

        return """
            You are a financial planning assistant for a fintech app called Wisemonie.

            Return valid JSON only.
            Do not include markdown.
            Do not include commentary outside JSON.

            Task:
            Suggest a clean budget allocation plan for a Nigerian user.

            Rules:
            - Suggest between 3 and 6 envelopes
            - Total percentage must be between 70 and 100
            - Each percentage must be greater than 0
            - Use only these categories:
              savings, security, food, car, home, education, flight, tools, gift, work, internet, faith, groceries, lunch, more
            - Use only these condition types:
              daily, weekly, dynamic, emergency
            - Recommendations must feel realistic for the stated goal, amount, and duration
            - Prioritize essentials before lifestyle envelopes
            - Keep envelope names short and user-friendly
            - reasoning should explain the allocation logic in 1 or 2 short sentences

            Input:
            - totalBudget: %s
            - durationDays: %s
            - goal: %s
            - currency: %s

            Return this exact JSON shape:
            {
              "title": "string",
              "reasoning": "string",
              "totalAllocatedPercentage": 0,
              "envelopes": [
                {
                  "name": "string",
                  "percentage": 0,
                  "conditionType": "daily|weekly|dynamic|emergency",
                  "category": "savings|security|food|car|home|education|flight|tools|gift|work|internet|faith|groceries|lunch|more"
                }
              ]
            }
            """.formatted(
                request.getTotalBudget(),
                request.getDurationDays(),
                goal,
                request.getCurrency()
        );
    }

    public String buildDashboardNextActionPrompt(
        String userName,
        double walletBalance,
        boolean hasActiveBudget,
        boolean hasCompletedBudget,
        boolean hasLinkedSettlementAccount,
        String activeBudgetName,
        long activeBudgetId
    ) {
        return """
            You are a product strategist for Wisemonie, a modern Nigerian fintech budgeting app.

            Return valid JSON only.
            Do not include markdown.
            Do not include commentary outside JSON.

            Goal:
            Pick the single best next action to show on the dashboard right now.
            The recommendation should feel practical, financially responsible, and product-native.

            Allowed action types:
            - create_budget
            - review_active_budget
            - fund_wallet
            - set_account
            - open_notifications

            Rules:
            - Prefer action types that help the user make progress immediately.
            - If there is an active budget, reviewing it is usually strong.
            - If there are no budgets at all, creating a budget is usually strong.
            - If wallet balance is zero or less, funding the wallet can be strong.
            - If settlement account is not linked, setting account can be strong.
            - Keep title under 55 characters.
            - Keep message under 140 characters.
            - CTA label should be 2 to 4 words.
            - Priority must be one of: normal, high, urgent.
            - Only include budgetId and budgetName when actionType is review_active_budget.

            User snapshot:
            - userName: %s
            - walletBalance: %.2f
            - hasActiveBudget: %s
            - hasCompletedBudget: %s
            - hasLinkedSettlementAccount: %s
            - activeBudgetName: %s
            - activeBudgetId: %s

            Return this exact JSON shape:
            {
              "title": "string",
              "message": "string",
              "ctaLabel": "string",
              "actionType": "create_budget|review_active_budget|fund_wallet|set_account|open_notifications",
              "priority": "normal|high|urgent",
              "budgetId": 0,
              "budgetName": "string"
            }
            """.formatted(
                userName,
                walletBalance,
                hasActiveBudget,
                hasCompletedBudget,
                hasLinkedSettlementAccount,
                activeBudgetName,
                activeBudgetId
        );
    }
}
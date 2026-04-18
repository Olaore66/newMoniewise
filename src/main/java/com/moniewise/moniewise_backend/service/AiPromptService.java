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

            Objective:
            Create a practical starter envelope plan that feels personalized to the user's actual goal, amount, duration, and context.

            Rules:
            - Suggest between 4 and 7 envelopes
            - Total percentage must not exceed 100
            - Each percentage must be greater than 0
            - Recommendations must be realistic for a Nigerian budgeting context
            - Keep envelope names short, user-friendly, and specific to the user's goal
            - Avoid repeating the same generic set of envelopes unless the goal truly calls for them
            - Use more specific names where appropriate, for example Rent, School Runs, Groceries, Data, Tithe, Emergency Buffer, Client Transport
            - If the user's goal includes an existing draft, rebalance it thoughtfully instead of ignoring it
            - Only use condition types from: daily, weekly, dynamic, emergency
            - Only use categories from: savings, security, food, car, home, education, flight, tools, gift, work, internet, faith, groceries, lunch, more
            - reasoning should explain why this mix fits the user's budget in 1 or 2 short sentences
            - title should sound like a fresh personalized draft, not a generic template label

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
            - Suggest between 4 and 7 envelopes
            - Total percentage must be between 75 and 100
            - Each percentage must be greater than 0
            - Recommendations must feel realistic for the stated goal, amount, and duration
            - Prioritize essentials before lifestyle envelopes when the goal suggests discipline or stability
            - Increase savings or emergency cover when the goal suggests caution, buffering, or discipline
            - Use more specific envelope names when the goal clearly points to them
            - Avoid returning the same generic mix unless it is genuinely the best fit
            - Keep envelope names short and user-friendly
            - reasoning should explain the allocation logic in 1 or 2 short sentences
            - title should feel like a personalized plan name, not a boilerplate label
            - Only use condition types from: daily, weekly, dynamic, emergency
            - Only use categories from: savings, security, food, car, home, education, flight, tools, gift, work, internet, faith, groceries, lunch, more

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

    public String buildBudgetAssistantTurnPrompt(
        String budgetName,
        Double totalBudget,
        Integer durationDays,
        String goal,
        String currency,
        String latestUserMessage,
        String currentEnvelopesJson,
        String conversationJson,
        double allocatedPercentage,
        double remainingAmount
    ) {
        return """
            You are Wisemonie's personal budget planning assistant.

            Return valid JSON only.
            Do not include markdown.
            Do not include commentary outside JSON.

            Your job:
            - Talk like a smart budgeting assistant, not a template generator.
            - Help the user name the envelopes they actually want.
            - Respect user-specified envelope names when they are practical.
            - Recalculate the plan after every turn based on what is already allocated and what remains.
            - Be realistic and collaborative in a Nigerian budgeting context.
            - If the user changes one envelope, preserve the others unless there is a good reason to rebalance them.

            Hard rules:
            - Return the FULL current envelope plan after this turn, not only the changed items.
            - Keep envelope names short, natural, and specific.
            - The final data must preserve only: name, percentage, conditionType, category.
            - Only use condition types from: daily, weekly, dynamic, emergency
            - Only use categories from: savings, security, food, car, home, education, flight, tools, gift, work, internet, faith, groceries, lunch, more
            - Total percentage must not exceed 100
            - If there is still meaningful money left to plan, do not mark readyToFinalize as true.
            - If the user clearly agrees the plan is done and the allocation is effectively complete, set readyToFinalize to true.
            - assistantMessage should feel conversational and mention what remains when useful.
            - reasoning should briefly explain the planning logic in 1 or 2 short sentences.
            - source must be "gemini"

            Budget context:
            - budgetName: %s
            - totalBudget: %s
            - durationDays: %s
            - goal: %s
            - currency: %s
            - currentlyAllocatedPercentage: %.1f
            - remainingAmount: %.2f

            Latest user message:
            %s

            Current envelope plan JSON:
            %s

            Recent conversation JSON:
            %s

            Return this exact JSON shape:
            {
              "assistantMessage": "string",
              "reasoning": "string",
              "source": "gemini",
              "readyToFinalize": false,
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
            budgetName,
            totalBudget,
            durationDays,
            goal,
            currency,
            allocatedPercentage,
            remainingAmount,
            latestUserMessage,
            currentEnvelopesJson,
            conversationJson
        );
    }

    public String buildDashboardNextActionPrompt(
        String userName,
        double walletBalance,
        boolean hasActiveBudget,
        boolean hasCompletedBudget,
        boolean hasLinkedSettlementAccount,
        String candidatesJson
    ) {
        return """
            You are a product strategist for Wisemonie, a modern Nigerian fintech budgeting app.

            Return valid JSON only.
            Do not include markdown.
            Do not include commentary outside JSON.

            Goal:
            Pick the single best next action to show on the dashboard right now from the server-ranked candidates provided below.
            The recommendation should feel practical, financially responsible, product-native, and immediately useful.

            Allowed action types:
            - create_budget
            - review_active_budget
            - fund_wallet
            - set_account
            - open_notifications

            Hard rules:
            - You must choose the primary action from the provided candidates.
            - You may lightly improve the wording of title, message, and CTA label, but do not invent a new unsupported route or identifier.
            - Keep title under 55 characters.
            - Keep message under 160 characters.
            - CTA label should be 2 to 4 words.
            - Priority must be one of: normal, high, urgent.
            - Confidence must be a decimal between 0 and 1.
            - reason should explain in one short sentence why this is the strongest next move.
            - Include up to 2 alternatives chosen only from the provided candidates.
            - Keep budgetId, budgetName, envelopeId, and envelopeName aligned with the chosen candidate when applicable.
            - If a candidate says no envelope is spendable right now, preserve its timing fields.

            User snapshot:
            - userName: %s
            - walletBalance: %.2f
            - hasActiveBudget: %s
            - hasCompletedBudget: %s
            - hasLinkedSettlementAccount: %s

            Ranked candidates JSON:
            %s

            Return this exact JSON shape:
            {
              "title": "string",
              "message": "string",
              "ctaLabel": "string",
              "actionType": "create_budget|review_active_budget|fund_wallet|set_account|open_notifications",
              "priority": "normal|high|urgent",
              "reason": "string",
              "source": "ai",
              "confidence": 0.0,
              "budgetId": 0,
              "budgetName": "string",
              "envelopeId": 0,
              "envelopeName": "string",
              "amountValue": 0.0,
              "nextAvailableAt": "string",
              "countdownText": "string",
              "alternatives": [
                {
                  "title": "string",
                  "actionType": "create_budget|review_active_budget|fund_wallet|set_account|open_notifications",
                  "ctaLabel": "string",
                  "reason": "string",
                  "budgetId": 0,
                  "budgetName": "string",
                  "envelopeId": 0,
                  "envelopeName": "string"
                }
              ]
            }
            """.formatted(
                userName,
                walletBalance,
                hasActiveBudget,
                hasCompletedBudget,
                hasLinkedSettlementAccount,
                candidatesJson
        );
    }
}

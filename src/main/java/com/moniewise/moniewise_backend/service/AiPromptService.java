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
}


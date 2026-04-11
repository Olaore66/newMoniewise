package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.request.AiStarterEnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.AiEnvelopeSuggestion;
import com.moniewise.moniewise_backend.dto.response.AiStarterEnvelopeResponse;

import org.springframework.stereotype.Service;

@Service
public class AiBudgetService {

    private final GeminiService geminiService;
    private final AiPromptService aiPromptService;
    private final ObjectMapper objectMapper;

    public AiBudgetService(
        GeminiService geminiService,
        AiPromptService aiPromptService,
        ObjectMapper objectMapper
    ) {
        this.geminiService = geminiService;
        this.aiPromptService = aiPromptService;
        this.objectMapper = objectMapper;
    }

    public AiStarterEnvelopeResponse generateStarterEnvelopes(AiStarterEnvelopeRequest request) {
        validateRequest(request);

        try {
            String prompt = aiPromptService.buildStarterEnvelopePrompt(request);
            String rawText = geminiService.generateText(prompt);

            AiStarterEnvelopeResponse response =
                objectMapper.readValue(rawText, AiStarterEnvelopeResponse.class);

            validateAiResponse(response);

            return response;
        } catch (Exception e) {
            return buildFallbackStarterPlan();
        }
    }

    private void validateRequest(AiStarterEnvelopeRequest request) {
        if (request.getTotalBudget() == null || request.getTotalBudget() <= 0) {
            throw new IllegalArgumentException("totalBudget must be greater than 0");
        }

        if (request.getDurationDays() == null || request.getDurationDays() <= 0) {
            throw new IllegalArgumentException("durationDays must be greater than 0");
        }

        if (request.getCurrency() == null || request.getCurrency().isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
    }

    private void validateAiResponse(AiStarterEnvelopeResponse response) {
        if (response == null || response.getEnvelopes() == null || response.getEnvelopes().isEmpty()) {
            throw new IllegalArgumentException("Invalid AI response");
        }

        double total = 0.0;

        for (AiEnvelopeSuggestion item : response.getEnvelopes()) {
            if (item.getName() == null || item.getName().isBlank()) {
                throw new IllegalArgumentException("Envelope name is missing");
            }

            if (item.getPercentage() == null || item.getPercentage() <= 0) {
                throw new IllegalArgumentException("Invalid percentage");
            }

            if (!isAllowedConditionType(item.getConditionType())) {
                throw new IllegalArgumentException("Invalid condition type");
            }

            if (!isAllowedCategory(item.getCategory())) {
                throw new IllegalArgumentException("Invalid category");
            }

            total += item.getPercentage();
        }

        if (total > 100.0) {
            throw new IllegalArgumentException("Total percentage exceeds 100");
        }
    }

    private boolean isAllowedConditionType(String value) {
        return value != null && switch (value) {
            case "daily", "weekly", "dynamic", "emergency" -> true;
            default -> false;
        };
    }

    private boolean isAllowedCategory(String value) {
        return value != null && switch (value) {
            case "savings", "security", "food", "car", "home", "education",
                 "flight", "tools", "gift", "work", "internet", "faith",
                 "groceries", "lunch", "more" -> true;
            default -> false;
        };
    }

    private AiStarterEnvelopeResponse buildFallbackStarterPlan() {
        AiStarterEnvelopeResponse response = new AiStarterEnvelopeResponse();
        response.setTitle("Starter budget plan");
        response.setReasoning("A balanced starter plan was prepared for you.");

        response.setEnvelopes(java.util.List.of(
            buildItem("Food", 30.0, "weekly", "food"),
            buildItem("Transport", 15.0, "daily", "car"),
            buildItem("Savings", 20.0, "dynamic", "savings"),
            buildItem("Bills", 20.0, "weekly", "home"),
            buildItem("Misc", 15.0, "daily", "more")
        ));

        return response;
    }

    private AiEnvelopeSuggestion buildItem(String name, Double pct, String type, String category) {
        AiEnvelopeSuggestion item = new AiEnvelopeSuggestion();
        item.setName(name);
        item.setPercentage(pct);
        item.setConditionType(type);
        item.setCategory(category);
        return item;
    }
}


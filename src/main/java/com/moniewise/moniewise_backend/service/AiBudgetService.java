package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.request.AiBudgetAssistantMessage;
import com.moniewise.moniewise_backend.dto.request.AiBudgetAssistantTurnRequest;
import com.moniewise.moniewise_backend.dto.request.AiStarterEnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.AiBudgetAssistantTurnResponse;
import com.moniewise.moniewise_backend.dto.response.AiBudgetAllocationResponse;
import com.moniewise.moniewise_backend.dto.response.AiEnvelopeSuggestion;
import com.moniewise.moniewise_backend.dto.response.AiStarterEnvelopeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiBudgetService {

    private static final Logger logger = LoggerFactory.getLogger(AiBudgetService.class);

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
            String cleanedText = cleanJson(rawText);

            AiStarterEnvelopeResponse response =
                objectMapper.readValue(cleanedText, AiStarterEnvelopeResponse.class);

            normalizeStarterResponse(response, request);
            validateStarterResponse(response);
            return response;
        } catch (Exception e) {
            logger.warn("Falling back to heuristic starter plan for goal='{}': {}", request.getGoal(), e.getMessage());
            return buildFallbackStarterPlan(request);
        }
    }

    public AiBudgetAllocationResponse generateBudgetAllocation(AiStarterEnvelopeRequest request) {
        validateRequest(request);

        try {
            String prompt = aiPromptService.buildBudgetAllocationPrompt(request);
            String rawText = geminiService.generateText(prompt);
            String cleanedText = cleanJson(rawText);

            AiBudgetAllocationResponse response =
                objectMapper.readValue(cleanedText, AiBudgetAllocationResponse.class);

            normalizeAllocationResponse(response, request);
            validateAllocationResponse(response);
            return response;
        } catch (Exception e) {
            logger.warn("Falling back to heuristic allocation plan for goal='{}': {}", request.getGoal(), e.getMessage());
            return buildFallbackAllocationPlan(request);
        }
    }

    public AiBudgetAssistantTurnResponse processBudgetAssistantTurn(
        AiBudgetAssistantTurnRequest request
    ) {
        validateAssistantTurnRequest(request);

        try {
            List<AiEnvelopeSuggestion> currentItems = request.getEnvelopes() == null
                ? Collections.emptyList()
                : request.getEnvelopes();
            List<AiEnvelopeSuggestion> normalizedCurrent = normalizeItems(currentItems, 100.0);
            double allocatedPercentage = sumPercentages(normalizedCurrent);
            double remainingAmount = computeRemainingAmount(request.getTotalBudget(), allocatedPercentage);

            String currentEnvelopesJson = objectMapper.writeValueAsString(normalizedCurrent);
            String conversationJson = objectMapper.writeValueAsString(trimConversation(request.getMessages()));
            String prompt = aiPromptService.buildBudgetAssistantTurnPrompt(
                safeBudgetName(request.getBudgetName()),
                request.getTotalBudget(),
                request.getDurationDays(),
                safeGoal(request.getGoal()),
                safeCurrency(request.getCurrency()),
                request.getLatestUserMessage().trim(),
                currentEnvelopesJson,
                conversationJson,
                allocatedPercentage,
                remainingAmount
            );

            String rawText = geminiService.generateText(prompt);
            String cleanedText = cleanJson(rawText);

            AiBudgetAssistantTurnResponse response =
                objectMapper.readValue(cleanedText, AiBudgetAssistantTurnResponse.class);

            normalizeAssistantResponse(response, request);
            validateAssistantResponse(response);
            response.setSource("gemini");
            return response;
        } catch (Exception e) {
            logger.warn(
                "Falling back to conversational assistant plan for latestUserMessage='{}': {}",
                request.getLatestUserMessage(),
                e.getMessage()
            );
            return buildFallbackAssistantResponse(request);
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

    private void validateAssistantTurnRequest(AiBudgetAssistantTurnRequest request) {
        if (request.getTotalBudget() == null || request.getTotalBudget() <= 0) {
            throw new IllegalArgumentException("totalBudget must be greater than 0");
        }

        if (request.getDurationDays() == null || request.getDurationDays() <= 0) {
            throw new IllegalArgumentException("durationDays must be greater than 0");
        }

        if (request.getCurrency() == null || request.getCurrency().isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }

        if (request.getLatestUserMessage() == null || request.getLatestUserMessage().isBlank()) {
            throw new IllegalArgumentException("latestUserMessage is required");
        }
    }

    private void validateStarterResponse(AiStarterEnvelopeResponse response) {
        if (response == null || response.getEnvelopes() == null || response.getEnvelopes().isEmpty()) {
            throw new IllegalArgumentException("Invalid AI response");
        }

        double total = validateSuggestionItems(response.getEnvelopes());
        if (total > 100.0) {
            throw new IllegalArgumentException("Total percentage exceeds 100");
        }
    }

    private void validateAllocationResponse(AiBudgetAllocationResponse response) {
        if (response == null || response.getEnvelopes() == null || response.getEnvelopes().isEmpty()) {
            throw new IllegalArgumentException("Invalid AI response");
        }

        double total = validateSuggestionItems(response.getEnvelopes());
        if (total < 70.0 || total > 100.0) {
            throw new IllegalArgumentException("Total allocation percentage must be between 70 and 100");
        }

        response.setTotalAllocatedPercentage(total);
    }

    private void validateAssistantResponse(AiBudgetAssistantTurnResponse response) {
        if (response == null || response.getEnvelopes() == null) {
            throw new IllegalArgumentException("Invalid assistant response");
        }

        double total = validateSuggestionItems(response.getEnvelopes());
        if (total > 100.0) {
            throw new IllegalArgumentException("Total percentage exceeds 100");
        }
    }

    private double validateSuggestionItems(List<AiEnvelopeSuggestion> items) {
        double total = 0.0;

        for (AiEnvelopeSuggestion item : items) {
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

        return total;
    }

    private void normalizeStarterResponse(AiStarterEnvelopeResponse response, AiStarterEnvelopeRequest request) {
        if (response == null) {
            return;
        }
        if (response.getTitle() == null || response.getTitle().isBlank()) {
            response.setTitle(buildHeuristicTitle(request, false));
        }
        if (response.getReasoning() == null || response.getReasoning().isBlank()) {
            response.setReasoning(buildHeuristicReasoning(request, false));
        }
        response.setEnvelopes(normalizeItems(response.getEnvelopes(), 100.0));
    }

    private void normalizeAllocationResponse(AiBudgetAllocationResponse response, AiStarterEnvelopeRequest request) {
        if (response == null) {
            return;
        }
        if (response.getTitle() == null || response.getTitle().isBlank()) {
            response.setTitle(buildHeuristicTitle(request, true));
        }
        if (response.getReasoning() == null || response.getReasoning().isBlank()) {
            response.setReasoning(buildHeuristicReasoning(request, true));
        }
        List<AiEnvelopeSuggestion> normalized = normalizeItems(response.getEnvelopes(), 95.0);
        response.setEnvelopes(normalized);
        response.setTotalAllocatedPercentage(sumPercentages(normalized));
    }

    private void normalizeAssistantResponse(
        AiBudgetAssistantTurnResponse response,
        AiBudgetAssistantTurnRequest request
    ) {
        if (response == null) {
            return;
        }

        List<AiEnvelopeSuggestion> normalized =
            normalizeItems(response.getEnvelopes(), 100.0);
        double total = sumPercentages(normalized);
        double remainingAmount = computeRemainingAmount(request.getTotalBudget(), total);

        response.setEnvelopes(normalized);
        response.setTotalAllocatedPercentage(round1(total));
        response.setRemainingAmount(round2(remainingAmount));

        if (response.getAssistantMessage() == null || response.getAssistantMessage().isBlank()) {
            response.setAssistantMessage(buildAssistantFollowUpMessage(request, normalized, remainingAmount));
        }

        if (response.getReasoning() == null || response.getReasoning().isBlank()) {
            response.setReasoning(buildAssistantReasoning(request, normalized, remainingAmount));
        }

        boolean ready = Boolean.TRUE.equals(response.getReadyToFinalize())
            && total >= 90.0
            && remainingAmount <= request.getTotalBudget() * 0.1;
        response.setReadyToFinalize(ready);
    }

    private List<AiEnvelopeSuggestion> normalizeItems(List<AiEnvelopeSuggestion> items, double targetCap) {
        if (items == null || items.isEmpty()) {
            return items;
        }

        List<AiEnvelopeSuggestion> normalized = new ArrayList<>();
        double total = 0.0;
        for (AiEnvelopeSuggestion item : items) {
            if (item == null) {
                continue;
            }
            String category = normalizeCategory(item.getCategory());
            String conditionType = normalizeConditionType(item.getConditionType());
            String name = item.getName() == null ? "Envelope" : item.getName().trim();
            if (name.isBlank()) {
                name = defaultNameForCategory(category);
            }

            double percentage = item.getPercentage() == null ? 0.0 : item.getPercentage();
            if (percentage <= 0) {
                continue;
            }

            AiEnvelopeSuggestion normalizedItem = new AiEnvelopeSuggestion();
            normalizedItem.setName(name);
            normalizedItem.setCategory(category);
            normalizedItem.setConditionType(conditionType);
            normalizedItem.setPercentage(percentage);
            normalized.add(normalizedItem);
            total += percentage;
        }

        if (normalized.isEmpty()) {
            return normalized;
        }

        if (total > targetCap && total > 0) {
            double ratio = targetCap / total;
            double adjustedTotal = 0.0;
            for (AiEnvelopeSuggestion item : normalized) {
                double adjusted = round1(item.getPercentage() * ratio);
                item.setPercentage(Math.max(1.0, adjusted));
                adjustedTotal += item.getPercentage();
            }
            rebalanceRounding(normalized, targetCap, adjustedTotal);
        }

        return normalized;
    }

    private void rebalanceRounding(List<AiEnvelopeSuggestion> items, double targetCap, double currentTotal) {
        if (items.isEmpty()) {
            return;
        }
        double diff = round1(targetCap - currentTotal);
        if (Math.abs(diff) < 0.1) {
            return;
        }

        AiEnvelopeSuggestion first = items.get(0);
        first.setPercentage(Math.max(1.0, round1(first.getPercentage() + diff)));
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

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        return cleaned;
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

    private String normalizeConditionType(String value) {
        if (isAllowedConditionType(value)) {
            return value;
        }
        return "weekly";
    }

    private String normalizeCategory(String value) {
        if (isAllowedCategory(value)) {
            return value;
        }
        return "more";
    }

    private AiBudgetAssistantTurnResponse buildFallbackAssistantResponse(
        AiBudgetAssistantTurnRequest request
    ) {
        AiBudgetAssistantTurnResponse response = new AiBudgetAssistantTurnResponse();
        List<AiEnvelopeSuggestion> currentItems = request.getEnvelopes() == null
            ? Collections.emptyList()
            : request.getEnvelopes();
        List<AiEnvelopeSuggestion> normalizedCurrent = normalizeItems(currentItems, 100.0);

        List<AiEnvelopeSuggestion> envelopes = buildAssistantFallbackSuggestions(
            request,
            normalizedCurrent
        );
        double total = sumPercentages(envelopes);
        double remaining = computeRemainingAmount(request.getTotalBudget(), total);
        boolean userConfirmed = isAffirmingCompletion(request.getLatestUserMessage());

        response.setEnvelopes(envelopes);
        response.setAssistantMessage(buildAssistantFollowUpMessage(request, envelopes, remaining));
        response.setReasoning(buildAssistantReasoning(request, envelopes, remaining));
        response.setReadyToFinalize(
            userConfirmed && total >= 90.0 && remaining <= request.getTotalBudget() * 0.1
        );
        response.setTotalAllocatedPercentage(round1(total));
        response.setRemainingAmount(round2(remaining));
        response.setSource("fallback");
        return response;
    }

    private AiStarterEnvelopeResponse buildFallbackStarterPlan(AiStarterEnvelopeRequest request) {
        AiStarterEnvelopeResponse response = new AiStarterEnvelopeResponse();
        response.setTitle(buildHeuristicTitle(request, false));
        response.setReasoning(buildHeuristicReasoning(request, false));
        response.setEnvelopes(buildHeuristicSuggestions(request, 100.0));
        return response;
    }

    private AiBudgetAllocationResponse buildFallbackAllocationPlan(AiStarterEnvelopeRequest request) {
        AiBudgetAllocationResponse response = new AiBudgetAllocationResponse();
        response.setTitle(buildHeuristicTitle(request, true));
        response.setReasoning(buildHeuristicReasoning(request, true));
        List<AiEnvelopeSuggestion> suggestions = buildHeuristicSuggestions(request, 95.0);
        response.setEnvelopes(suggestions);
        response.setTotalAllocatedPercentage(sumPercentages(suggestions));
        return response;
    }

    private List<AiEnvelopeSuggestion> buildHeuristicSuggestions(AiStarterEnvelopeRequest request, double targetTotal) {
        String goal = request.getGoal() == null ? "" : request.getGoal().toLowerCase(Locale.ROOT);
        boolean savingsHeavy = containsAny(goal, "save", "savings", "buffer", "emergency", "discipline");
        boolean essentialsHeavy = containsAny(goal, "essential", "essentials", "discipline", "control", "tight", "lean", "rent", "bills");
        boolean travelHeavy = containsAny(goal, "travel", "flight", "trip", "vacation");
        boolean workHeavy = containsAny(goal, "work", "business", "client", "tools", "data", "internet");
        boolean educationHeavy = containsAny(goal, "school", "education", "tuition", "books");
        boolean faithHeavy = containsAny(goal, "faith", "tithe", "offering", "church");
        boolean foodHeavy = containsAny(goal, "food", "groceries", "feeding", "meal", "lunch");

        LinkedHashMap<String, SuggestionSeed> seeds = new LinkedHashMap<>();
        addSeed(seeds, foodHeavy ? "Groceries" : "Food", foodHeavy ? "groceries" : "food", chooseRecurringType(request, "food"), 22);
        addSeed(seeds, essentialsHeavy ? "Bills" : "Home", "home", chooseRecurringType(request, "home"), essentialsHeavy ? 24 : 18);
        addSeed(seeds, "Transport", "car", chooseRecurringType(request, "car"), travelHeavy ? 18 : 12);

        if (savingsHeavy) {
            addSeed(seeds, goal.contains("emergency") ? "Emergency Buffer" : "Savings", goal.contains("emergency") ? "security" : "savings", goal.contains("emergency") ? "emergency" : "dynamic", goal.contains("emergency") ? 22 : 20);
        } else {
            addSeed(seeds, "Savings", "savings", "dynamic", 12);
        }

        if (educationHeavy) {
            addSeed(seeds, "Education", "education", chooseRecurringType(request, "education"), 15);
        }
        if (workHeavy) {
            addSeed(seeds, goal.contains("internet") || goal.contains("data") ? "Data" : "Work Tools", goal.contains("internet") || goal.contains("data") ? "internet" : "tools", chooseRecurringType(request, "internet"), 12);
        }
        if (travelHeavy) {
            addSeed(seeds, "Travel", "flight", "dynamic", 16);
        }
        if (faithHeavy) {
            addSeed(seeds, goal.contains("offering") ? "Offering" : "Tithe", "faith", "weekly", 8);
        }

        addSeed(seeds, "Misc", "more", "daily", essentialsHeavy ? 6 : 10);

        List<SuggestionSeed> selected = new ArrayList<>(seeds.values());
        if (selected.size() < 4) {
            addSeed(seeds, "Lunch", "lunch", "daily", 8);
            selected = new ArrayList<>(seeds.values());
        }

        if (selected.size() > 6) {
            selected = selected.subList(0, 6);
        }

        double totalWeight = selected.stream().mapToDouble(seed -> seed.weight).sum();
        List<AiEnvelopeSuggestion> result = new ArrayList<>();
        double runningTotal = 0.0;

        for (int i = 0; i < selected.size(); i++) {
            SuggestionSeed seed = selected.get(i);
            double percentage = round1((seed.weight / totalWeight) * targetTotal);
            if (i == selected.size() - 1) {
                percentage = round1(targetTotal - runningTotal);
            }
            percentage = Math.max(4.0, percentage);
            runningTotal += percentage;
            result.add(buildItem(seed.name, percentage, seed.conditionType, seed.category));
        }

        return result;
    }

    private List<AiEnvelopeSuggestion> buildAssistantFallbackSuggestions(
        AiBudgetAssistantTurnRequest request,
        List<AiEnvelopeSuggestion> normalizedCurrent
    ) {
        String latestMessage = request.getLatestUserMessage() == null
            ? ""
            : request.getLatestUserMessage().trim();
        List<AiEnvelopeSuggestion> requestedFromMessage = parseRequestedEnvelopes(
            latestMessage,
            request
        );

        if (normalizedCurrent.isEmpty() && requestedFromMessage.isEmpty()) {
            return buildHeuristicSuggestions(toStarterRequest(request), 100.0);
        }

        LinkedHashMap<String, AiEnvelopeSuggestion> merged = new LinkedHashMap<>();
        for (AiEnvelopeSuggestion item : normalizedCurrent) {
            merged.put(item.getName().trim().toLowerCase(Locale.ROOT), cloneSuggestion(item));
        }

        for (AiEnvelopeSuggestion requested : requestedFromMessage) {
            String key = requested.getName().trim().toLowerCase(Locale.ROOT);
            AiEnvelopeSuggestion existing = merged.get(key);
            if (existing == null) {
                merged.put(key, requested);
                continue;
            }

            existing.setCategory(normalizeCategory(requested.getCategory()));
            existing.setConditionType(normalizeConditionType(requested.getConditionType()));
            if (requested.getPercentage() != null && requested.getPercentage() > 0) {
                existing.setPercentage(requested.getPercentage());
            }
        }

        List<AiEnvelopeSuggestion> mergedItems = new ArrayList<>(merged.values());
        boolean hasExplicitPercentages = requestedFromMessage.stream()
            .anyMatch(item -> item.getPercentage() != null && item.getPercentage() > 0);

        if (!hasExplicitPercentages) {
            rebalanceAssistantSuggestions(
                mergedItems,
                normalizedCurrent,
                request.getTotalBudget() == null ? 100.0 : 100.0
            );
        } else {
            mergedItems = normalizeItems(mergedItems, 100.0);
        }

        return mergedItems;
    }

    private void rebalanceAssistantSuggestions(
        List<AiEnvelopeSuggestion> mergedItems,
        List<AiEnvelopeSuggestion> normalizedCurrent,
        double targetTotal
    ) {
        if (mergedItems.isEmpty()) {
            return;
        }

        double currentTotal = sumPercentages(mergedItems);
        if (currentTotal <= 0) {
            double equalShare = round1(targetTotal / mergedItems.size());
            double running = 0.0;
            for (int i = 0; i < mergedItems.size(); i++) {
                double pct = i == mergedItems.size() - 1
                    ? round1(targetTotal - running)
                    : equalShare;
                mergedItems.get(i).setPercentage(Math.max(4.0, pct));
                running += mergedItems.get(i).getPercentage();
            }
            return;
        }

        if (!normalizedCurrent.isEmpty() && currentTotal <= targetTotal) {
            return;
        }

        List<AiEnvelopeSuggestion> normalized = normalizeItems(mergedItems, targetTotal);
        mergedItems.clear();
        mergedItems.addAll(normalized);
    }

    private List<AiEnvelopeSuggestion> parseRequestedEnvelopes(
        String latestMessage,
        AiBudgetAssistantTurnRequest request
    ) {
        if (latestMessage.isBlank()) {
            return Collections.emptyList();
        }

        List<EnvelopeKeyword> keywords = List.of(
            new EnvelopeKeyword("emergency buffer", "Emergency Buffer", "security", "emergency"),
            new EnvelopeKeyword("school runs", "School Runs", "education", chooseRecurringType(toStarterRequest(request), "education")),
            new EnvelopeKeyword("work tools", "Work Tools", "tools", chooseRecurringType(toStarterRequest(request), "tools")),
            new EnvelopeKeyword("client transport", "Client Transport", "car", chooseRecurringType(toStarterRequest(request), "car")),
            new EnvelopeKeyword("rent", "Rent", "home", chooseRecurringType(toStarterRequest(request), "home")),
            new EnvelopeKeyword("bills", "Bills", "home", chooseRecurringType(toStarterRequest(request), "home")),
            new EnvelopeKeyword("groceries", "Groceries", "groceries", chooseRecurringType(toStarterRequest(request), "groceries")),
            new EnvelopeKeyword("food", "Food", "food", chooseRecurringType(toStarterRequest(request), "food")),
            new EnvelopeKeyword("transport", "Transport", "car", chooseRecurringType(toStarterRequest(request), "car")),
            new EnvelopeKeyword("savings", "Savings", "savings", "dynamic"),
            new EnvelopeKeyword("data", "Data", "internet", chooseRecurringType(toStarterRequest(request), "internet")),
            new EnvelopeKeyword("internet", "Internet", "internet", chooseRecurringType(toStarterRequest(request), "internet")),
            new EnvelopeKeyword("tithe", "Tithe", "faith", "weekly"),
            new EnvelopeKeyword("offering", "Offering", "faith", "weekly"),
            new EnvelopeKeyword("travel", "Travel", "flight", "dynamic"),
            new EnvelopeKeyword("education", "Education", "education", chooseRecurringType(toStarterRequest(request), "education")),
            new EnvelopeKeyword("lunch", "Lunch", "lunch", "daily"),
            new EnvelopeKeyword("home", "Home", "home", chooseRecurringType(toStarterRequest(request), "home")),
            new EnvelopeKeyword("misc", "Misc", "more", "daily")
        );

        String normalizedMessage = latestMessage.toLowerCase(Locale.ROOT);
        List<AiEnvelopeSuggestion> suggestions = new ArrayList<>();

        for (EnvelopeKeyword keyword : keywords) {
            if (!normalizedMessage.contains(keyword.phrase)) {
                continue;
            }
            Double explicitPercentage = extractRequestedPercentage(normalizedMessage, keyword.phrase);
            AiEnvelopeSuggestion item = new AiEnvelopeSuggestion();
            item.setName(keyword.label);
            item.setCategory(keyword.category);
            item.setConditionType(keyword.conditionType);
            item.setPercentage(explicitPercentage != null ? explicitPercentage : 0.0);
            suggestions.add(item);
        }

        if (suggestions.isEmpty()) {
            return Collections.emptyList();
        }

        long zeroCount = suggestions.stream()
            .filter(item -> item.getPercentage() == null || item.getPercentage() <= 0)
            .count();
        double allocatedExplicit = suggestions.stream()
            .mapToDouble(item -> item.getPercentage() == null ? 0.0 : item.getPercentage())
            .sum();
        double remaining = Math.max(0.0, 100.0 - allocatedExplicit);
        double shared = zeroCount > 0 ? round1(remaining / zeroCount) : 0.0;

        double running = allocatedExplicit;
        for (int i = 0; i < suggestions.size(); i++) {
            AiEnvelopeSuggestion item = suggestions.get(i);
            if (item.getPercentage() != null && item.getPercentage() > 0) {
                continue;
            }
            double pct = i == suggestions.size() - 1
                ? round1(Math.max(0.0, 100.0 - running))
                : shared;
            item.setPercentage(Math.max(4.0, pct));
            running += item.getPercentage();
        }

        return normalizeItems(suggestions, 100.0);
    }

    private Double extractRequestedPercentage(String message, String phrase) {
        Pattern before = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)\\s*%\\s+(?:for\\s+)?"
            + Pattern.quote(phrase));
        Matcher beforeMatcher = before.matcher(message);
        if (beforeMatcher.find()) {
            return parsePercentage(beforeMatcher.group(1));
        }

        Pattern after = Pattern.compile(Pattern.quote(phrase)
            + "(?:\\s+at|\\s+for|\\s*=|\\s*:)??\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*%");
        Matcher afterMatcher = after.matcher(message);
        if (afterMatcher.find()) {
            return parsePercentage(afterMatcher.group(1));
        }

        return null;
    }

    private Double parsePercentage(String raw) {
        try {
            double value = Double.parseDouble(raw);
            if (value <= 0) {
                return null;
            }
            return Math.min(100.0, round1(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private AiEnvelopeSuggestion cloneSuggestion(AiEnvelopeSuggestion item) {
        AiEnvelopeSuggestion clone = new AiEnvelopeSuggestion();
        clone.setName(item.getName());
        clone.setCategory(item.getCategory());
        clone.setConditionType(item.getConditionType());
        clone.setPercentage(item.getPercentage());
        return clone;
    }

    private AiStarterEnvelopeRequest toStarterRequest(AiBudgetAssistantTurnRequest request) {
        AiStarterEnvelopeRequest starterRequest = new AiStarterEnvelopeRequest();
        starterRequest.setTotalBudget(request.getTotalBudget());
        starterRequest.setDurationDays(request.getDurationDays());
        starterRequest.setGoal(request.getGoal());
        starterRequest.setCurrency(request.getCurrency());
        return starterRequest;
    }

    private List<AiBudgetAssistantMessage> trimConversation(List<AiBudgetAssistantMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        int start = Math.max(0, messages.size() - 8);
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    private String buildHeuristicTitle(AiStarterEnvelopeRequest request, boolean allocation) {
        String goal = request.getGoal() == null ? "" : request.getGoal().toLowerCase(Locale.ROOT);
        if (containsAny(goal, "save", "savings", "buffer")) {
            return allocation ? "Savings-led allocation" : "Savings-first starter plan";
        }
        if (containsAny(goal, "essential", "discipline", "control", "lean")) {
            return allocation ? "Essentials-led allocation" : "Essentials-first starter plan";
        }
        if (containsAny(goal, "travel", "flight", "trip")) {
            return allocation ? "Travel-ready allocation" : "Travel-focused starter plan";
        }
        if (containsAny(goal, "school", "education", "tuition")) {
            return allocation ? "Education-led allocation" : "Education-focused starter plan";
        }
        return allocation ? "Personalized allocation mix" : "Personalized starter plan";
    }

    private String buildHeuristicReasoning(AiStarterEnvelopeRequest request, boolean allocation) {
        String goal = request.getGoal() == null ? "" : request.getGoal().toLowerCase(Locale.ROOT);
        if (containsAny(goal, "save", "savings", "buffer", "emergency")) {
            return "This mix protects core spending first while keeping stronger room for savings and financial buffer.";
        }
        if (containsAny(goal, "essential", "discipline", "control", "lean")) {
            return "This mix leans toward essentials and repeat spending so the budget stays tighter and easier to manage.";
        }
        if (containsAny(goal, "travel", "flight", "trip")) {
            return "This mix gives stronger room to movement and travel-related needs without ignoring daily spending.";
        }
        if (containsAny(goal, "work", "business", "tools", "internet", "data")) {
            return "This mix gives more room to productive spend while keeping household and recurring needs covered.";
        }
        return allocation
            ? "This draft balances essentials, flexibility, and savings around the budget amount and stated goal."
            : "This starter draft adapts the envelope mix to the stated goal instead of relying on one default structure.";
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String chooseRecurringType(AiStarterEnvelopeRequest request, String category) {
        int days = request.getDurationDays() == null ? 30 : request.getDurationDays();
        if ("security".equals(category)) {
            return "emergency";
        }
        if (days <= 8) {
            return "daily";
        }
        if (days <= 21) {
            return "weekly";
        }
        return switch (category) {
            case "food", "groceries", "lunch", "internet", "car" -> "weekly";
            default -> "dynamic";
        };
    }

    private void addSeed(Map<String, SuggestionSeed> seeds, String name, String category, String conditionType, double weight) {
        seeds.putIfAbsent(name.toLowerCase(Locale.ROOT), new SuggestionSeed(name, category, conditionType, weight));
    }

    private double sumPercentages(List<AiEnvelopeSuggestion> items) {
        return items.stream().mapToDouble(item -> item.getPercentage() == null ? 0.0 : item.getPercentage()).sum();
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double computeRemainingAmount(Double totalBudget, double allocatedPercentage) {
        if (totalBudget == null || totalBudget <= 0) {
            return 0.0;
        }
        double remainingRatio = Math.max(0.0, 100.0 - allocatedPercentage) / 100.0;
        return totalBudget * remainingRatio;
    }

    private String safeBudgetName(String budgetName) {
        return budgetName == null || budgetName.isBlank() ? "This budget" : budgetName.trim();
    }

    private String safeGoal(String goal) {
        return goal == null ? "" : goal.trim();
    }

    private String safeCurrency(String currency) {
        return currency == null || currency.isBlank() ? "NGN" : currency.trim();
    }

    private String buildAssistantFollowUpMessage(
        AiBudgetAssistantTurnRequest request,
        List<AiEnvelopeSuggestion> envelopes,
        double remainingAmount
    ) {
        String latestMessage = request.getLatestUserMessage() == null
            ? ""
            : request.getLatestUserMessage().toLowerCase(Locale.ROOT);

        if (envelopes.isEmpty()) {
            return "Let's start by naming the first few envelopes you want this budget to cover, and I will help split the money realistically.";
        }

        if (remainingAmount <= 0.01) {
            if (containsAny(latestMessage, "why", "left", "remaining", "0.00", "zero")) {
                return "The plan is fully allocated, so there is no money left to assign. The continue button is still waiting for your final confirmation before I mark this budget as ready.";
            }
            return "This plan is fully allocated now, so there is no balance left to assign. If it looks right to you, say you are done and I will treat it as ready to finalize.";
        }

        if (remainingAmount > request.getTotalBudget() * 0.1) {
            String nextName = pickNextPromptCategory(envelopes);
            return String.format(
                Locale.ROOT,
                "I have mapped %.1f%% so far. You still have %s %.2f left to plan. We can add or rebalance %s next if that fits what you want.",
                sumPercentages(envelopes),
                safeCurrency(request.getCurrency()),
                round2(remainingAmount),
                nextName
            );
        }

        return "This plan is almost complete, with only a small balance left. If these envelopes feel right to you, say you are done and I will treat it as ready to finalize.";
    }

    private String buildAssistantReasoning(
        AiBudgetAssistantTurnRequest request,
        List<AiEnvelopeSuggestion> envelopes,
        double remainingAmount
    ) {
        if (remainingAmount > request.getTotalBudget() * 0.1) {
            return "This draft keeps the current envelope choices intact while showing what remains so the next decision can stay realistic.";
        }
        return "This allocation now covers the main envelopes with only a small balance left, so it is close to a finalized budget plan.";
    }

    private boolean isAffirmingCompletion(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(
            normalized,
            "we're done",
            "we are done",
            "i am done",
            "i'm done",
            "looks good",
            "this is fine",
            "finalize",
            "confirm",
            "done"
        );
    }

    private String pickNextPromptCategory(List<AiEnvelopeSuggestion> envelopes) {
        for (AiEnvelopeSuggestion item : envelopes) {
            if ("more".equals(item.getCategory())) {
                return "your remaining flexible spend";
            }
        }
        return "one more priority envelope";
    }

    private String defaultNameForCategory(String category) {
        return switch (category) {
            case "savings" -> "Savings";
            case "security" -> "Emergency Buffer";
            case "food" -> "Food";
            case "car" -> "Transport";
            case "home" -> "Home";
            case "education" -> "Education";
            case "flight" -> "Travel";
            case "tools" -> "Tools";
            case "gift" -> "Giving";
            case "work" -> "Work";
            case "internet" -> "Internet";
            case "faith" -> "Faith";
            case "groceries" -> "Groceries";
            case "lunch" -> "Lunch";
            default -> "Misc";
        };
    }

    private AiEnvelopeSuggestion buildItem(String name, Double pct, String type, String category) {
        AiEnvelopeSuggestion item = new AiEnvelopeSuggestion();
        item.setName(name);
        item.setPercentage(pct);
        item.setConditionType(type);
        item.setCategory(category);
        return item;
    }

    private static class EnvelopeKeyword {
        private final String phrase;
        private final String label;
        private final String category;
        private final String conditionType;

        private EnvelopeKeyword(String phrase, String label, String category, String conditionType) {
            this.phrase = phrase;
            this.label = label;
            this.category = category;
            this.conditionType = conditionType;
        }
    }

    private static class SuggestionSeed {
        private final String name;
        private final String category;
        private final String conditionType;
        private final double weight;

        private SuggestionSeed(String name, String category, String conditionType, double weight) {
            this.name = name;
            this.category = category;
            this.conditionType = conditionType;
            this.weight = weight;
        }
    }
}

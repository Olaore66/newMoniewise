package com.moniewise.moniewise_backend.dto.response;

import java.util.List;

public class AiBudgetAssistantTurnResponse {
    private String assistantMessage;
    private String reasoning;
    private String source;
    private Boolean readyToFinalize;
    private Double totalAllocatedPercentage;
    private Double remainingAmount;
    private List<AiEnvelopeSuggestion> envelopes;

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public void setAssistantMessage(String assistantMessage) {
        this.assistantMessage = assistantMessage;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Boolean getReadyToFinalize() {
        return readyToFinalize;
    }

    public void setReadyToFinalize(Boolean readyToFinalize) {
        this.readyToFinalize = readyToFinalize;
    }

    public Double getTotalAllocatedPercentage() {
        return totalAllocatedPercentage;
    }

    public void setTotalAllocatedPercentage(Double totalAllocatedPercentage) {
        this.totalAllocatedPercentage = totalAllocatedPercentage;
    }

    public Double getRemainingAmount() {
        return remainingAmount;
    }

    public void setRemainingAmount(Double remainingAmount) {
        this.remainingAmount = remainingAmount;
    }

    public List<AiEnvelopeSuggestion> getEnvelopes() {
        return envelopes;
    }

    public void setEnvelopes(List<AiEnvelopeSuggestion> envelopes) {
        this.envelopes = envelopes;
    }
}

package com.moniewise.moniewise_backend.dto.response;

import java.util.List;

public class AiBudgetAllocationResponse {
    private String title;
    private String reasoning;
    private Double totalAllocatedPercentage;
    private List<AiEnvelopeSuggestion> envelopes;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public Double getTotalAllocatedPercentage() {
        return totalAllocatedPercentage;
    }

    public void setTotalAllocatedPercentage(Double totalAllocatedPercentage) {
        this.totalAllocatedPercentage = totalAllocatedPercentage;
    }

    public List<AiEnvelopeSuggestion> getEnvelopes() {
        return envelopes;
    }

    public void setEnvelopes(List<AiEnvelopeSuggestion> envelopes) {
        this.envelopes = envelopes;
    }
}
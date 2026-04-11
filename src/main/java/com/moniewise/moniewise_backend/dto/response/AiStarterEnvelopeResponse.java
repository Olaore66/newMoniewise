package com.moniewise.moniewise_backend.dto.response;

import java.util.List;

public class AiStarterEnvelopeResponse {
    private String title;
    private String reasoning;
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

    public List<AiEnvelopeSuggestion> getEnvelopes() {
        return envelopes;
    }

    public void setEnvelopes(List<AiEnvelopeSuggestion> envelopes) {
        this.envelopes = envelopes;
    }
}


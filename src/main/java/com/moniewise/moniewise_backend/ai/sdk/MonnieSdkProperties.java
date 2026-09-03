package com.moniewise.moniewise_backend.ai.sdk;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "ai.sdk")
public class MonnieSdkProperties {

    /** When false, the in-process agent is not started. Existing /ai Gemini routes stay. */
    private boolean enabled = true;
    /** gemini | groq | openai. Empty infers from whichever key is present. */
    private String provider = "";
    private String model = "";
    private String groqApiKey = "";
    private String openaiApiKey = "";
    private int historyLimit = 24;
    private int turnBudgetSeconds = 90;
    private List<String> enabledKinds = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getGroqApiKey() {
        return groqApiKey;
    }

    public void setGroqApiKey(String groqApiKey) {
        this.groqApiKey = groqApiKey;
    }

    public String getOpenaiApiKey() {
        return openaiApiKey;
    }

    public void setOpenaiApiKey(String openaiApiKey) {
        this.openaiApiKey = openaiApiKey;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    public int getTurnBudgetSeconds() {
        return turnBudgetSeconds;
    }

    public void setTurnBudgetSeconds(int turnBudgetSeconds) {
        this.turnBudgetSeconds = turnBudgetSeconds;
    }

    public List<String> getEnabledKinds() {
        return enabledKinds;
    }

    public void setEnabledKinds(List<String> enabledKinds) {
        this.enabledKinds = enabledKinds == null ? new ArrayList<>() : enabledKinds;
    }
}

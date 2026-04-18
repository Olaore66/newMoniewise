package com.moniewise.moniewise_backend.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.moniewise.moniewise_backend.config.GeminiProperties;

@Service
public class GeminiService {
    private static final Logger logger = LoggerFactory.getLogger(GeminiService.class);

    private final GeminiProperties geminiProperties;
    private final RestTemplate restTemplate = new RestTemplate();

    public GeminiService(GeminiProperties geminiProperties) {
        this.geminiProperties = geminiProperties;
    }

    @PostConstruct
    public void logConfigurationStatus() {
        logger.info(
            "Gemini config loaded: keyPresent={}, model={}, url={}",
            hasConfiguredApiKey(),
            sanitizeValue(geminiProperties.getModel()),
            sanitizeValue(geminiProperties.getUrl())
        );
    }

    public String generateText(String prompt) {
        if (geminiProperties.getApiKey() == null || geminiProperties.getApiKey().isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is missing or blank");
        }
        if (geminiProperties.getModel() == null || geminiProperties.getModel().isBlank()) {
            throw new IllegalStateException("gemini.model is missing or blank");
        }
        if (geminiProperties.getUrl() == null || geminiProperties.getUrl().isBlank()) {
            throw new IllegalStateException("gemini.url is missing or blank");
        }

        String endpoint = String.format(
            "%s/%s:generateContent?key=%s",
            geminiProperties.getUrl(),
            geminiProperties.getModel(),
            geminiProperties.getApiKey()
        );

        Map<String, Object> part = new HashMap<>();
        part.put("text", prompt);

        Map<String, Object> content = new HashMap<>();
        content.put("parts", List.of(part));

        Map<String, Object> body = new HashMap<>();
        body.put("contents", List.of(content));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response =
                restTemplate.exchange(endpoint, HttpMethod.POST, entity, Map.class);

            return extractText(response.getBody());
        } catch (HttpStatusCodeException e) {
            logger.warn(
                "Gemini HTTP error status={} model={} body={}",
                e.getStatusCode(),
                geminiProperties.getModel(),
                e.getResponseBodyAsString()
            );
            throw new RuntimeException("Gemini HTTP request failed with status " + e.getStatusCode(), e);
        } catch (Exception e) {
            logger.warn(
                "Gemini request failed for model={}: {}",
                geminiProperties.getModel(),
                e.getMessage()
            );
            throw new RuntimeException("Gemini request failed", e);
        }
    }

    public Map<String, Object> getConfigurationStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("configured", isConfigured());
        status.put("keyPresent", hasConfiguredApiKey());
        status.put("model", sanitizeValue(geminiProperties.getModel()));
        status.put("url", sanitizeValue(geminiProperties.getUrl()));
        return status;
    }

    public boolean isConfigured() {
        return hasConfiguredApiKey()
            && geminiProperties.getModel() != null
            && !geminiProperties.getModel().isBlank()
            && geminiProperties.getUrl() != null
            && !geminiProperties.getUrl().isBlank();
    }

    private boolean hasConfiguredApiKey() {
        return geminiProperties.getApiKey() != null && !geminiProperties.getApiKey().isBlank();
    }

    private String sanitizeValue(String value) {
        return value == null || value.isBlank() ? "<missing>" : value;
    }

    private String extractText(Map responseBody) {
        try {
            List candidates = (List) responseBody.get("candidates");
            Map firstCandidate = (Map) candidates.get(0);
            Map content = (Map) firstCandidate.get("content");
            List parts = (List) content.get("parts");
            Map firstPart = (Map) parts.get(0);
            return firstPart.get("text").toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract Gemini response text", e);
        }
    }
}

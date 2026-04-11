package com.moniewise.moniewise_backend.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.moniewise.moniewise_backend.config.GeminiProperties;

@Service
public class GeminiService {

    private final GeminiProperties geminiProperties;
    private final RestTemplate restTemplate = new RestTemplate();

    public GeminiService(GeminiProperties geminiProperties) {
        this.geminiProperties = geminiProperties;
    }

    public String generateText(String prompt) {
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

        ResponseEntity<Map> response =
            restTemplate.exchange(endpoint, HttpMethod.POST, entity, Map.class);

        return extractText(response.getBody());
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

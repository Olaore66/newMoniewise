package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.WebhookEvent;
import com.moniewise.moniewise_backend.repository.WebhookEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class WebhookEventService {

    private final WebhookEventRepository webhookEventRepository;

    public WebhookEventService(WebhookEventRepository webhookEventRepository) {
        this.webhookEventRepository = webhookEventRepository;
    }

    @Transactional
    public WebhookEvent saveIfNew(
            String providerName,
            String eventType,
            String externalReference,
            String idempotencyKey,
            String signature,
            String payloadJson,
            String headersJson
    ) {
        return webhookEventRepository
                .findByProviderNameAndIdempotencyKey(providerName, idempotencyKey)
                .orElseGet(() -> {
                    WebhookEvent event = new WebhookEvent();
                    event.setProviderName(providerName);
                    event.setEventType(eventType);
                    event.setExternalReference(externalReference);
                    event.setIdempotencyKey(idempotencyKey);
                    event.setSignature(signature);
                    event.setPayloadJson(payloadJson);
                    event.setHeadersJson(headersJson);
                    event.setProcessingStatus("RECEIVED");
                    event.setProcessingAttempts(0);
                    event.setReceivedAt(LocalDateTime.now());
                    return webhookEventRepository.save(event);
                });
    }

    public boolean alreadyProcessed(String providerName, String idempotencyKey) {
        return webhookEventRepository
                .findByProviderNameAndIdempotencyKey(providerName, idempotencyKey)
                .isPresent();
    }

    @Transactional
    public void markProcessed(Long webhookEventId) {
        WebhookEvent event = webhookEventRepository.findById(webhookEventId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook event not found"));

        event.setProcessingStatus("PROCESSED");
        event.setProcessedAt(LocalDateTime.now());
        event.setProcessingAttempts(event.getProcessingAttempts() + 1);
        webhookEventRepository.save(event);
    }

    @Transactional
    public void markFailed(Long webhookEventId, String errorMessage) {
        WebhookEvent event = webhookEventRepository.findById(webhookEventId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook event not found"));

        event.setProcessingStatus("FAILED");
        event.setErrorMessage(errorMessage);
        event.setProcessingAttempts(event.getProcessingAttempts() + 1);
        webhookEventRepository.save(event);
    }
}
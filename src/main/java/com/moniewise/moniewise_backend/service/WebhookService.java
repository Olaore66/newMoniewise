package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.WebhookEvent;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.SecureWaveGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookEventService webhookEventService;
    private final WalletWebhookService walletWebhookService;
    private final PaymentGatewayResolver paymentGatewayResolver;

    public WebhookService(
            WebhookEventService webhookEventService,
            WalletWebhookService walletWebhookService,
            PaymentGatewayResolver paymentGatewayResolver
    ) {
        this.webhookEventService = webhookEventService;
        this.walletWebhookService = walletWebhookService;
        this.paymentGatewayResolver = paymentGatewayResolver;
    }

    public void processSecureWaveWebhook(String signatureHeader, String rawPayload) {
        PaymentGateway gateway = paymentGatewayResolver.resolveByProviderName(SecureWaveGateway.PROVIDER_NAME);
        String cleanPayload = normalizePayload(rawPayload);

        if (!gateway.validateWebhookSignature(signatureHeader, cleanPayload)) {
            logger.warn("Rejected SecureWave webhook due to signature mismatch");
            throw new SecurityException("Invalid SecureWave webhook signature");
        }

        String providerName = gateway.getProviderName();
        String eventType = gateway.extractWebhookEventType(cleanPayload);
        String externalReference = gateway.extractWebhookReference(cleanPayload);
        String idempotencyKey = generateIdempotencyKey(providerName, externalReference, cleanPayload);

        if (webhookEventService.alreadyProcessed(providerName, idempotencyKey)) {
            logger.info("Duplicate SecureWave webhook detected, skipping processing");
            return;
        }

        WebhookEvent event = webhookEventService.saveIfNew(
                providerName,
                eventType,
                externalReference,
                idempotencyKey,
                signatureHeader,
                cleanPayload,
                null
        );

        try {
            if (isFundingEvent(eventType)) {
                walletWebhookService.processFundingWebhook(cleanPayload);
            } else {
                logger.info("Ignoring unsupported SecureWave webhook event type {}", eventType);
            }
            webhookEventService.markProcessed(event.getId());
        } catch (Exception e) {
            logger.error("Webhook processing failed for event {}", event.getId(), e);
            webhookEventService.markFailed(event.getId(), e.getMessage());
            throw e;
        }
    }

    private String normalizePayload(String rawPayload) {
        String cleanPayload = rawPayload == null ? "" : rawPayload.trim();
        if (cleanPayload.startsWith("\"") && cleanPayload.endsWith("\"")) {
            cleanPayload = cleanPayload.substring(1, cleanPayload.length() - 1);
        }
        return cleanPayload.replace("\\\"", "\"");
    }

    private String generateIdempotencyKey(String providerName, String externalReference, String payload) {
        if (externalReference != null && !externalReference.isBlank()) {
            return externalReference;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String combined = providerName + payload;
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error generating idempotency key", e);
        }
    }

    private boolean isFundingEvent(String eventType) {
        return "SUCCESSFUL_TRANSACTION".equalsIgnoreCase(eventType)
                || "payment_successful".equalsIgnoreCase(eventType);
    }
}

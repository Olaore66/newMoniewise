package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.entity.OutboxEvent;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class NotificationOutboxWorker {

    private static final Logger logger = LoggerFactory.getLogger(NotificationOutboxWorker.class);

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public NotificationOutboxWorker(
            OutboxEventRepository outboxEventRepository,
            NotificationService notificationService,
            ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${moniewise.outbox.worker.fixed-delay-ms:5000}")
    @Transactional
    public void processOutboxEvents() {
        List<OutboxEvent> events = outboxEventRepository.claimPendingEvents(200);

        for (OutboxEvent event : events) {
            try {
                event.setStatus("PROCESSING");
                event.setLockedAt(LocalDateTime.now());

                Map<String, Object> payload =
                        objectMapper.readValue(event.getPayload(), new TypeReference<>() {});

                String redirectUrl = event.getEnvelopeId() != null
                        ? "/envelopes/" + event.getEnvelopeId()
                        : event.getBudgetId() != null
                        ? "/budgets/" + event.getBudgetId()
                        : null;

                notificationService.processOutboxNotification(
                        event.getUserId(),
                        NotificationType.valueOf(event.getEventType()),
                        payload,
                        event.getBudgetId(),
                        event.getEnvelopeId(),
                        redirectUrl
                );

                event.setStatus("PROCESSED");
                event.setProcessedAt(LocalDateTime.now());

            } catch (Exception e) {
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastError(e.getMessage());

                if (event.getRetryCount() >= 5) {
                    event.setStatus("FAILED");
                } else {
                    event.setStatus("PENDING");
                }

                logger.error("Failed to process outbox event {}", event.getId(), e);
            }
        }

        outboxEventRepository.saveAll(events);
    }
}
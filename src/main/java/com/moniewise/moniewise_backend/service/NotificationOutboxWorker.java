package com.moniewise.moniewise_backend.service;

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

    public NotificationOutboxWorker(
            OutboxEventRepository outboxEventRepository,
            NotificationService notificationService
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelayString = "${moniewise.outbox.worker.fixed-delay-ms:5000}")
    @Transactional
    public void processOutboxEvents() {
        List<OutboxEvent> events = outboxEventRepository.claimPendingEvents(200);

        if (events.isEmpty()) {
            return;
        }

        logger.info("Found {} pending notification outbox event(s)", events.size());

        for (OutboxEvent event : events) {
            processSingleEvent(event);
        }

        outboxEventRepository.saveAll(events);
    }

    private void processSingleEvent(OutboxEvent event) {
        try {
            logger.info("Processing outbox event {} type {}", event.getId(), event.getEventType());

            event.setStatus("PROCESSING");
            event.setLockedAt(LocalDateTime.now());

            Map<String, Object> payload = event.getPayload();

            String redirectUrl = buildRedirectUrl(event);

            NotificationType notificationType = NotificationType.valueOf(event.getEventType());

            notificationService.processOutboxNotification(
                    event.getUserId(),
                    notificationType,
                    payload,
                    event.getBudgetId(),
                    event.getEnvelopeId(),
                    redirectUrl
            );

            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            event.setLastError(null);

            logger.info("Processed outbox event {} type {}", event.getId(), event.getEventType());

        } catch (Exception e) {
            int retryCount = event.getRetryCount() + 1;

            event.setRetryCount(retryCount);
            event.setLastError(e.getMessage());

            if (retryCount >= 5) {
                event.setStatus("FAILED");
                logger.error("Outbox event {} failed permanently after {} attempts",
                        event.getId(), retryCount, e);
            } else {
                event.setStatus("PENDING");
                logger.warn("Outbox event {} failed attempt {}. It will be retried. Error: {}",
                        event.getId(), retryCount, e.getMessage());
            }
        }
    }

    private String buildRedirectUrl(OutboxEvent event) {
        if (event.getEnvelopeId() != null) {
            return "/envelopes/" + event.getEnvelopeId();
        }

        if (event.getBudgetId() != null) {
            return "/budgets/" + event.getBudgetId();
        }

        return "/notifications";
    }
}
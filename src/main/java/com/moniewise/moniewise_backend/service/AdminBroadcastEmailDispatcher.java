package com.moniewise.moniewise_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminBroadcastEmailDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(AdminBroadcastEmailDispatcher.class);

    private final NotificationService notificationService;

    public AdminBroadcastEmailDispatcher(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Async
    public void sendBroadcastEmails(List<Recipient> recipients,
                                    String subject,
                                    String tag,
                                    String title,
                                    String body,
                                    String footerNote,
                                    String ctaLabel,
                                    String ctaUrl,
                                    String broadcastType) {
        int sent = 0;
        int skipped = 0;

        for (Recipient recipient : recipients) {
            if (recipient.email() == null || recipient.email().isBlank()) {
                skipped++;
                continue;
            }

            try {
                notificationService.sendAdminBroadcastEmail(
                        recipient.email(),
                        recipient.firstName(),
                        subject,
                        tag,
                        title,
                        body,
                        footerNote,
                        ctaLabel,
                        ctaUrl
                );
                sent++;
            } catch (Exception e) {
                skipped++;
                logger.error("[ADMIN-BROADCAST] Failed to queue email type={} to {}",
                        broadcastType, recipient.email(), e);
            }
        }

        logger.info("[ADMIN-BROADCAST] Email dispatch finished type={} sent={} skipped={}",
                broadcastType, sent, skipped);
    }

    public record Recipient(String email, String firstName) {
    }
}

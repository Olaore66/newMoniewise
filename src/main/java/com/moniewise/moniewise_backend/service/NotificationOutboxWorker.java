package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.OutboxEvent;
import com.moniewise.moniewise_backend.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Drains the notification outbox. Each run:
 * <ol>
 *   <li>Claims a batch in a <b>short</b> transaction ({@code FOR UPDATE SKIP LOCKED},
 *       marks them {@code PROCESSING}) — no FCM work is done while a DB transaction /
 *       row locks are held.</li>
 *   <li>Delivers each event in its <b>own</b> transaction via
 *       {@link NotificationService#deliverOutboxEvent(Long)}, which is idempotent
 *       (writes the inbox row once, pushes each token once) and never rethrows — so
 *       one bad event can't poison the whole batch.</li>
 * </ol>
 */
@Service
public class NotificationOutboxWorker {

    private static final Logger logger = LoggerFactory.getLogger(NotificationOutboxWorker.class);

    private static final int BATCH_SIZE = 200;

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationService notificationService;

    /** Self-proxy so {@link #claimBatch()} runs in its own committed transaction. */
    @Autowired
    @Lazy
    private NotificationOutboxWorker self;

    public NotificationOutboxWorker(
            OutboxEventRepository outboxEventRepository,
            NotificationService notificationService
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelayString = "${moniewise.outbox.worker.fixed-delay-ms:5000}")
    public void processOutboxEvents() {
        List<Long> ids;
        try {
            ids = self.claimBatch();
        } catch (Exception e) {
            logger.error("[OUTBOX] Failed to claim a batch of events", e);
            return;
        }

        if (ids.isEmpty()) {
            return;
        }

        logger.info("[OUTBOX] Claimed {} event(s) for delivery", ids.size());

        for (Long id : ids) {
            try {
                notificationService.deliverOutboxEvent(id);
            } catch (Exception e) {
                // deliverOutboxEvent handles its own failures + retry state; this only
                // guards against a hard crash so the rest of the batch still runs.
                logger.error("[OUTBOX] Unexpected failure delivering event {}", id, e);
            }
        }
    }

    /**
     * Dead-letter visibility: periodically surfaces events that exhausted their retries
     * (status {@code FAILED}). Without this they'd sit silently in the table after the
     * original error log scrolled off. An ERROR-level summary here is picked up by
     * log-based alerting; the per-type breakdown says where the failures cluster.
     */
    @Scheduled(fixedDelayString = "${moniewise.outbox.deadletter.alert-ms:900000}") // 15 min
    public void alertOnDeadLetters() {
        long failed;
        try {
            failed = outboxEventRepository.countByStatus("FAILED");
        } catch (Exception e) {
            logger.error("[OUTBOX] Dead-letter check failed", e);
            return;
        }
        if (failed == 0) {
            return;
        }
        StringBuilder breakdown = new StringBuilder();
        for (Object[] row : outboxEventRepository.countFailedByType()) {
            if (breakdown.length() > 0) {
                breakdown.append(", ");
            }
            breakdown.append(row[0]).append('=').append(row[1]);
        }
        logger.error("[OUTBOX][DEAD-LETTER] {} notification event(s) permanently FAILED and need attention: {}",
                failed, breakdown);
    }

    /**
     * Atomically claims a batch and flips it to PROCESSING in a short transaction.
     * The {@code FOR UPDATE SKIP LOCKED} query makes this safe across multiple app
     * instances — each claims a disjoint set of rows.
     */
    @Transactional
    public List<Long> claimBatch() {
        List<OutboxEvent> events = outboxEventRepository.claimPendingEvents(BATCH_SIZE);
        if (events.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = new ArrayList<>(events.size());
        for (OutboxEvent event : events) {
            event.setStatus("PROCESSING");
            event.setLockedAt(now);
            ids.add(event.getId());
        }
        outboxEventRepository.saveAll(events);
        return ids;
    }
}

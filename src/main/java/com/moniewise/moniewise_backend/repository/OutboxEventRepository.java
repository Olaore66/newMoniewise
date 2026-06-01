package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            SELECT *
            FROM outbox_events
            WHERE (
                status = 'PENDING'
                OR (
                    status = 'PROCESSING'
                    AND locked_at IS NOT NULL
                    AND locked_at < (CURRENT_TIMESTAMP - INTERVAL '10 minutes')
                )
            )
            AND retry_count < 5
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimPendingEvents(@Param("limit") int limit);
}
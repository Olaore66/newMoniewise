package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.AnalyticsLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AnalyticsLogRepository extends JpaRepository<AnalyticsLog, Long> {
    List<AnalyticsLog> findByUserId(Long userId);
    List<AnalyticsLog> findByUserIdAndCreatedAtBetween(Long userId, Instant start, Instant end);
}
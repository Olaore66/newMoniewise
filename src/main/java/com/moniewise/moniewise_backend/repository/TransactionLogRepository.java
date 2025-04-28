package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransactionLogRepository extends JpaRepository<TransactionLog, Long> {
    @Query("SELECT t FROM TransactionLog t WHERE t.sourceEnvelopeId = :envelopeId AND t.createdAt >= :startTime AND t.createdAt < :endTime")
    List<TransactionLog> findBySourceEnvelopeIdAndTimeRange(Long envelopeId, LocalDateTime startTime, LocalDateTime endTime);

    List<TransactionLog> findByUserId(Long userId);
    List<TransactionLog> findByBudgetId(Long budgetId);
//    List<TransactionLog> findByEnvelopeId(Long envelopeId);

//    List<TransactionLog> findByUserIdAndStatus(Long userId, TransactionStatus status);

}


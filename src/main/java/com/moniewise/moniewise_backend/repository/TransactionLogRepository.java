package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.enums.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Repository
public interface TransactionLogRepository extends JpaRepository<TransactionLog, Long> {

    // KEEP ALL THESE — THEY ARE PERFECT
    @Query("SELECT t FROM TransactionLog t WHERE t.sourceEnvelopeId = :envelopeId AND t.createdAt >= :startTime AND t.createdAt < :endTime")
    List<TransactionLog> findBySourceEnvelopeIdAndTimeRange(Long envelopeId, LocalDateTime startTime, LocalDateTime endTime);

    Page<TransactionLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Page<TransactionLog> findByBudgetIdOrderByCreatedAtDesc(Long budgetId, Pageable pageable);

    @Query("""
        SELECT t FROM TransactionLog t 
        WHERE (t.sourceEnvelopeId = :envelopeId OR t.targetEnvelopeId = :envelopeId)
          AND t.userId = :userId
        ORDER BY t.createdAt DESC
        """)
    Page<TransactionLog> findByEnvelopeId(
            @Param("userId") Long userId,
            @Param("envelopeId") Long envelopeId,
            Pageable pageable);

    Page<TransactionLog> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long userId, LocalDateTime start, LocalDateTime end, Pageable pageable);

    // Only keep ONE of these (this is the best)
    List<TransactionLog> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    // Keep your old ones if you use them elsewhere
    List<TransactionLog> findByUserId(Long userId);
    List<TransactionLog> findByBudgetId(Long budgetId);

    @Query("SELECT t FROM TransactionLog t WHERE t.userId = :userId " +
            "AND t.transactionType IN :types " +
            "ORDER BY t.createdAt DESC")
    Page<TransactionLog> findUserVisibleTransactions(
            @Param("userId") Long userId,
            @Param("types") Set<TransactionType> types,
            Pageable pageable);
}
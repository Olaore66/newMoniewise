package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

    @Query("""
        SELECT t FROM TransactionLog t
        WHERE t.userId = :userId
        AND (:types IS NULL OR t.transactionType IN :types)
        ORDER BY t.createdAt DESC
    """)
    List<TransactionLog> findUserTransactions(
            @Param("userId") Long userId,
            @Param("types") Set<TransactionType> types
    );

    Optional<TransactionLog> findByReference(String reference);

    boolean existsByReference(String transactionReference);

    // 👇 ADD THIS NUCLEAR METHOD 👇
    // 👇 FIX: Use ABS() to handle both negative and positive log entries correctly
    @Query("""
    SELECT COALESCE(SUM(ABS(t.amount)), 0)
    FROM TransactionLog t
    WHERE t.sourceEnvelopeId = :envelopeId
      AND t.createdAt >= :startDate
      AND t.transactionType IN :types
      AND t.status IN :statuses
""")
    BigDecimal calculateTotalSpent(
            @Param("envelopeId") Long envelopeId,
            @Param("startDate") LocalDateTime startDate,
            @Param("types") List<TransactionType> types,
            @Param("statuses") List<TransactionStatus> statuses
    );

    @Query("""
        SELECT COALESCE(SUM(ABS(t.amount)), 0) FROM TransactionLog t
        WHERE t.userId = :userId
          AND t.createdAt >= :start
          AND t.createdAt < :end
          AND t.transactionType IN :types
    """)
    BigDecimal sumAbsoluteAmountByUserAndDateRangeAndTypes(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("types") Set<TransactionType> types
    );

    @Query("""
        SELECT COALESCE(SUM(COALESCE(t.fee, 0)), 0) FROM TransactionLog t
        WHERE t.userId = :userId
          AND t.createdAt >= :start
          AND t.createdAt < :end
          AND t.transactionType IN :types
    """)
    BigDecimal sumFeesByUserAndDateRangeAndTypes(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("types") Set<TransactionType> types
    );

    Optional<TransactionLog> findByProviderReference(String providerReference);


    @Query("""
       SELECT COALESCE(SUM(ABS(t.amount)), 0)
       FROM TransactionLog t
       WHERE t.sourceEnvelopeId = :envelopeId
       AND t.transactionType IN :types
       AND t.status IN :statuses
       """)
    BigDecimal sumAbsAmountBySourceEnvelopeAndTypesAndStatuses(
            @Param("envelopeId") Long envelopeId,
            @Param("types") List<TransactionType> types,
            @Param("statuses") List<TransactionStatus> statuses
    );

    @Query("""
       SELECT COALESCE(SUM(ABS(t.amount)), 0)
       FROM TransactionLog t
       WHERE t.targetEnvelopeId = :envelopeId
       AND t.transactionType IN :types
       AND t.status IN :statuses
       """)
    BigDecimal sumAbsAmountByTargetEnvelopeAndTypesAndStatuses(
            @Param("envelopeId") Long envelopeId,
            @Param("types") List<TransactionType> types,
            @Param("statuses") List<TransactionStatus> statuses
    );

}

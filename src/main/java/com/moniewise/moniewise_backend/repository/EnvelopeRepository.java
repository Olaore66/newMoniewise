package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Repository
public interface EnvelopeRepository extends JpaRepository<Envelope, Long> {
    @Query("SELECT e FROM Envelope e WHERE e.budget.id = :budgetId")
    List<Envelope> findByBudgetId(Long budgetId);
    Optional<Envelope> findById(Long id);

    Optional<Envelope> findByIdAndBudget_UserEmail(Long id, String email);

    @Query("SELECT e FROM Envelope e WHERE e.nextDisbursementAt <= :now AND e.hasMatured = false")
    List<Envelope> findByNextDisbursementAtBeforeAndHasMaturedFalse(@Param("now") LocalDateTime now);

    // New method to find envelopes by conditions.type
    @Query(value = "SELECT * FROM envelopes e WHERE e.conditions->>'type' = :type", nativeQuery = true)
    List<Envelope> findByConditionsType(String type);

    @Query(value = "SELECT * FROM envelopes e JOIN budgets b ON e.budget_id = b.id WHERE b.status = CAST(:status AS VARCHAR) AND e.conditions->>'type' != :type", nativeQuery = true)
    Stream<Envelope> findByBudgetStatusAndTypeNot(@Param("status") BudgetStatus status, @Param("type") String type);

    List<Envelope> findByNextDisbursementAtBefore(LocalDateTime now, Pageable pageable);
}
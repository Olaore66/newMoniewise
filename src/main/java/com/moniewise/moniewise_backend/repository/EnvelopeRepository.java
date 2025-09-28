package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Repository
public interface EnvelopeRepository extends JpaRepository<Envelope, Long> {
    @Query("SELECT e FROM Envelope e WHERE e.budget.id = :budgetId")
    List<Envelope> findByBudgetId(Long budgetId);
    Optional<Envelope> findById(Long id);

    Optional<Envelope> findByIdAndBudget_UserEmail(Long id, String email);

    @Query("SELECT e FROM Envelope e")
    Stream<Envelope> findAllByStream();

    // New method to find envelopes by conditions.type
    @Query(value = "SELECT * FROM envelopes e WHERE e.conditions->>'type' = :type", nativeQuery = true)
    List<Envelope> findByConditionsType(String type);
}
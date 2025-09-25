package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnvelopeRepository extends JpaRepository<Envelope, Long> {
    List<Envelope> findByBudgetId(Long budgetId);
    Optional<Envelope> findById(Long id);

    Optional<Envelope> findByIdAndBudget_UserEmail(Long id, String email);

}
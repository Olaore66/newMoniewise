package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {
    List<Budget> findByUserId(Long userId);

    List<Budget> findByUserIdAndStatus(Long userId, BudgetStatus status);

    @Query("SELECT b.id FROM Budget b")
    List<Long> findAllIds();

    List<Budget> findByStatusAndEndDateLessThanEqual(BudgetStatus status, LocalDate endDate);
}
package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {
    List<Budget> findByUserId(Long userId);

    List<Budget> findByUserIdAndStatus(Long userId, BudgetStatus status);

    @Query(value = "SELECT CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Lagos'", nativeQuery = true)
    LocalDateTime getCurrentLagosTime();

    @Query("SELECT b.id FROM Budget b")
    List<Long> findAllIds();

    List<Budget> findByStatusAndEndDate(BudgetStatus status, LocalDate endDate);

//    List<Budget> findByStatusAndEndDateLessThanEqual(BudgetStatus status, LocalDate endDate);

    Optional<Budget> findByUserEmailAndStatus(String email, BudgetStatus status);
    @Query("SELECT DISTINCT b FROM Budget b LEFT JOIN FETCH b.envelopes WHERE b.user.id = :userId")
    List<Budget> findByUserIdWithEnvelopes(@Param("userId") Long userId);

    // ✅ NEW: Batch Processing for Expired Budgets
    Page<Budget> findByStatusAndEndDateLessThanEqual(BudgetStatus status, LocalDate endDate, Pageable pageable);

    // ... existing imports

    // ✅ NEW: Fetch the single most recent budget by status
    Optional<Budget> findTopByUserIdAndStatusOrderByCreatedAtDesc(Long userId, BudgetStatus status);
}
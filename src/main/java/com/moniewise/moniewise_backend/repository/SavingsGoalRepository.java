package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.SavingsGoal;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import javax.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface SavingsGoalRepository extends JpaRepository<SavingsGoal, Long> {

    @Query("SELECT COALESCE(SUM(s.currentBalance), 0) FROM SavingsGoal s " +
           "WHERE s.user.id = :userId AND s.status = :status")
    BigDecimal sumBalanceByUserIdAndStatus(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("status") SavingsStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SavingsGoal s WHERE s.id = :id")
    Optional<SavingsGoal> findByIdForUpdate(Long id);

    // For the Mobile Dashboard: Get all savings pots for a specific user
    List<SavingsGoal> findByUserIdOrderByCreatedAtDesc(Long userId);

    // For the Mobile Dashboard: Get only active pots for a specific user
    List<SavingsGoal> findByUserIdAndStatus(Long userId, SavingsStatus status);

    // For the Backend CRON Engine: Fetch ALL active pots globally to calculate daily interest
    Page<SavingsGoal> findByStatus(SavingsStatus status, Pageable pageable);
    
    // Safety Check: Prevent duplicate savings goal names for the same user
    boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);
}
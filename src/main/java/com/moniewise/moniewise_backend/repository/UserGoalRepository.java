package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.UserGoal;
import com.moniewise.moniewise_backend.enums.GoalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserGoalRepository extends JpaRepository<UserGoal, Long> {
    List<UserGoal> findByUserId(Long userId);
    List<UserGoal> findByUserIdAndStatus(Long userId, GoalStatus status);
}
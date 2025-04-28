package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.RevenueLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RevenueLogRepository extends JpaRepository<RevenueLog, Long> {
    // Basic CRUD provided by JpaRepository
}
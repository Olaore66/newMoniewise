package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.PayeelordVasTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayeelordVasTransactionRepository extends JpaRepository<PayeelordVasTransaction, Long> {

    Optional<PayeelordVasTransaction> findByReference(String reference);

    Optional<PayeelordVasTransaction> findByPayeelordTransactionId(String payeelordTransactionId);

    List<PayeelordVasTransaction> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);
}

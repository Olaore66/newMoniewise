package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.Withdrawal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {
    Optional<Withdrawal> findByClientReference(String clientReference);
    Optional<Withdrawal> findByProviderReference(String providerReference);
    List<Withdrawal> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);
}

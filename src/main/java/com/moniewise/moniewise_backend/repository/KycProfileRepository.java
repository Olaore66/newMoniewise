package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.KycProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KycProfileRepository extends JpaRepository<KycProfile, Long> {

    Optional<KycProfile> findByUserId(Long userId);
}
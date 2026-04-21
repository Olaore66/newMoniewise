package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.KycProfileRequestDto;
import com.moniewise.moniewise_backend.dto.KycProfileResponseDto;
import com.moniewise.moniewise_backend.entity.KycProfile;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.KycProfileRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class KycService {

    private final KycProfileRepository kycProfileRepository;
    private final UserRepository userRepository;

    public KycService(KycProfileRepository kycProfileRepository, UserRepository userRepository) {
        this.kycProfileRepository = kycProfileRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public KycProfileResponseDto createOrUpdateProfile(Long userId, KycProfileRequestDto request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        Optional<KycProfile> existing = kycProfileRepository.findByUserId(userId);
        KycProfile profile = existing.orElse(new KycProfile());
        profile.setUser(user);
        profile.setBvn(request.getBvn());
        profile.setSourceOfFunds(request.getSourceOfFunds());
        profile.setSourceOfWealth(request.getSourceOfWealth());
        KycProfile saved = kycProfileRepository.save(profile);
        return mapToResponse(saved);
    }

    @Transactional
    public void verifyBvn(Long userId, String bvn) {
        Optional<KycProfile> profileOpt = kycProfileRepository.findByUserId(userId);
        if (profileOpt.isPresent()) {
            KycProfile profile = profileOpt.get();
            if (profile.getBvn().equals(bvn)) {
                profile.setBvnVerified(true);
                kycProfileRepository.save(profile);
            }
        }
    }

    public Optional<KycProfileResponseDto> getProfile(Long userId) {
        return kycProfileRepository.findByUserId(userId).map(this::mapToResponse);
    }

    public boolean isUserVerified(Long userId) {
        return kycProfileRepository.findByUserId(userId)
                .map(profile -> profile.getKycStatus() == KycProfile.KycStatus.VERIFIED)
                .orElse(false);
    }

    @Transactional
    public void markApproved(Long userId) {
        kycProfileRepository.findByUserId(userId).ifPresent(profile -> {
            profile.setKycStatus(KycProfile.KycStatus.VERIFIED);
            kycProfileRepository.save(profile);
        });
    }

    @Transactional
    public void markRejected(Long userId, String reason) {
        kycProfileRepository.findByUserId(userId).ifPresent(profile -> {
            profile.setKycStatus(KycProfile.KycStatus.REJECTED);
            // Assuming errorMessage field or similar
            kycProfileRepository.save(profile);
        });
    }

    private KycProfileResponseDto mapToResponse(KycProfile profile) {
        return new KycProfileResponseDto(
                profile.getId(),
                profile.getBvn(),
                profile.isBvnVerified(),
                profile.getSourceOfFunds(),
                profile.getSourceOfWealth(),
                profile.getKycStatus(),
                profile.getRiskLevel(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }
}
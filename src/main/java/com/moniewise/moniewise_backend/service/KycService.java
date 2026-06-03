package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.KycProfileRequestDto;
import com.moniewise.moniewise_backend.dto.KycProfileResponseDto;
import com.moniewise.moniewise_backend.dto.PendingRegistrationData;
import com.moniewise.moniewise_backend.dto.response.BvnVerificationResultDto;
import com.moniewise.moniewise_backend.entity.KycProfile;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.KycProfileRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
public class KycService {

    private final KycProfileRepository kycProfileRepository;
    private final UserRepository userRepository;
    private final SecureWavePaymentProvider secureWavePaymentProvider;
    private final RegistrationCacheService registrationCacheService;

    public KycService(
            KycProfileRepository kycProfileRepository,
            UserRepository userRepository,
            SecureWavePaymentProvider secureWavePaymentProvider,
            RegistrationCacheService registrationCacheService
    ) {
        this.kycProfileRepository = kycProfileRepository;
        this.userRepository = userRepository;
        this.secureWavePaymentProvider = secureWavePaymentProvider;
        this.registrationCacheService = registrationCacheService;
    }

    // ── Profile CRUD ──────────────────────────────────────────────────────────

    @Transactional
    public KycProfileResponseDto createOrUpdateProfile(Long userId, KycProfileRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Optional<KycProfile> existing = kycProfileRepository.findByUserId(userId);
        KycProfile profile = existing.orElse(new KycProfile());
        profile.setUser(user);
        profile.setBvn(request.getBvn());
        profile.setSourceOfFunds(request.getSourceOfFunds());
        profile.setSourceOfWealth(request.getSourceOfWealth());
        KycProfile saved = kycProfileRepository.save(profile);
        return mapToResponse(saved);
    }

    // ── Public (pre-auth) BVN pre-verification — called during signup ─────────

    /**
     * Verifies a BVN via SecureWave <b>before</b> the user has a JWT, i.e.
     * during the signup flow.
     *
     * <ol>
     *   <li>Looks up the in-progress pending registration in Redis by {@code phone}
     *       to obtain the email address (which SecureWave requires).</li>
     *   <li>Calls the SecureWave BVN verification API.</li>
     *   <li>Writes the result back into the same Redis pending-registration key
     *       so it is available when the OTP is later verified and the user is
     *       persisted to PostgreSQL.</li>
     *   <li>Returns the structured result DTO to the client.</li>
     * </ol>
     *
     * @param phone the phone submitted during signup (step 1)
     * @param bvn   the 11-digit BVN to verify
     * @return the full {@link BvnVerificationResultDto} from SecureWave
     * @throws IllegalArgumentException if no pending registration exists for
     *                                  this phone (user must call /auth/signup first)
     * @throws RuntimeException         if SecureWave rejects the BVN
     */
    public BvnVerificationResultDto preVerifyBvn(String phone, String bvn) {
        // 1. Guard: must have a pending registration — proves phone was submitted
        //    during /auth/signup and prevents anonymous BVN enumeration
        PendingRegistrationData pending = registrationCacheService
                .findByPhone(phone)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No pending registration found for this phone. " +
                        "Please complete the signup step first."));

        String email = pending.getEmail();

        // 2. Call SecureWave
        BvnVerificationResultDto result = secureWavePaymentProvider.verifyBvn(email, phone, bvn);

        // 3. Persist BVN data back into the Redis pending record so it is
        //    available when the OTP is verified and the User is created
        pending.setBvn(bvn);
        pending.setBvnVerificationResult(result);
        registrationCacheService.save(pending); // overwrites the existing key, preserving the TTL reset

        log.info("[KYC] BVN pre-verify complete for email={} — cached in pending registration", email);
        return result;
    }

    // ── BVN Verification via SecureWave (authenticated / post-signup) ─────────

    /**
     * Calls the SecureWave BVN verification API, persists the full result to
     * {@code kyc_profiles}, marks the user's BVN on the {@code users} table,
     * and returns the structured result DTO.
     *
     * <p>Idempotent — if the BVN is already marked verified in our DB, we skip
     * the external call and return the stored data immediately.
     *
     * @param userId the ID of the authenticated user
     * @param bvn    the 11-digit BVN submitted by the client
     * @return a {@link BvnVerificationResultDto} populated with identity data
     * @throws RuntimeException if SecureWave rejects the BVN or returns an error
     */
    /**
     * Verifies the BVN via SecureWave, persists all returned identity data,
     * and marks the profile as VERIFIED.
     *
     * <p>The email and phone sent to SecureWave are taken directly from the
     * authenticated user's record (stored at signup) — the client only needs
     * to submit the BVN itself.  Users are instructed during signup to provide
     * the phone number and email that match their BVN registration.
     */
    @Transactional
    public BvnVerificationResultDto verifyBvnWithProvider(Long userId, String bvn) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
//
//        // Idempotency guard — avoid redundant API calls if already VERIFIED
//        Optional<KycProfile> existing = kycProfileRepository.findByUserId(userId);
//        if (existing.isPresent() && existing.get().isBvnVerified()
//                && KycProfile.KycStatus.VERIFIED.equals(existing.get().getKycStatus())) {
//            log.info("[KYC] BVN already verified for userId={}, skipping SecureWave call", userId);
//            return buildDtoFromProfile(existing.get());
//        }

        // Delegate to SecureWave — email + phone come from the user's record
        BvnVerificationResultDto result = secureWavePaymentProvider.verifyBvn(
                user.getEmail(), user.getPhone(), bvn);

        // Persist to kyc_profiles
//        KycProfile profile = existing.orElse(new KycProfile());
        KycProfile profile = new KycProfile();

        profile.setUser(user);
        profile.setBvn(bvn);
        profile.setBvnVerified(true);
        profile.setKycStatus(KycProfile.KycStatus.VERIFIED);

        // Top-level identity fields
        profile.setNameOnCard(result.getNameOnCard());
        profile.setEnrolmentBank(result.getEnrolmentBank());
        profile.setEnrolmentBranch(result.getEnrolmentBranch());
        profile.setFormattedRegistrationDate(result.getFormattedRegistrationDate());
        profile.setLevelOfAccount(result.getLevelOfAccount());
        profile.setNin(result.getNin());
        profile.setWatchlisted(result.getWatchlisted());
        profile.setBvnVerificationStatus(result.getVerificationStatus());

        // personal_info fields
        profile.setFirstName(result.getFirstName());
        profile.setMiddleName(result.getMiddleName());
        profile.setLastName(result.getLastName());
        profile.setGender(result.getGender());
        profile.setDateOfBirth(result.getDateOfBirth());
        profile.setStateOfOrigin(result.getStateOfOrigin());
        profile.setLgaOfOrigin(result.getLgaOfOrigin());
        profile.setNationality(result.getNationality());
        profile.setMaritalStatus(result.getMaritalStatus());

        // residential_info fields
        profile.setStateOfResidence(result.getStateOfResidence());
        profile.setLgaOfResidence(result.getLgaOfResidence());
        profile.setResidentialAddress(result.getResidentialAddress());

        kycProfileRepository.save(profile);

        // Mirror the BVN onto the users table so createVirtualAccount() can use it
        user.setBvn(bvn);
        userRepository.save(user);

        log.info("[KYC] BVN verification persisted for userId={}", userId);

        // Stamp internal status onto the result DTO before returning
        result.setKycStatus(KycProfile.KycStatus.VERIFIED);
        result.setBvnVerified(true);
        return result;
    }

    // ── Other helpers ─────────────────────────────────────────────────────────

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
            kycProfileRepository.save(profile);
        });
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

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

    /** Rebuilds a {@link BvnVerificationResultDto} from an already-persisted profile. */
    private BvnVerificationResultDto buildDtoFromProfile(KycProfile p) {
        BvnVerificationResultDto dto = new BvnVerificationResultDto();
        dto.setBvnNumber(p.getBvn());
        dto.setNameOnCard(p.getNameOnCard());
        dto.setEnrolmentBank(p.getEnrolmentBank());
        dto.setEnrolmentBranch(p.getEnrolmentBranch());
        dto.setFormattedRegistrationDate(p.getFormattedRegistrationDate());
        dto.setLevelOfAccount(p.getLevelOfAccount());
        dto.setNin(p.getNin());
        dto.setWatchlisted(p.getWatchlisted());
        dto.setVerificationStatus(p.getBvnVerificationStatus());
        dto.setFirstName(p.getFirstName());
        dto.setMiddleName(p.getMiddleName());
        dto.setLastName(p.getLastName());
        dto.setGender(p.getGender());
        dto.setDateOfBirth(p.getDateOfBirth());
        dto.setStateOfOrigin(p.getStateOfOrigin());
        dto.setLgaOfOrigin(p.getLgaOfOrigin());
        dto.setNationality(p.getNationality());
        dto.setMaritalStatus(p.getMaritalStatus());
        dto.setStateOfResidence(p.getStateOfResidence());
        dto.setLgaOfResidence(p.getLgaOfResidence());
        dto.setResidentialAddress(p.getResidentialAddress());
        dto.setKycStatus(p.getKycStatus());
        dto.setBvnVerified(p.isBvnVerified());
        return dto;
    }
}

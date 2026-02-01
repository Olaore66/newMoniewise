package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.response.UserSummaryResponse;
import com.moniewise.moniewise_backend.entity.Beneficiary;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.BeneficiaryRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BeneficiaryService {
    private final BeneficiaryRepository beneficiaryRepository;
    private final UserService userService;
    private final UserRepository userRepository;

    // =========================================================================
    // 1. ADD BENEFICIARY (Now Null-Safe)
    // =========================================================================
    public void addBeneficiary(Long currentUserId, String targetEmail, String alias) {
        User target = userService.findByEmail(targetEmail);

        if (currentUserId.equals(target.getId())) {
            throw new IllegalArgumentException("You cannot save yourself as a beneficiary.");
        }

        if (beneficiaryRepository.existsByUserIdAndBeneficiaryUserId(currentUserId, target.getId())) {
            throw new IllegalStateException("User is already in your beneficiary list.");
        }

        User currentUser = userRepository.findById(currentUserId).orElseThrow();

        // FIX: Use getSafeName() instead of assuming target.getName() works
        String safeAlias = (alias != null && !alias.isEmpty())
                ? alias
                : getSafeName(target);

        Beneficiary beneficiary = Beneficiary.builder()
                .user(currentUser)
                .beneficiaryUser(target)
                .alias(safeAlias)
                .build();

        beneficiaryRepository.save(beneficiary);
    }

    // =========================================================================
    // 2. GET BENEFICIARIES (Fixed the Crash)
    // =========================================================================
    public List<UserSummaryResponse> getMyBeneficiaries(Long currentUserId) {
        return beneficiaryRepository.findByUserId(currentUserId)
                .stream()
                .map(b -> {
                    User targetUser = b.getBeneficiaryUser();
                    String handle = "@" + targetUser.getEmail().split("@")[0];

                    // LOGIC: Use Alias if set, otherwise use Safe Name (never null)
                    String displayName = (b.getAlias() != null && !b.getAlias().isEmpty())
                            ? b.getAlias()
                            : getSafeName(targetUser);

                    return new UserSummaryResponse(
                            displayName,
                            handle,
                            targetUser.getProfileImageUrl(),
                            targetUser.getEmail()
                    );
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 3. HELPER: SAFE NAME EXTRACTION (Copy this logic everywhere!)
    // =========================================================================
    private String getSafeName(User user) {
        // 1. Try Profile Data
        if (user.getProfileData() != null) {
            Object nameObj = user.getProfileData().getOrDefault("fullName", user.getProfileData().get("name"));
            if (nameObj != null && !nameObj.toString().trim().isEmpty()) {
                return nameObj.toString();
            }
        }

        // 2. Fallback to Email Handle (e.g. "olaore66@..." -> "Olaore66")
        if (user.getEmail() != null) {
            String handle = user.getEmail().split("@")[0];
            // Capitalize first letter
            return handle.substring(0, 1).toUpperCase() + handle.substring(1);
        }

        return "Unknown User";
    }
}
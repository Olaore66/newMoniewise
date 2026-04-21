package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.KycProfileRequestDto;
import com.moniewise.moniewise_backend.dto.KycProfileResponseDto;
import com.moniewise.moniewise_backend.service.KycService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Optional;

@RestController
@RequestMapping("/api/kyc")
public class KycController {

    private final KycService kycService;
    private final UserService userService;

    public KycController(KycService kycService, UserService userService) {
        this.kycService = kycService;
        this.userService = userService;
    }

    @GetMapping("/status")
    public ResponseEntity<Boolean> getKycStatus(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userService.getRequiredUserIdByEmail(userDetails.getUsername());
        boolean verified = kycService.isUserVerified(userId);
        return ResponseEntity.ok(verified);
    }

    @PostMapping("/profile")
    public ResponseEntity<KycProfileResponseDto> createOrUpdateProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody KycProfileRequestDto request) {
        Long userId = userService.getRequiredUserIdByEmail(userDetails.getUsername());
        KycProfileResponseDto response = kycService.createOrUpdateProfile(userId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/bvn/verify")
    public ResponseEntity<Void> verifyBvn(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam String bvn) {
        Long userId = userService.getRequiredUserIdByEmail(userDetails.getUsername());
        kycService.verifyBvn(userId, bvn);
        return ResponseEntity.ok().build();
    }
}

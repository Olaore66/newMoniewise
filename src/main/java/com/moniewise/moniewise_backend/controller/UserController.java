package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.*;
import com.moniewise.moniewise_backend.dto.response.*;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.OtpService;
import com.moniewise.moniewise_backend.service.UserService;
import lombok.RequiredArgsConstructor; // ✅ Added for cleaner code
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor // ✅ Autowires everything automatically
public class UserController {

    private final UserService userService;
    private final OtpService otpService;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;

    // =========================================================================
    // 1. GET CURRENT USER (Updated to ensure Profile Image is included)
    // =========================================================================
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Wallet wallet = walletRepository.findByUser(user).orElse(null);

        // ✅ UserResponse MUST include 'profileImageUrl' in its constructor/fields
        return ResponseEntity.ok(new UserResponse(user, wallet));
    }

    // =========================================================================
    // 2. UPLOAD PROFILE IMAGE (Returns New URL Immediately)
    // =========================================================================
    @PostMapping("/image")
    public ResponseEntity<?> uploadProfileImage(
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        // 1. Get the User
        String email = authentication.getName();
        User user = userService.findByEmail(email);

        // 2. Upload to Cloud & Save to DB
        // (Your UserService handles the logic and returns the signed URL)
        String imageUrl = userService.uploadProfileImage(user.getId(), file);

        // 3. Return the URL so the Frontend can update state instantly
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Profile image updated successfully",
                "imageUrl", imageUrl
        ));
    }

    // =========================================================================
    // 3. GET PROFILE IMAGE ONLY (Specific Endpoint)
    // =========================================================================
    @GetMapping("/image")
    public ResponseEntity<?> getProfileImage(Authentication authentication) {
        String email = authentication.getName();
        User user = userService.findByEmail(email);

        String imageUrl = user.getProfileImageUrl();

        if (imageUrl == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "No profile image set"));
        }

        return ResponseEntity.ok(Map.of("imageUrl", imageUrl));
    }

    // =========================================================================
    // 4. DELETE IMAGE
    // =========================================================================
    @DeleteMapping("/image")
    public ResponseEntity<?> deleteProfileImage(Authentication authentication) {
        String email = authentication.getName();
        User user = userService.findByEmail(email);

        userService.deleteProfileImage(user.getId());

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Profile image removed"
        ));
    }

    // =========================================================================
    // 5. OTHER EXISTING ENDPOINTS (Kept as is)
    // =========================================================================

    @PostMapping("/otp/generate")
    public ResponseEntity<OtpResponse> generateOtp(@Valid @RequestBody OtpGenerateRequest request) {
        User user = userRepository.findByEmail(request.getEmailOrPhone())
                .orElseGet(() -> userRepository.findByPhone(request.getEmailOrPhone())
                        .orElseThrow(() -> new RuntimeException("User not found")));

        String otpCode = otpService.generateOtp(user.getId());
        return ResponseEntity.ok(new OtpResponse("OTP generated: " + otpCode));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<?> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        try {
            User user = userRepository.findByEmail(request.getEmailOrPhone())
                    .orElseGet(() -> userRepository.findByPhone(request.getEmailOrPhone())
                            .orElseThrow(() -> new RuntimeException("User not found")));

            if (!otpService.verifyOtp(user.getId(), request.getOtpCode())) {
                // Using RuntimeException here for simplicity based on your snippet
                throw new RuntimeException("Invalid OTP");
            }

            user.setVerified(true);
            userRepository.save(user);
            otpService.clearOtp(user.getId());

            return ResponseEntity.ok(Map.of("message", "OTP verified successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestBody ProfileRequest request, Authentication authentication) {
        try {
            String email = authentication.getName();
            userService.updateProfile(email, request);
            return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/tnc")
    public ResponseEntity<?> acceptTnc(Authentication authentication, @RequestBody TncRequest request) {
        userService.acceptTnc(authentication.getName(), request.isAccepted());
        return ResponseEntity.ok(Map.of("message", "TnC " + (request.isAccepted() ? "accepted" : "rejected")));
    }

    @GetMapping("/search")
    public ResponseEntity<List<UserSummaryResponse>> searchUsers(
            @RequestParam String query,
            @AuthenticationPrincipal String email // Note: Ensure your security config populates this
    ) {
        // Fallback if AuthenticationPrincipal is null (depends on config)
        if (email == null) {
            email = SecurityContextHolder.getContext().getAuthentication().getName();
        }
        return ResponseEntity.ok(userService.searchUsers(query, email));
    }

    @PostMapping("/fcm-token")
    public ResponseEntity<?> updateFcmToken(@RequestBody Map<String, String> payload, Authentication authentication) {
        String token = payload.get("token");
        if (token == null || token.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Token is required"));
        }
        String email = authentication.getName();
        userService.updateFcmToken(email, token);
        return ResponseEntity.ok(Map.of("message", "FCM token updated successfully"));
    }
}
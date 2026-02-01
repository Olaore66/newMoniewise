package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.*;
import com.moniewise.moniewise_backend.dto.response.OtpResponse;
import com.moniewise.moniewise_backend.dto.response.OtpVerifyRequest;
import com.moniewise.moniewise_backend.dto.response.UserResponse;
import com.moniewise.moniewise_backend.dto.response.UserSummaryResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.exception.OtpVerificationException;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.OtpService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private OtpService otpService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

//    @GetMapping("/me")
//    public ResponseEntity<UserDTO> getCurrentUser() {
//        String email = SecurityContextHolder.getContext().getAuthentication().getName();
//
//        User user = userRepository.findByEmail(email)
//                .orElseThrow(() -> new RuntimeException("User not found"));
//        // Fetch wallet (might be null)
//        Wallet wallet = walletRepository.findByUser(user).orElse(null);
//        return ResponseEntity.ok(new UserDTO(user, wallet));
//    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() { // ✅ Return UserResponse
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 1. Fetch Wallet (Explicitly handle null)
        Wallet wallet = walletRepository.findByUser(user).orElse(null);

        // 2. Return Response (The Constructor handles the logic)
        return ResponseEntity.ok(new UserResponse(user, wallet));
    }
    @PostMapping("/otp/generate")
    public ResponseEntity<OtpResponse> generateOtp(@Valid @RequestBody OtpGenerateRequest request) {
        // Find user by email/phone
        User user = userRepository.findByEmail(request.getEmailOrPhone())
                .orElseGet(() -> userRepository.findByPhone(request.getEmailOrPhone())
                        .orElseThrow(() -> new RuntimeException("User not found")));
        // TODO: For future Twilio integration
        // Send OTP via Twilio SMS API: POST /v1/Messages
        // TwilioClient.sendSms(user.getPhone(), "Your Moniewise OTP is: " + otpCode);

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
                throw new OtpVerificationException("Invalid OTP");
            }

            user.setVerified(true);
            userRepository.save(user);
            otpService.clearOtp(user.getId());

            return ResponseEntity.ok(Map.of("message", "OTP verified successfully"));
        } catch (OtpVerificationException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // UserController
    @PostMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestBody ProfileRequest request, Authentication authentication) {
        try {
            String email = authentication.getName();
            User updatedUser = userService.updateProfile(email, request);
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
            @AuthenticationPrincipal String email
    ) {
        return ResponseEntity.ok(userService.searchUsers(query, email));
    }

    // Inside UserController.java

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

//    ============ PROFILE PICTURE ==============

    // 1. UPLOAD or UPDATE IMAGE (POST)
    // Key: "file", Value: [Select Image]
    @PostMapping("/image")
    public ResponseEntity<?> uploadProfileImage(
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        String email = authentication.getName();
        // Assuming you have a helper to get ID from email, or fetch user first
        // For now, let's fetch user to get ID
        // (Optimized: Your UserDetails might already have the ID)
        User user = userService.findByEmail(email);

        userService.uploadProfileImage(user.getId(), file);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Profile image updated successfully"
        ));
    }

    // 2. VIEW IMAGE (GET)
    // This URL goes into your Flutter NetworkImage()
    @GetMapping("/image")
    public ResponseEntity<byte[]> getProfileImage(Authentication authentication) {
        String email = authentication.getName();
        User user = userService.findByEmail(email);

        byte[] imageData = userService.getProfileImage(user.getId());

        if (imageData == null || imageData.length == 0) {
            return ResponseEntity.notFound().build(); // Return 404 if no image
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG) // We assume JPEG/PNG. Browsers handle both fine.
                .body(imageData);
    }

    // 3. DELETE IMAGE (DELETE)
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

}
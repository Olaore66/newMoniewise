package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.OtpGenerateRequest;
import com.moniewise.moniewise_backend.dto.request.OtpRequest;
import com.moniewise.moniewise_backend.dto.request.ProfileRequest;
import com.moniewise.moniewise_backend.dto.request.UserDTO;
import com.moniewise.moniewise_backend.dto.response.OtpResponse;
import com.moniewise.moniewise_backend.dto.response.OtpVerifyRequest;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.exception.OtpVerificationException;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.service.OtpService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
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

    @GetMapping("/me")
    public ResponseEntity<UserDTO> getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(new UserDTO(user));
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
            return ResponseEntity.ok(Map.of("message", "Profile updated" + updatedUser) );
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }


    // UserController
    @PatchMapping("/tnc")
    public ResponseEntity<?> acceptTnc(Authentication authentication) {
        userService.acceptTnc(authentication.getName());
        return ResponseEntity.ok(Map.of("message", "TnC accepted"));
    }

}
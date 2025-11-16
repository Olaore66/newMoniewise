package com.moniewise.moniewise_backend.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.request.AuthRequest;
import com.moniewise.moniewise_backend.dto.response.AuthResponse;
import com.moniewise.moniewise_backend.dto.response.LogoutResponse;
import com.moniewise.moniewise_backend.dto.response.SignupResponse;
import com.moniewise.moniewise_backend.entity.PasswordResetToken;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.security.JwtUtil;
import com.moniewise.moniewise_backend.service.EmailService;
import com.moniewise.moniewise_backend.service.PasswordResetService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import javax.mail.MessagingException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordResetService resetService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private UserRepository userRepository;


    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody AuthRequest request) {
        try {
            Role role = request.getRole() != null ? Role.valueOf(request.getRole()) : Role.USER;
            SignupResponse signupResponse = userService.signup(
                    request.getEmail(), request.getPhone(), request.getPassword(), role
            );
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "User registered successfully. Please login.");
            response.put("data", signupResponse);
            return ResponseEntity.status(201).body(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request) {
        try {
            User user = userService.login(request.getEmailOrPhone(), request.getPassword());
            UserDetails userDetails = userService.loadUserByUsername(user.getEmail());
            String token = jwtUtil.generateToken(userDetails);

//            boolean needsProfileUpdate = (user.getProfileData() == null);
            boolean needsProfileUpdate = true;

            if (user.getProfileData() != null && !user.getProfileData().isEmpty()) {
                needsProfileUpdate = user.getProfileData().values().stream()
                        .allMatch(value -> value == null || value.toString().isBlank());
            }

            // Return both token and profile completion flag
            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "needsProfileUpdate", needsProfileUpdate
            ));
        } catch (RuntimeException e) {
            if ("OTP verification required".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "OTP verification required"));
            }

            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/oauth2/success")
    public ResponseEntity<?> oauth2Success(@AuthenticationPrincipal OAuth2User oauth2User) {
        try {
            String email = oauth2User.getAttribute("email");
            if (email == null) {
                throw new RuntimeException("Email not provided by OAuth2 provider");
            }
            User user = userService.findOrCreateOAuthUser(email);
            UserDetails userDetails = userService.loadUserByUsername(email); // Get UserDetails
            String token = jwtUtil.generateToken(userDetails); // Pass UserDetails
            return ResponseEntity.ok(new AuthResponse(token));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }

    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new RuntimeException("Invalid or missing token");
            }
            return ResponseEntity.ok(new LogoutResponse("Logged out successfully. Please discard your token."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestHeader("Authorization") String authHeader) {
        System.out.println("Refresh endpoint hit with header: " + authHeader);
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new RuntimeException("Invalid or missing token");
            }
            String oldToken = authHeader.substring(7);
            System.out.println("Old token: " + oldToken);
            if (jwtUtil.isTokenExpired(oldToken)) {
                throw new RuntimeException("Token has expired—please log in again");
            }
            String email = jwtUtil.extractEmail(oldToken);
            UserDetails userDetails = userService.loadUserByUsername(email);
            String newToken = jwtUtil.generateToken(userDetails);
            System.out.println("New token: " + newToken);
            return ResponseEntity.ok(new AuthResponse(newToken));
        } catch (Exception e) {
            System.out.println("Refresh error: " + e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

//    @PostMapping("/forgot-password")
//    public  ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body) {
//        String email = body.get("email");
//        PasswordResetToken token = resetService.createResetToken(email);
//        token.getToken(); //send via email
//        Map<String, String> response = new HashMap<>();
//        response.put("message", "Reset link sent");
//        response.put("token", token.getToken());
//        return ResponseEntity.ok(response);
//    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");


        // ✅ Check if user exists here
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "No account found for this email.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }

        // Step 1: Generate the reset token
        PasswordResetToken token = resetService.createResetToken(email);

        // Step 2: Build the reset link//  please replace the user with the server link
//        String resetLink = "http://10.40.246.184:9000/reset-password?token=" + token.getToken();
        String resetLink = "moniewise://reset-password?token=" + token.getToken();


        // Step 3: Send email
        try {
            emailService.sendPasswordResetEmail(email, email, resetLink); // You can replace second `email` with user full name if you have it
        } catch (MessagingException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to send reset email.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }

        // Step 4: Return JSON response
        Map<String, String> response = new HashMap<>();
        response.put("message", "Reset link sent to email.");
        response.put("token", token.getToken()); // For dev/testing only. Remove in production.
        return ResponseEntity.ok(response);
    }


    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String newPassword = body.get("password");

        if (!resetService.isValidToken(token)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid or expired token");
        }

        // Assume updateUserPassword updates user’s password by email associated with token
        PasswordResetToken reset = resetService.tokenRepository.findByToken(token).orElseThrow();
//        userService.updateUserPassword(reset.getEmail(), newPassword);
        userService.updateUserPassword(token, newPassword);

        resetService.markTokenAsUsed(token);

        return ResponseEntity.ok("Password reset successful");
    }
}
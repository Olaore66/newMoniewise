package com.moniewise.moniewise_backend.controller;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.moniewise.moniewise_backend.dto.request.AuthRequest;
import com.moniewise.moniewise_backend.dto.request.ChangePasswordRequest;
import com.moniewise.moniewise_backend.dto.response.AuthResponse;
import com.moniewise.moniewise_backend.dto.response.LogoutResponse;
import com.moniewise.moniewise_backend.dto.response.SignupResponse;
import com.moniewise.moniewise_backend.entity.PasswordResetToken;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.security.JwtUtil;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.AuthSessionService;
import com.moniewise.moniewise_backend.service.EmailService;
import com.moniewise.moniewise_backend.service.NotificationService;
import com.moniewise.moniewise_backend.service.PasswordResetService;
import com.moniewise.moniewise_backend.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Autowired private UserService userService;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordResetService resetService;
    @Autowired private EmailService emailService;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private AuthSessionService authSessionService;
    @Autowired private AbuseProtectionService abuseProtectionService;

    @Value("${spring.security.oauth2.client.registration.google.client-id:}")
    private String googleClientId;

    @Value("${SESSION_IDLE_TIMEOUT_SECONDS:${session.idle-timeout-seconds:240}}")
    private String idleTimeoutSecondsRaw;

    @Value("${SESSION_WARNING_LEAD_SECONDS:${session.warning-lead-seconds:60}}")
    private String warningLeadSecondsRaw;

    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody AuthRequest request) {
        try {
            Role role = Role.USER;
            SignupResponse signupResponse = userService.signup(request.getEmail(), request.getPhone(), request.getPassword());
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
    public ResponseEntity<?> login(@RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(request.getEmailOrPhone(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.LOGIN, throttleKey);
        try {
            User user = userService.login(request.getEmailOrPhone(), request.getPassword());
            UserDetails userDetails = userService.loadUserByUsername(user.getEmail());
            String newSessionId = authSessionService.createSession(user);
            String token = jwtUtil.generateToken(userDetails, newSessionId);
            abuseProtectionService.recordSuccess(AbuseProtectionService.LOGIN, throttleKey);
            boolean needsProfileUpdate = needsProfileUpdate(user);
            return ResponseEntity.ok(buildAuthPayload(token, needsProfileUpdate));
        } catch (RuntimeException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.LOGIN, throttleKey);
            if ("OTP verification required".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "OTP verification required"));
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid credentials"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String authHeader) {
        try {
            String token = extractBearerToken(authHeader);
            String sessionId = jwtUtil.extractSessionId(token);
            if (sessionId == null || sessionId.isBlank()) {
                throw new RuntimeException("Invalid session");
            }
            authSessionService.revokeSession(sessionId);
            return ResponseEntity.ok(new LogoutResponse("Logged out successfully. Please discard your token."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Unable to logout with the provided token"));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestHeader("Authorization") String authHeader) {
        try {
            String oldToken = extractBearerToken(authHeader);
            if (jwtUtil.isTokenExpired(oldToken)) {
                throw new RuntimeException("Token expired");
            }
            String email = jwtUtil.extractEmail(oldToken);
            String oldSessionId = jwtUtil.extractSessionId(oldToken);
            if (!authSessionService.isSessionActive(email, oldSessionId)) {
                throw new RuntimeException("Session expired");
            }
            UserDetails userDetails = userService.loadUserByUsername(email);
            String newToken = jwtUtil.generateToken(userDetails, oldSessionId);
            return ResponseEntity.ok(new AuthResponse(
                    newToken,
                    jwtUtil.extractExpiration(newToken).getTime(),
                    getIdleTimeoutSeconds(),
                    getWarningLeadSeconds()
            ));
        } catch (Exception e) {
            logger.warn("Token refresh failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please log in again"));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        String email = body.get("email");
        String throttleKey = abuseProtectionService.buildKey(email, httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.FORGOT_PASSWORD, throttleKey);
        try {
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isPresent() && !userOpt.get().isDeleted()) {
                User user = userOpt.get();
                PasswordResetToken token = resetService.createResetToken(email);
                String name = user.getName() != null ? user.getName() : "User";
                notificationService.sendPasswordResetOtp(email, name, token.getToken());
            }
            abuseProtectionService.recordSuccess(AbuseProtectionService.FORGOT_PASSWORD, throttleKey);
            return ResponseEntity.ok(Map.of("message", "If an account exists, a reset code has been sent."));
        } catch (Exception e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.FORGOT_PASSWORD, throttleKey);
            logger.error("Forgot password flow failed for {}", email, e);
            return ResponseEntity.ok(Map.of("message", "If an account exists, a reset code has been sent."));
        }
    }

    @PostMapping("/verify-reset-otp")
    public ResponseEntity<?> verifyResetOtp(@RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        String email = body.get("email");
        String otp = body.get("otp");
        String throttleKey = abuseProtectionService.buildKey(email, httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.RESET_VERIFY, throttleKey);
        boolean isValid = resetService.isValidToken(email, otp);
        if (isValid) {
            abuseProtectionService.recordSuccess(AbuseProtectionService.RESET_VERIFY, throttleKey);
            return ResponseEntity.ok(Map.of("status", "success", "message", "OTP verified", "email", email));
        }
        abuseProtectionService.recordFailure(AbuseProtectionService.RESET_VERIFY, throttleKey);
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired OTP"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String token = body.get("token");
        String newPassword = body.get("password");
        if (!resetService.isValidToken(email, token)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid or expired reset session"));
        }
        resetService.updateUserPassword(email, token, newPassword);
        resetService.markTokenAsUsed(email, token);
        return ResponseEntity.ok(Map.of("message", "Password reset successful"));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody ChangePasswordRequest request
    ) {
        try {
            userService.changePassword(
                    userDetails.getUsername(),
                    request.getCurrentPassword(),
                    request.getNewPassword(),
                    request.getConfirmNewPassword()
            );
            return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@RequestBody Map<String, String> payload, HttpServletRequest httpRequest) {
        String idTokenString = payload.get("token");
        String throttleKey = abuseProtectionService.buildKey("google", httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.GOOGLE_LOGIN, throttleKey);
        if (idTokenString == null || idTokenString.isBlank()) {
            abuseProtectionService.recordFailure(AbuseProtectionService.GOOGLE_LOGIN, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", "Token is required"));
        }
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();
            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken == null) {
                abuseProtectionService.recordFailure(AbuseProtectionService.GOOGLE_LOGIN, throttleKey);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid Google token"));
            }
            GoogleIdToken.Payload googlePayload = idToken.getPayload();
            String email = googlePayload.getEmail();
            String name = (String) googlePayload.get("name");
            User user = userService.findOrCreateOAuthUser(email, name);
            abuseProtectionService.recordSuccess(AbuseProtectionService.GOOGLE_LOGIN, throttleKey);
            return generateAuthResponse(user);
        } catch (GeneralSecurityException | IOException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.GOOGLE_LOGIN, throttleKey);
            logger.error("Google authentication failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Google authentication failed"));
        }
    }

    private ResponseEntity<?> generateAuthResponse(User user) {
        try {
            UserDetails userDetails = userService.loadUserByUsername(user.getEmail());
            String newSessionId = authSessionService.createSession(user);
            String token = jwtUtil.generateToken(userDetails, newSessionId);
            boolean needsProfileUpdate = needsProfileUpdate(user);
            return ResponseEntity.ok(buildAuthPayload(token, needsProfileUpdate));
        } catch (Exception e) {
            logger.error("Failed to generate auth response for {}", user.getEmail(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Login generation failed"));
        }
    }

    private Map<String, Object> buildAuthPayload(String token, boolean needsProfileUpdate) {
        Date expiration = jwtUtil.extractExpiration(token);
        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("needsProfileUpdate", needsProfileUpdate);
        response.put("expiresAt", expiration.getTime());
        response.put("idleTimeoutSeconds", getIdleTimeoutSeconds());
        response.put("warningLeadSeconds", getWarningLeadSeconds());
        return response;
    }

    private long getIdleTimeoutSeconds() {
        return parseLongOrDefault(idleTimeoutSecondsRaw, 240L);
    }

    private long getWarningLeadSeconds() {
        return parseLongOrDefault(warningLeadSecondsRaw, 60L);
    }

    private long parseLongOrDefault(String value, long fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            logger.warn("Invalid numeric session config '{}', falling back to {}", value, fallback);
            return fallback;
        }
    }

    private boolean needsProfileUpdate(User user) {
        Map<String, Object> profileData = user.getProfileData();
        String firstName = readProfileValue(profileData, "firstName");
        String lastName = readProfileValue(profileData, "lastName");
        return isBlank(user.getPhone()) || isBlank(user.getBvn()) || isBlank(firstName) || isBlank(lastName);
    }

    private String readProfileValue(Map<String, Object> profileData, String key) {
        if (profileData == null) return null;
        Object value = profileData.get(key);
        return value != null ? value.toString() : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String extractBearerToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Invalid or missing token");
        }
        return authHeader.substring(7);
    }

    @DeleteMapping("/delete")
    public ResponseEntity<?> deleteMyAccount(@AuthenticationPrincipal UserDetails userDetails) {
        String email = userDetails.getUsername();
        userService.deleteUserAccount(email);
        return ResponseEntity.ok(Collections.singletonMap("message", "Account deactivated successfully. You can reactivate it by logging in with Google."));
    }
}


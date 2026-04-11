package com.moniewise.moniewise_backend.controller;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
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
import com.moniewise.moniewise_backend.service.NotificationService;
import com.moniewise.moniewise_backend.service.PasswordResetService;
import com.moniewise.moniewise_backend.service.AuthSessionService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.*;

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

    @Autowired
    private NotificationService notificationService; // Add this

    @Autowired
    private AuthSessionService authSessionService;

    // 👇 ADD THIS SECTION HERE
    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String googleClientId;

    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody AuthRequest request) {
        try {
            // Role role = request.getRole() != null ? ... ❌ DELETE THIS
            Role role = Role.USER; // ✅ FORCE THIS

            SignupResponse signupResponse = userService.signup(
                    request.getEmail(), request.getPhone(), request.getPassword()
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

            String newSessionId = authSessionService.createSession(user);
            String token = jwtUtil.generateToken(userDetails, newSessionId);

            boolean needsProfileUpdate = needsProfileUpdate(user);

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

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new RuntimeException("Invalid or missing token");
            }
            String token = authHeader.substring(7);
            String sessionId = jwtUtil.extractSessionId(token);
            if (sessionId == null || sessionId.isBlank()) {
                throw new RuntimeException("Invalid session");
            }
            authSessionService.revokeSession(sessionId);
            return ResponseEntity.ok(new LogoutResponse("Logged out successfully. Please discard your token."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
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

            // 1. Check if expired
            if (jwtUtil.isTokenExpired(oldToken)) {
                throw new RuntimeException("Token has expired—please log in again");
            }

            // 2. Extract Data
            String email = jwtUtil.extractEmail(oldToken);
            String oldSessionId = jwtUtil.extractSessionId(oldToken); // <--- Get Session ID from old token

            if (!authSessionService.isSessionActive(email, oldSessionId)) {
                throw new RuntimeException("Session expired: You have logged in on another device.");
            }

            UserDetails userDetails = userService.loadUserByUsername(email);
            String newToken = jwtUtil.generateToken(userDetails, oldSessionId);

            return ResponseEntity.ok(new AuthResponse(newToken));
        } catch (Exception e) {
            System.out.println("Refresh error: " + e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");

        // 1. Check User
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty() || userOpt.get().isDeleted()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No account found for this email."));
        }
        User user = userOpt.get();

        // 2. Generate 6-Digit OTP
        PasswordResetToken token = resetService.createResetToken(email);

        // 3. Send OTP Email (Using NotificationService)
        String name = (user.getName() != null) ? user.getName() : "User";
        notificationService.sendPasswordResetOtp(email, name, token.getToken());

        // 4. Return Success
        return ResponseEntity.ok(Map.of("message", "OTP sent to email."));
    }

    // ✅ NEW ENDPOINT: Step 2 - Verify OTP (Called by Flutter App)
    @PostMapping("/verify-reset-otp")
    public ResponseEntity<?> verifyResetOtp(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String otp = body.get("otp");

        boolean isValid = resetService.isValidToken(email, otp);

        if (isValid) {
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "OTP Verified",
                    "token", otp,
                    "email", email
            ));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired OTP"));
        }
    }

    // ✅ REFACTORED: Step 3 - Change Password
    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String token = body.get("token"); // This is the OTP string (e.g., "123456")
        String newPassword = body.get("password");

        if (!resetService.isValidToken(email, token)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid or expired session");
        }

        // Update Password
        resetService.updateUserPassword(email, token, newPassword);

        // Invalidate OTP
        resetService.markTokenAsUsed(email, token);

        return ResponseEntity.ok("Password reset successful");
    }

    // 👇 NEW ENDPOINT: Handle Google Sign-In from Flutter/Frontend
    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@RequestBody Map<String, String> payload) {
        String idTokenString = payload.get("token");

        System.out.println("🔥 [DEBUG] Google Login Request Received");
        System.out.println("🔹 Received Token (Start): " + (idTokenString != null ? idTokenString.substring(0, 15) + "..." : "NULL"));
        System.out.println("🔹 Backend Expects Client ID: " + googleClientId); // 👈 CHECK THIS LOG!

        if (idTokenString == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Token is required"));
        }

        try {
            // 1. Verify the Token with Google
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();

            GoogleIdToken idToken = verifier.verify(idTokenString);

            if (idToken != null) {
                System.out.println("✅ [DEBUG] Token Verified Successfully!");
                GoogleIdToken.Payload googlePayload = idToken.getPayload();
                String email = googlePayload.getEmail();
                String name = (String) googlePayload.get("name");


                System.out.println("🔹 Backend googlePayload: " + googlePayload);
                System.out.println("🔹 Backend email: " + email);
                System.out.println("🔹 Backend name: " + name);



                // 2. Check DB: Login if exists, Register if new
                // You might need to add a 'findOrCreateGoogleUser' method to UserService,
                // or use your existing logic here.
                User user = userService.findOrCreateOAuthUser(email, name);
                System.out.println("🔹 Backend user: " + user);

                // 3. Generate Your JWT
                return generateAuthResponse(user);
            } else {
                System.out.println("❌ [DEBUG] Verification FAILED: Token returned null (Audience Mismatch?)");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid Google Token");
            }

        } catch (GeneralSecurityException | IOException e) {
            System.out.println("❌ [DEBUG] Exception: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Google Auth Failed: " + e.getMessage());
        }
    }

    // In AuthController.java

    private ResponseEntity<?> generateAuthResponse(User user) {
        try {
            // 🔍 Debugging: Print user details before the crash
            System.out.println("⚡ Generating Response for: " + user.getEmail());

            UserDetails userDetails = userService.loadUserByUsername(user.getEmail());
            String newSessionId = authSessionService.createSession(user);
            String token = jwtUtil.generateToken(userDetails, newSessionId);

            boolean needsProfileUpdate = needsProfileUpdate(user);

            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "needsProfileUpdate", needsProfileUpdate
            ));

        } catch (Exception e) {
            // 🚨 CATCH THE CRASH
            System.out.println("❌ CRASH in generateAuthResponse: " + e.getMessage());
            e.printStackTrace(); // This will print the REAL error to your console
            return ResponseEntity.status(500).body("Login generation failed: " + e.getMessage());
        }
    }
    // 👇 Helper method to avoid duplicating Session/JWT logic for Login & Google
//    private ResponseEntity<?> generateAuthResponse(User user) {
//        UserDetails userDetails = userService.loadUserByUsername(user.getEmail());
//
//        // 1. Generate Session ID
//        String newSessionId = UUID.randomUUID().toString();
//
//        // 2. Update User Session
//        user.setCurrentSessionId(newSessionId);
//        userRepository.save(user);
//
//        // 3. Generate Token
//        String token = jwtUtil.generateToken(userDetails, newSessionId);
//
//        // 4. Check Profile Status
//        boolean needsProfileUpdate = true;
//        if (user.getProfileData() != null && !user.getProfileData().isEmpty()) {
//            needsProfileUpdate = user.getProfileData().values().stream()
//                    .allMatch(value -> value == null || value.toString().isBlank());
//        }
//        System.out.println("🔹 Backend in generateAuthResponse token: " + token);
//        System.out.println("🔹 Backend in generateAuthResponse needsProfileUpdate: " + needsProfileUpdate);
//
//        return ResponseEntity.ok(Map.of(
//                "token", token,
//                "needsProfileUpdate", needsProfileUpdate
//        ));
//    }

    private boolean needsProfileUpdate(User user) {
        Map<String, Object> profileData = user.getProfileData();
        String firstName = readProfileValue(profileData, "firstName");
        String lastName = readProfileValue(profileData, "lastName");

        return isBlank(user.getPhone())
                || isBlank(user.getBvn())
                || isBlank(firstName)
                || isBlank(lastName);
    }

    private String readProfileValue(Map<String, Object> profileData, String key) {
        if (profileData == null) {
            return null;
        }

        Object value = profileData.get(key);
        return value != null ? value.toString() : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @DeleteMapping("/delete") // Endpoint: DELETE /auth/delete
    public ResponseEntity<?> deleteMyAccount(@AuthenticationPrincipal UserDetails userDetails) {
        // 1. Get the email from the Security Context (The Token)
        // This ensures a user can ONLY delete themselves.
        String email = userDetails.getUsername();

        // 2. Call the Soft Delete Logic
        userService.deleteUserAccount(email);

        // 3. Return Success
        return ResponseEntity.ok()
                .body(Collections.singletonMap("message", "Account deactivated successfully. You can reactivate it by logging in with Google."));
    }
}

package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.request.ProfileRequest;
import com.moniewise.moniewise_backend.dto.response.SignupResponse;
import com.moniewise.moniewise_backend.dto.response.UserSummaryResponse;
import com.moniewise.moniewise_backend.entity.PasswordResetToken;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.repository.PasswordResetTokenRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;

import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.transaction.annotation.Transactional; // ✅ CORRECT ONE
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


@Service
public class UserService implements UserDetailsService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder; // Now sourced from AppConfig
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final OtpService otpService;

    private final PasswordResetTokenRepository passwordResetTokenRepository;

    // Add to class dependencies
    private final NotificationService notificationService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, WalletService walletService, WalletRepository walletRepository, WalletService walletService1, OtpService otpService, PasswordResetTokenRepository passwordResetTokenRepository, NotificationService notificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder; // No link to SecurityConfig
        this.walletRepository = walletRepository;
        this.walletService = walletService1;
        this.otpService = otpService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public SignupResponse signup(String email, String phone, String password) {

        // 1. Check Global Email (Active + Deleted) to prevent DB constraint errors
        Optional<User> existingUser = userRepository.findGlobalByEmail(email);

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            if (user.isDeleted()) {
                // Optional: You could choose to reactivate here too,
                // but usually we ask them to contact support or use Google.
                throw new IllegalArgumentException("This account was deleted. Please login with Google to reactivate it.");
            }
            throw new IllegalArgumentException("Email already exists: " + email);
        }

        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already exists: " + email);
        }

        // Check for duplicate phone number
        if (userRepository.findByPhone(phone).isPresent()) {
            throw new IllegalArgumentException("An account with the phone number '" + phone + "' already exists.");
        }

        User user = new User();
        user.setEmail(email);
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(Role.USER);
        user.setProfileData(new HashMap<>());
        user.setVerified(false); // Default to unverified
        user.setCreatedAt(LocalDateTime.now());
        user.setTncAccepted(false); // during creation


        User savedUser = userRepository.save(user);

        // Generate OTP for verification
        otpService.generateOtp(user.getId());



        return new SignupResponse(
                savedUser,
                null, // Wallet is null
                null, // Methods null
                "PENDING_SETUP", // Account Number placeholder
                "PENDING_SETUP"  // Bank Name placeholder
        );
    }

    public Optional<User> findByEmailOrPhone(String input) {
        // We pass the input twice because we are saying: "Find where Email is X OR Phone is X"
        return userRepository.findByEmailOrPhone(input, input);
    }


    //================= SEARCH FOR USERS ===========================
    public List<UserSummaryResponse> searchUsers(String query, String currentEmail) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        return userRepository.searchUsers(query.trim())
                .stream()
                .filter(u -> !u.getEmail().equals(currentEmail))
                .map(u -> {
                    // 1. Generate Handle
                    String handle = "@" + u.getEmail().split("@")[0];

                    // 2. Try to get Real Name
                    String displayName = "Unknown"; // Default
                    if (u.getProfileData() != null) {
                        Object nameObj = u.getProfileData().getOrDefault("fullName", u.getProfileData().get("name"));
                        if (nameObj != null && !nameObj.toString().trim().isEmpty()) {
                            displayName = nameObj.toString();
                        }
                    }

                    // 3. THE FIX: If still "Unknown", use the Handle instead
                    if (displayName.equals("Unknown")) {
                        // Turn "@olaore66" -> "Olaore66"
                        String cleanName = handle.substring(1);
                        displayName = cleanName.substring(0, 1).toUpperCase() + cleanName.substring(1);
                    }

                    return new UserSummaryResponse(
                            displayName,
                            handle,
                            u.getProfileImageUrl(),
                            u.getEmail()
                    );
                })
                .limit(10)
                .collect(Collectors.toList());
    }
    //==============================================================

    // ================== TRANSACTION PIN MANAGEMENT ==================

    /**
     * Check if the user has a PIN set.
     */
    public boolean hasPin(User user) {
        return user.getTransactionPin() != null && !user.getTransactionPin().isEmpty();
    }

    /**
     * Create or Update the Transaction PIN.
     */
    @Transactional
    public void createTransactionPin(User user, String pin, String confirmPin) {
        // 1. Validations
        if (!pin.equals(confirmPin)) {
            throw new IllegalArgumentException("PINs do not match");
        }
        if (pin.length() != 4 || !pin.matches("\\d+")) {
            throw new IllegalArgumentException("PIN must be exactly 4 digits");
        }

        // 2. Security: Hash the PIN
        // We reuse the same BCrypt encoder used for passwords.
        // Result looks like: $2a$10$EixZa...
        String hashedPin = passwordEncoder.encode(pin);

        // 3. Save
        user.setTransactionPin(hashedPin);
        userRepository.save(user);
    }

    /**
     * Validate a PIN before a transfer (Returns true/false).
     */
    public boolean verifyTransactionPin(User user, String rawPin) {
        if (!hasPin(user)) {
            throw new IllegalStateException("User has not set a transaction PIN");
        }
        return passwordEncoder.matches(rawPin, user.getTransactionPin());
    }

    //=======================FIREBASE RECIEVE TOKEN=================
    @Transactional
    public void updateFcmToken(String email, String token) {
        User user = findByEmail(email);
        user.setFcmToken(token);
        userRepository.save(user);
    }
    //==============================================================

    @Transactional
    public User verifySignup(Long userId, String otpCode) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // ✅ Check if T&C was accepted before proceeding
        if (!Boolean.TRUE.equals(user.getTncAccepted())) {
            throw new IllegalStateException("User must accept Terms and Conditions before verification");
        }

        if (otpService.verifyOtp(userId, otpCode)) {
            user.setVerified(true); // Update the new column
            userRepository.save(user);
            otpService.clearOtp(userId);
            return user;
        } else {
            throw new IllegalArgumentException("Invalid or expired OTP");
        }
    }

    public User login(String emailOrPhone, String password) {
        User user = userRepository.findByEmail(emailOrPhone)
                .orElseGet(() -> userRepository.findByPhone(emailOrPhone)
                        .orElseThrow(() -> new RuntimeException("User not found")));

        // Validate password directly
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        // Check new isVerified column
        if (!user.isVerified()) {
            throw new RuntimeException("OTP verification required");
        }

        // Update lastLogin timestamp
        user.setLastLogin(Instant.now());
        userRepository.save(user); // Save to database

        return user;
    }


    // UserService.java

    // UserService.java

    // 1. Change signature to accept 'name'
//    public User findOrCreateOAuthUser(String email, String name) {
//        return userRepository.findByEmail(email)
//                .orElseGet(() -> {
//                    User newUser = new User();
//                    newUser.setEmail(email);
//                    newUser.setPassword(passwordEncoder.encode("GOOGLE_AUTH_USER_" + UUID.randomUUID().toString()));
//                    newUser.setRole(Role.USER);
//                    newUser.setVerified(true);
//                    newUser.setCreatedAt(LocalDateTime.now());
//
//                    // 2. Auto-fill the Name into Profile Data
//                    Map<String, Object> profile = new HashMap<>();
//                    // Use the name from Google, or "MonieWise User" if null
//                    profile.put("name", (name != null && !name.isEmpty()) ? name : "MonieWise User");
//                    profile.put("auth_provider", "google");
//
//                    newUser.setProfileData(profile);
//
//                    return userRepository.save(newUser);
//                });
//    }

    // In UserService.java

    public User findOrCreateOAuthUser(String email, String name) {
        // 1. 🔍 SEARCH GLOBALLY (Active AND Deleted users)
        // We use the custom method to find users hidden by the @Where clause
        Optional<User> existingUserOpt = userRepository.findGlobalByEmail(email);

        if (existingUserOpt.isPresent()) {
            User user = existingUserOpt.get();

            // 2. 🧟 REACTIVATION CHECK
            // If the user exists but was "Soft Deleted", we bring them back.
            if (user.isDeleted()) {
                logger.info("♻️ Reactivating returning user: " + email);
                user.setDeleted(false); // Mark as Active
                // We do NOT create a new wallet/password. We reuse the old data.
                return userRepository.save(user);
            }

            // If user is already active, just return them.
            return user;
        }

        // 3. 🆕 CREATE FRESH USER
        // Only runs if the email has TRULY never been seen before.
        User newUser = new User();
        newUser.setEmail(email);
        newUser.setPassword(passwordEncoder.encode("GOOGLE_AUTH_USER_" + UUID.randomUUID().toString()));
        newUser.setRole(Role.USER);
        newUser.setVerified(true);
        newUser.setCreatedAt(LocalDateTime.now());

        // Default to not deleted
        newUser.setDeleted(false);

        Map<String, Object> profile = new HashMap<>();
        profile.put("name", (name != null && !name.isEmpty()) ? name : "MonieWise User");
        profile.put("auth_provider", "google");

        newUser.setProfileData(profile);

        return userRepository.save(newUser);
    }

//    @Transactional
    public User updateProfile(String email, ProfileRequest request) {
        User user = findByEmail(email);

        // 1. Save Profile Data
        Map<String, Object> profileData = user.getProfileData();
        if (profileData == null) profileData = new HashMap<>();

        profileData.put("name", request.getName());
        profileData.put("monthlyIncome", request.getMonthlyIncome());
        profileData.put("mainExpense", request.getMainExpense());
        profileData.put("savingsGoal", request.getSavingsGoal());
        profileData.put("occupation", request.getOccupation());
        if(request.getDob() != null){
            profileData.put("dob", request.getDob());
        }
        user.setProfileData(profileData);

        // Save first so the user entity has the name ready for the WalletService
        User savedUser = userRepository.save(user);

        // ============================================================
        // 2. ⚡ CHECK & CREATE WALLET (The Missing Piece)
        // ============================================================
        Optional<Wallet> existingWallet = walletRepository.findByUser(user);

        if (existingWallet.isEmpty()) {
            try {
                logger.info("⚡ Profile complete. Creating Wallet for: " + user.getEmail());

                // This creates the wallet using the name we just saved!
                Wallet newWallet = walletService.createWalletForUser(savedUser);

                // 3. 📧 Send the Welcome Email (Now that we have bank details)
                CompletableFuture.runAsync(() -> {
                notificationService.sendWelcomeEmail(
                        savedUser.getEmail(),
                        newWallet.getAccountNumber(),
                        newWallet.getBankName(),
                        newWallet.getBalance()
                );
                });

            } catch (Exception e) {
                logger.error("❌ Wallet creation failed for user " + user.getId() + ": " + e.getMessage());
                // Note: We swallow the error here so the Profile Update doesn't fail.
                // The user can try again later, or you can run a background job to fix missing wallets.
            }
        }

        return savedUser;
    }
    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + email));
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + username));

        // 2. 🛡️ CRITICAL FIX: Handle NULL passwords
        // If the user came from Google, their password might be null in the DB.
        // We give Spring a "dummy" password just to keep it happy.
        // (This doesn't change the DB, just the in-memory object).
        String password = user.getPassword();
        if (password == null || password.isEmpty()) {
            password = "GOOGLE_LOGIN_NO_PASSWORD_NEEDED";
        }

        // 3. Return the Spring User
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                password,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }

    public void acceptTnc(String email, boolean accepted) {
        User user = findByEmail(email);
        Map<String, Object> profileData = user.getProfileData();
        profileData.put("acceptedTncVersion", "1.0");
        user.setProfileData(profileData);
        user.setTncAccepted(accepted);
        userRepository.save(user);
    }

    public void updateUserPassword(String token, String newPassword) {
        // 1. Fetch token from DB
        Optional<PasswordResetToken> tokenOpt = passwordResetTokenRepository.findByToken(token);

        if (tokenOpt.isEmpty()) {
            throw new IllegalArgumentException("Invalid token");
        }

        PasswordResetToken resetToken = tokenOpt.get();

        // 2. Check if token is expired or used
        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now()) || resetToken.isUsed()) {
            throw new IllegalArgumentException("Token has expired or already used");
        }

        // 3. Get user by email stored in the token
        String email = resetToken.getEmail();
//        Optional<User> userOpt = userRepository.findByEmail(email);
        // Correct usage
        Optional<User> userOpt = userRepository.findByEmail(resetToken.getEmail());


        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("User not found for token email");
        }

        // 4. Update user password
        User user = userOpt.get();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // 5. Mark token as used
        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);
    }

    //    ===================== PROFILE PICTURE CHANGE ===============================
    // 1. UPLOAD / UPDATE
    @Transactional
    public void uploadProfileImage(Long userId, MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new IllegalArgumentException("Cannot save empty file");
            }

            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Convert file to bytes and save
            user.setProfileImage(file.getBytes());

            // Optional: Update profile_data map if you use it for UI flags
            if (user.getProfileData() != null) {
                user.getProfileData().put("has_image", true);
            }

            userRepository.save(user);

        } catch (IOException e) {
            throw new RuntimeException("Failed to process image file", e);
        }
    }

    // 2. GET IMAGE
    @Transactional(readOnly = true)
    public byte[] getProfileImage(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getProfileImage();
    }

    // 3. DELETE IMAGE
    @Transactional
    public void deleteProfileImage(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setProfileImage(null); // Clear the bytes

        if (user.getProfileData() != null) {
            user.getProfileData().put("has_image", false);
        }

        userRepository.save(user);
    }

    // ================== SESSION MANAGEMENT ==================

    /**
     * Updates the user's session ID to enforce Single Device Login.
     * Called by SecurityConfig on successful OAuth2 login.
     */
    @Transactional
    public void updateUserSession(User user) {
        userRepository.save(user);
    }

    @Transactional
    public void deleteUserAccount(String email) {
        User user = findByEmail(email);

        // 🛡️ SOFT DELETE: Don't remove the row. Just hide it.
        user.setDeleted(true);

        // Optional: Clear sensitive data if required by law (GDPR),
        // but KEEP the ID and Wallet link.
        // user.setPassword("");

        userRepository.save(user);
        logger.info("❌ Soft-deleted user account: " + email);
    }


}
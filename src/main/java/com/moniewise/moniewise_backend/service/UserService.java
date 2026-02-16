package com.moniewise.moniewise_backend.service;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.firebase.cloud.StorageClient;
import com.moniewise.moniewise_backend.dto.request.ProfileRequest;
import com.moniewise.moniewise_backend.dto.response.SignupResponse;
import com.moniewise.moniewise_backend.dto.response.UserSummaryResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.PasswordResetTokenRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

    private final BudgetRepository budgetRepository;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, WalletService walletService, WalletRepository walletRepository, WalletService walletService1, OtpService otpService, PasswordResetTokenRepository passwordResetTokenRepository, NotificationService notificationService, BudgetRepository budgetRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder; // No link to SecurityConfig
        this.walletRepository = walletRepository;
        this.walletService = walletService1;
        this.otpService = otpService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.notificationService = notificationService;
        this.budgetRepository = budgetRepository;
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

    // ==============================================================
    // ✅ SAFE SEARCH (Fixes Memory Crash)
    // ==============================================================
//    public List<UserSummaryResponse> searchUsers(String query, String currentEmail) {
//        if (query == null || query.trim().isEmpty()) {
//            return Collections.emptyList();
//        }
//
//        // 1. Define excluded emails (Self + Revenue)
//        List<String> excludedEmails = Arrays.asList(currentEmail, "revenue@wisemonie.app");
//
//        // 1. Use the NEW Repository Method (Fetches only name/email/tag)
//        // We limit to 15 results at the DB level, saving massive RAM.
//        List<UserSummary> results = userRepository.searchUsers(
//                query.trim(),
//                excludedEmails,
//                PageRequest.of(0, 15)
//        );
//        return results.stream()
//                // 2. Filter self (Lightweight string check)
////                .filter(u -> !u.getEmail().equalsIgnoreCase(currentEmail))
//                .map(u -> {
//                    // 3. Generate Handle
//                    String handle = (u.getUserTag() != null && !u.getUserTag().isEmpty())
//                            ? u.getUserTag()
//                            : "@" + u.getEmail().split("@")[0];
//
//                    // 4. Generate Display Name
//                    String displayName = "Unknown";
//                    if (u.getFirstName() != null && !u.getFirstName().isEmpty()) {
//                        displayName = u.getFirstName() + " " + u.getLastName();
//                    } else {
//                        // Fallback to handle
//                        String cleanName = handle.startsWith("@") ? handle.substring(1) : handle;
//                        displayName = cleanName.substring(0, 1).toUpperCase() + cleanName.substring(1);
//                    }
//
//                    return new UserSummaryResponse(
//                            displayName,
//                            handle,
//                            u.getProfileImageUrl(),
//                            u.getEmail()
//                    );
//                })
//                .collect(Collectors.toList());
//    }

    // In UserService.java

    public List<UserSummaryResponse> searchUsers(String query, String currentEmail) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 1. Prepare the wildcard pattern in Java (Safer than SQL CONCAT)
        String searchPattern = "%" + query.trim().toLowerCase() + "%";

        // 2. Fetch slightly more results (e.g., 20) to account for the 1-2 we might filter out
        List<UserSummary> results = userRepository.searchUsers(
                searchPattern,
                PageRequest.of(0, 20)
        );

        return results.stream()
                // 3. Filter Self & Revenue Account in Java (100% Reliable)
                .filter(u -> !u.getEmail().equalsIgnoreCase(currentEmail) &&
                        !u.getEmail().equalsIgnoreCase("revenue@wisemonie.app"))
                .map(u -> {
                    // Generate Handle
                    String handle = (u.getUserTag() != null && !u.getUserTag().isEmpty())
                            ? u.getUserTag()
                            : "@" + u.getEmail().split("@")[0];

                    // Generate Display Name
                    String displayName = "Unknown";
                    if (u.getFirstName() != null && !u.getFirstName().isEmpty()) {
                        displayName = u.getFirstName() + " " + u.getLastName();
                    } else {
                        String cleanName = handle.startsWith("@") ? handle.substring(1) : handle;
                        displayName = cleanName.substring(0, 1).toUpperCase() + cleanName.substring(1);
                    }

                    return new UserSummaryResponse(
                            displayName,
                            handle,
                            u.getProfileImageUrl(),
                            u.getEmail()
                    );
                })
                .limit(15) // Limit back to 15 after filtering
                .collect(Collectors.toList());
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

        if (!walletRepository.existsByUser(savedUser)) {
            CompletableFuture.runAsync(() -> {
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
                    logger.error("❌ Background Wallet Creation Failed for {}: {}", savedUser.getEmail(), e.getMessage());
                    // Optional: Add logic to retry later or flag user as "Wallet Failed"
                }
            });
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

    // 1. UPLOAD IMAGE TO FIREBASE
    public String uploadProfileImage(Long userId, MultipartFile file) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Get the filename extension (e.g., .jpg, .png)
            String originalFilename = file.getOriginalFilename();
            String extension = "jpg"; // Default
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf(".") + 1);
            }

            // Create a unique file path: profile_images/USER_ID_TIMESTAMP.jpg
            String fileName = String.format("profile_images/%d_%d.%s",
                    userId, System.currentTimeMillis(), extension);

            // Get Firebase Storage Bucket
            Bucket bucket = StorageClient.getInstance().bucket();

            // Upload file
            Blob blob = bucket.create(fileName, file.getInputStream(), file.getContentType());

            // OPTION A: Generate a Signed URL (Valid for X days/years) - More Secure
            // URL signedUrl = blob.signUrl(365, TimeUnit.DAYS);
            // String publicUrl = signedUrl.toString();

            // OPTION B: Make Public (Easiest for Profile Pics)
            // Note: This requires the bucket or object to be publicly readable via IAM or Rules.
            // For simple apps, we often construct the public token manually or use signed URLs.
            // Let's use the Signed URL approach as it works out of the box with the Admin SDK.
            URL signedUrl = blob.signUrl(7300, TimeUnit.DAYS); // Valid for 20 years
            String publicUrl = signedUrl.toString();

            // Save the URL to Database
            user.setProfileImageUrl(publicUrl);

            // Remove legacy byte data if it exists to free up space
            user.setProfileImage(null);

            userRepository.save(user);

            return publicUrl;

        } catch (IOException e) {
            throw new RuntimeException("Failed to upload image to Firebase", e);
        }
    }

    // 2. DELETE IMAGE FROM FIREBASE
    public void deleteProfileImage(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String currentUrl = user.getProfileImageUrl();

        if (currentUrl != null && !currentUrl.isEmpty()) {
            try {
                // Extract file path from URL (Rough logic, depends on Option A or B above)
                // If using Signed URL, the path is hidden inside.
                // Better strategy: Store the 'fileName' (path) in DB as well if you need strict deletion.

                // For now, we will just clear the DB reference.
                // To actually delete from storage, you need the exact "blob name" (e.g., profile_images/1_12345.jpg).
                // If you want to support deletion, save the 'blobName' in your User entity too.

                // Example deletion if you knew the name:
                // Bucket bucket = StorageClient.getInstance().bucket();
                // bucket.get("profile_images/old_file_name.jpg").delete();

            } catch (Exception e) {
                logger.error("Error deleting file from Firebase", e);
            }
        }

        user.setProfileImageUrl(null);
        userRepository.save(user);
    }

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

    // ==============================================================
    // ✅ GET MOST RECENT BUDGETS (Active & Completed)
    // ==============================================================
    @Transactional(readOnly = true)
    public Map<String, Object> getMostRecentBudgets(String email) {
        User user = findByEmail(email);

        // 1. Fetch Most Recent Active
        Optional<Budget> activeOpt = budgetRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), BudgetStatus.ACTIVE);

        // 2. Fetch Most Recent Completed
        Optional<Budget> completedOpt = budgetRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), BudgetStatus.COMPLETED);

        // 3. Construct Response
        Map<String, Object> response = new HashMap<>();

        // We map manually or use a helper to avoid Infinite Recursion (User -> Budget -> User)
        response.put("active", activeOpt.map(this::mapBudgetToSummary).orElse(null));
        response.put("completed", completedOpt.map(this::mapBudgetToSummary).orElse(null));

        return response;
    }

    // Helper to prevent infinite JSON recursion / loading heavy relationships
    private Map<String, Object> mapBudgetToSummary(Budget budget) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", budget.getId());
        summary.put("name", budget.getName());
        summary.put("totalAmount", budget.getTotalAmount());
        summary.put("allocatedAmount", budget.getAllocatedAmount());
        summary.put("startDate", budget.getStartDate());
        summary.put("endDate", budget.getEndDate());
        summary.put("status", budget.getStatus());
        summary.put("createdAt", budget.getCreatedAt());
        // Add envelope count or summary if needed, but keep it light
        summary.put("envelopeCount", budget.getEnvelopes() != null ? budget.getEnvelopes().size() : 0);
        return summary;
    }


}
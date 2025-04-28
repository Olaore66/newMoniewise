package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.request.ProfileRequest;
import com.moniewise.moniewise_backend.dto.response.SignupResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.repository.UserRepository;

import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.Transactional;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Service
public class UserService implements UserDetailsService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder; // Now sourced from AppConfig
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final OtpService otpService;

    // Add to class dependencies
    private final NotificationService notificationService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, WalletService walletService, WalletRepository walletRepository, WalletService walletService1, OtpService otpService, NotificationService notificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder; // No link to SecurityConfig
        this.walletRepository = walletRepository;
        this.walletService = walletService1;
        this.otpService = otpService;
        this.notificationService = notificationService;
    }

    @Transactional
    public SignupResponse signup(String email, String phone, String password, Role role) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already exists: " + email);
        }

        User user = new User();
        user.setEmail(email);
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(role);
        user.setProfileData(new HashMap<>());
        user.setVerified(false); // Default to unverified
        user.setCreatedAt(LocalDateTime.now());

        User savedUser = userRepository.save(user);
        Wallet wallet = walletService.createWalletForUser(savedUser);

        // Generate OTP for verification
        otpService.generateOtp(user.getId());

        // Create wallet

        notificationService.sendWelcomeEmail(
                savedUser.getEmail(),
                wallet.getAccountNumber(),
                wallet.getBankName(),
                wallet.getBalance()
        );

        return new SignupResponse(
                savedUser,
                wallet,
                List.of("bank_transfer", "third_party"),
                wallet.getAccountNumber(),
                wallet.getBankName()
        );
    }

    @Transactional
    public User verifySignup(Long userId, String otpCode) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

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


    public User findOrCreateOAuthUser(String email) {
        return userRepository.findByEmail(email)
                .orElseGet(() -> {
                    User newUser = new User();
                    newUser.setEmail(email);
                    newUser.setPassword(null); // OAuth users don’t need passwords
                    newUser.setRole(Role.USER);
                    return userRepository.save(newUser);
                });
    }

    // UserService
    @Transactional
    public User updateProfile(String email, ProfileRequest request) {
        User user = findByEmail(email);
        Map<String, Object> profileData = new HashMap<>();
        profileData.put("monthlyIncome", request.getMonthlyIncome());
        profileData.put("mainExpense", request.getMainExpense());
        profileData.put("savingsGoal", request.getSavingsGoal());
        profileData.put("occupation", request.getOccupation());
        if(request.getDob() != null){
            profileData.put("dob", request.getDob());
        }
        user.setProfileData(profileData);

        // Mock Paystack profile creation
        logger.info("Mock: Created Paystack profile for user {}", user.getId());
        return userRepository.save(user);
    }

    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + email));
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + username));
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                Collections.singletonList(user.getRole()) // Role as GrantedAuthority
        );
    }

    // UserService.java
    public void acceptTnc(String email) {
        User user = findByEmail(email);
        Map<String, Object> profileData = user.getProfileData();
        profileData.put("acceptedTncVersion", "2.0"); // Hardcoded in your code
        user.setProfileData(profileData);
        userRepository.save(user);
    }
}
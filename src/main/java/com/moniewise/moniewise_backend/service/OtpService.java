package com.moniewise.moniewise_backend.service;


import com.moniewise.moniewise_backend.entity.Otp;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.exception.OtpVerificationException;
import com.moniewise.moniewise_backend.repository.OtpRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class OtpService {

    private final OtpRepository otpRepository;
    private static final int OTP_LENGTH = 6;
    private static final int OTP_EXPIRY_MINUTES = 5;

    private final UserRepository userRepository;

    public OtpService(OtpRepository otpRepository, UserRepository userRepository) {
        this.otpRepository = otpRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public String generateOtp(Long userId) {
        // Generate 6-digit OTP
        SecureRandom random = new SecureRandom();
        String otpCode = String.format("%06d", random.nextInt(1000000));

        // Delete any existing OTP for the user
        otpRepository.deleteByUserId(userId);

        // Fetch existing user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + userId));

        // Set isVerified to false
        user.setVerified(false);
        userRepository.save(user); // Update existing user

        // Create and save new OTP
        LocalDateTime now = LocalDateTime.now();
        Otp otp = new Otp(userId, otpCode, now, now.plusMinutes(OTP_EXPIRY_MINUTES));
        otpRepository.save(otp);

        // TODO: For future Twilio integration
        // Send OTP via Twilio SMS API: POST /v1/Messages
        // Example: TwilioClient.sendSms(userPhone, "Your Moniewise OTP is: " + otpCode);

        return otpCode;
    }

    @Transactional(readOnly = true)
    public boolean verifyOtp(Long userId, String otpCode) {
        Optional<Otp> otpOptional = otpRepository.findByUserIdAndOtpCode(userId, otpCode);

        if (otpOptional.isEmpty()) {
            throw new OtpVerificationException("Invalid OTP code");
        }

        Otp otp = otpOptional.get();
        if (otp.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new OtpVerificationException("OTP has expired");
        }

        return true;
    }

    @Transactional
    public void clearOtp(Long userId) {
        otpRepository.deleteByUserId(userId);
    }
}

package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.ChangeTransactionPinRequest;
import com.moniewise.moniewise_backend.dto.request.PinRequest;
import com.moniewise.moniewise_backend.dto.request.ResetTransactionPinRequest;
import com.moniewise.moniewise_backend.dto.response.PinStatusResponse;
import com.moniewise.moniewise_backend.entity.TransactionPinResetToken;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.service.NotificationService;
import com.moniewise.moniewise_backend.service.TransactionPinResetService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/transactions/pin") // Recommended dedicated path
public class UserPinController {

    private final UserService userService;
    private final TransactionPinResetService transactionPinResetService;
    private final NotificationService notificationService;

    public UserPinController(
            UserService userService,
            TransactionPinResetService transactionPinResetService,
            NotificationService notificationService
    ) {
        this.userService = userService;
        this.transactionPinResetService = transactionPinResetService;
        this.notificationService = notificationService;
    }

    // 1. GET STATUS: Frontend calls this on Transfer Screen Load
    // If true -> Show "Enter PIN". If false -> Navigate to "Create PIN".
    @GetMapping("/status")
    public ResponseEntity<PinStatusResponse> getPinStatus(Authentication authentication) {
        User user = userService.findByEmail(authentication.getName());
        return ResponseEntity.ok(new PinStatusResponse(userService.hasPin(user)));
    }

    // 2. CREATE PIN: Called when user enters "1234" -> "1234" -> "Save"
    @PostMapping("/create")
    public ResponseEntity<?> createPin(
            Authentication authentication,
            @RequestBody PinRequest request
    ) {
        User user = userService.findByEmail(authentication.getName());
        
        try {
            userService.createTransactionPin(user, request.getPin(), request.getConfirmPin());
            return ResponseEntity.ok(Map.of("message", "Transaction PIN created successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // 3. VERIFY PIN: (Optional helper for UI checks)
    // You can call this to show a "Green Checkmark" before sending the actual money.
    @PostMapping("/verify")
    public ResponseEntity<?> verifyPin(
            Authentication authentication,
            @RequestBody PinRequest request
    ) {
        try {
            User user = userService.findByEmail(authentication.getName());
            boolean isValid = userService.verifyTransactionPin(user, request.getPin());

            if (isValid) {
                return ResponseEntity.ok(Map.of("valid", true));
            } else {
                return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Incorrect PIN"));
            }
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/change")
    public ResponseEntity<?> changePin(
            Authentication authentication,
            @RequestBody ChangeTransactionPinRequest request
    ) {
        User user = userService.findByEmail(authentication.getName());

        try {
            userService.changeTransactionPin(
                    user,
                    request.getCurrentPin(),
                    request.getNewPin(),
                    request.getConfirmNewPin()
            );
            return ResponseEntity.ok(Map.of("message", "Transaction PIN changed successfully"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/forgot/request")
    public ResponseEntity<?> requestPinReset(Authentication authentication) {
        User user = userService.findByEmail(authentication.getName());
        TransactionPinResetToken token = transactionPinResetService.createResetToken(user.getEmail());
        String userName = user.getName() != null ? user.getName() : "User";
        notificationService.sendTransactionPinResetOtp(user.getEmail(), userName, token.getToken());
        return ResponseEntity.ok(Map.of("message", "A PIN reset code has been sent to your email"));
    }

    @PostMapping("/forgot/verify")
    public ResponseEntity<?> verifyPinResetOtp(
            Authentication authentication,
            @RequestBody Map<String, String> body
    ) {
        User user = userService.findByEmail(authentication.getName());
        String otp = body.get("otp");
        boolean valid = transactionPinResetService.isValidToken(user.getEmail(), otp);

        if (!valid) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired OTP"));
        }

        return ResponseEntity.ok(Map.of("message", "OTP verified"));
    }

    @PostMapping("/forgot/reset")
    public ResponseEntity<?> resetForgottenPin(
            Authentication authentication,
            @RequestBody ResetTransactionPinRequest request
    ) {
        User user = userService.findByEmail(authentication.getName());

        try {
            if (!transactionPinResetService.isValidToken(user.getEmail(), request.getOtp())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired OTP"));
            }

            userService.resetTransactionPin(user, request.getNewPin(), request.getConfirmNewPin());
            transactionPinResetService.markTokenAsUsed(user.getEmail(), request.getOtp());
            return ResponseEntity.ok(Map.of("message", "Transaction PIN reset successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}

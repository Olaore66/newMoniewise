package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.PinRequest;
import com.moniewise.moniewise_backend.dto.response.PinStatusResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/transactions/pin") // Recommended dedicated path
public class UserPinController {

    private final UserService userService;
    private final UserRepository userRepository; // Or use service to fetch user

    public UserPinController(UserService userService, UserRepository userRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
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
        User user = userService.findByEmail(authentication.getName());
        boolean isValid = userService.verifyTransactionPin(user, request.getPin());

        if (isValid) {
            return ResponseEntity.ok(Map.of("valid", true));
        } else {
            return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Incorrect PIN"));
        }
    }
}
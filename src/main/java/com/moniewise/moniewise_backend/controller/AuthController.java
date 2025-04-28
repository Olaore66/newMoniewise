package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.AuthRequest;
import com.moniewise.moniewise_backend.dto.response.AuthResponse;
import com.moniewise.moniewise_backend.dto.response.LogoutResponse;
import com.moniewise.moniewise_backend.dto.response.SignupResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.security.JwtUtil;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody AuthRequest request) {
        try {
            Role role = request.getRole() != null ? Role.valueOf(request.getRole()) : Role.USER;
            SignupResponse user = userService.signup(request.getEmail(), request.getPhone(), request.getPassword(), role);
            return ResponseEntity.ok("User registered successfully \n" + user);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request) {
        try {
            User user = userService.login(request.getEmailOrPhone(), request.getPassword());
            UserDetails userDetails = userService.loadUserByUsername(user.getEmail());
            String token = jwtUtil.generateToken(userDetails);
            return ResponseEntity.ok(new AuthResponse(token));
        } catch (RuntimeException e) {
            if ("OTP verification required".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "OTP verification required"));
            }
            return ResponseEntity.badRequest().body(e.getMessage());
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

}
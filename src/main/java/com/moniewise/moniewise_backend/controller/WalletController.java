package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.FundWalletRequest;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.service.UserService;
import com.moniewise.moniewise_backend.service.WalletService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

// WalletController (new)
@RestController
@RequestMapping("/wallets")
public class WalletController {
    private final WalletService walletService;
    private final UserService userService;

    public WalletController(WalletService walletService, UserService userService) {
        this.walletService = walletService;
        this.userService = userService;
    }

    @PostMapping("/account")
    public ResponseEntity<?> createVirtualAccount(Authentication authentication) {
        try {
            String email = authentication.getName();
            Long userId = userService.findByEmail(email).getId();
            Map<String, String> accountDetails = walletService.createVirtualAccount(userId);
            return ResponseEntity.ok(accountDetails);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/fund")
    public ResponseEntity<?> fundWallet(@RequestBody FundWalletRequest request, Authentication authentication) {
        try {
            String email = authentication.getName();
            Long userId = userService.findByEmail(email).getId();
            walletService.fundWallet(userId, request.getAmount());
            return ResponseEntity.ok(Map.of("message", "Wallet funded successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

}

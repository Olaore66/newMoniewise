package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.FundWalletRequest;
import com.moniewise.moniewise_backend.dto.response.WalletResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.service.UserService;
import com.moniewise.moniewise_backend.service.WalletService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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
            walletService.fundWallet(userId, request.getAmount(), null);
            return ResponseEntity.ok(Map.of("message", "Wallet funded successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getWallet(@PathVariable Long userId) {
        Wallet wallet = walletService.findById(userId).getWallet();
        // ⬆️ we should add a helper in WalletService (findByUserId) instead of chaining

        return ResponseEntity.ok(new WalletResponse(
                wallet.getBalance(),
                wallet.getCurrency(),
                wallet.getAccountNumber(),
                wallet.getBankName(),
                wallet.getStatus().name(),
                wallet.getUpdatedAt()
        ));
    }

}

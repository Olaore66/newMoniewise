package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.FundWalletRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.dto.response.WalletResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.service.MonnifyPaymentProvider;
import com.moniewise.moniewise_backend.service.UserService;
import com.moniewise.moniewise_backend.service.WalletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/wallets")
public class WalletController {
    private final WalletService walletService;
    private final UserService userService;

    @Autowired
    private MonnifyPaymentProvider monnifyPaymentProvider; // Inject your provider

    public WalletController(WalletService walletService, UserService userService) {
        this.walletService = walletService;
        this.userService = userService;
    }

    @PostMapping("/fund")
    public ResponseEntity<?> fundWallet(@RequestBody FundWalletRequest request, Authentication authentication) {
        try {
            String email = authentication.getName();
            Long userId = userService.findByEmail(email).getId();

            // Note: The null is for the custom message.
            walletService.fundWallet(userId, request.getAmount(), null);

            return ResponseEntity.ok(Map.of("message", "Wallet funded successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> getMyWallet(Authentication authentication) {
        String email = authentication.getName();
        Long userId = userService.findByEmail(email).getId();

        // Use a clean service method instead of chaining .getUser().getWallet()
        Wallet wallet = walletService.getWalletByUserId(userId);

        return ResponseEntity.ok(new WalletResponse(
                wallet.getBalance(),
                wallet.getCurrency(),
                wallet.getAccountNumber(),
                wallet.getBankName(),
                wallet.getStatus().name(),
                wallet.getUpdatedAt()
        ));
    }

    @PostMapping("/deposit/card")
    public ResponseEntity<?> initiateCardDeposit(@AuthenticationPrincipal UserDetails userDetails,
                                                 @RequestBody Map<String, BigDecimal> request) {
        String email = userDetails.getUsername();
        User user = userService.findByEmail(email);

        BigDecimal amount = request.get("amount");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body("Invalid amount");
        }

        // Call the new method we just wrote
        Map<String, String> response = monnifyPaymentProvider.initializeCardPayment(user, amount);

        return ResponseEntity.ok(response);
    }


    // Inside WalletController.java

    @PostMapping("/withdraw")
    public ResponseEntity<?> withdrawFunds(
            @RequestBody WithdrawalRequest request,
            Authentication authentication
    )  {
        try {
            // 1. Get User
            String email = authentication.getName();
            User user = userService.findByEmail(email);

            // 2. Call Service
            walletService.withdrawToBank(user.getId(), request);

            // 3. Return Success
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Withdrawal successful",
                    "newBalance", walletService.checkBalance(user.getId()) // Optional convenience
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Withdrawal failed: " + e.getMessage()));
        }
    }

}
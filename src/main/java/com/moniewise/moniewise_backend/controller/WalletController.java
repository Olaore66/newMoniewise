package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.UpdateBankDetailsRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.dto.response.WalletResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.entity.Withdrawal;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.UserService;
import com.moniewise.moniewise_backend.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;
    private final UserService userService;
    private final AbuseProtectionService abuseProtectionService;

    public WalletController(WalletService walletService,
                            UserService userService,
                            AbuseProtectionService abuseProtectionService) {
        this.walletService = walletService;
        this.userService = userService;
        this.abuseProtectionService = abuseProtectionService;
    }

    @GetMapping
    public ResponseEntity<?> getMyWallet(Authentication authentication) {
        String email = authentication.getName();
        Long userId = userService.findByEmail(email).getId();

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

    @GetMapping("/bank-info")
    public ResponseEntity<?> getLinkedBankInfo(Principal principal) {
        try {
            User user = userService.findByEmail(principal.getName());
            Map<String, Object> linkedBankInfo = walletService.getLinkedBankInfo(user.getId(), user.getEmail());

            if (linkedBankInfo == null || linkedBankInfo.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "status", false,
                        "message", "No linked bank account found"
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "status", true,
                    "message", "Linked bank account fetched successfully",
                    "data", linkedBankInfo
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", false,
                    "message", e.getMessage()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "status", false,
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET: Fetch all supported banks for withdrawals
     */
    @GetMapping("/banks")
    public ResponseEntity<?> getSupportedBanks(Authentication authentication) {
        try {
            Long userId = userService.findByEmail(authentication.getName()).getId();
            List<Map<String, Object>> banks = walletService.getSupportedBanks(userId);

            if (banks.isEmpty()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Bank list is currently unavailable");
            }

            return ResponseEntity.ok(banks);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "An error occurred while fetching banks"));
        }
    }

    /**
     * POST: Resolve Account Name (KYC Check).
     * Rate-limited per user+IP — 30 lookups per 5 minutes to prevent scraping.
     */
    @PostMapping("/resolve-account")
    public ResponseEntity<?> resolveBankAccount(Authentication authentication,
                                                @RequestBody Map<String, String> payload,
                                                HttpServletRequest httpRequest) {
        String bankCode      = payload.get("bankCode");
        String accountNumber = payload.get("accountNumber");

        log.error("\n\n🚨🚨🚨 ALARM: WALLET CONTROLLER HIT (POST)! 🚨🚨🚨\nBank: {}, Account: {}\n\n",
                bankCode, accountNumber);

        if (bankCode == null || accountNumber == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "bankCode and accountNumber are required."));
        }

        String throttleKey = abuseProtectionService.buildKey(authentication.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.WALLET_RESOLVE_ACCOUNT, throttleKey);

        try {
            Long userId = userService.findByEmail(authentication.getName()).getId();
            String accountName = walletService.resolveBankAccount(userId, bankCode, accountNumber);
            // Count every lookup (success or failure) — prevents bulk account enumeration
            abuseProtectionService.recordRequest(AbuseProtectionService.WALLET_RESOLVE_ACCOUNT, throttleKey);
            return ResponseEntity.ok(Map.of("accountName", accountName));
        } catch (RuntimeException e) {
            abuseProtectionService.recordRequest(AbuseProtectionService.WALLET_RESOLVE_ACCOUNT, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST: Save the verified Settlement Account details to the Wallet.
     * Rate-limited — prevents rapid bank account cycling.
     */
    @PostMapping("/bank-info")
    public ResponseEntity<?> updateWithdrawalBank(@Valid @RequestBody UpdateBankDetailsRequest request,
                                                  Principal principal,
                                                  HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(principal.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.WALLET_BANK_INFO, throttleKey);
        try {
            User user = userService.findByEmail(principal.getName());
            Wallet updatedWallet = walletService.updateSettlementAccount(user.getId(), request);
            abuseProtectionService.recordSuccess(AbuseProtectionService.WALLET_BANK_INFO, throttleKey);
            return ResponseEntity.ok(Map.of(
                    "message", "Bank details updated successfully",
                    "accountName", updatedWallet.getSettlementAccountName(),
                    "accountNumber", updatedWallet.getSettlementAccountNumber(),
                    "bankName", updatedWallet.getSettlementBankName()
            ));
        } catch (RuntimeException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.WALLET_BANK_INFO, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST: Initiate a Withdrawal to the Settlement Account.
     * Rate-limited — 10 withdrawal attempts per hour per user+IP.
     */
    @PostMapping("/withdraw")
    public ResponseEntity<?> withdrawFunds(@Valid @RequestBody WithdrawalRequest request,
                                           Principal principal,
                                           HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(principal.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.WALLET_WITHDRAW, throttleKey);
        try {
            User user = userService.findByEmail(principal.getName());
            Withdrawal withdrawal = walletService.processWithdrawal(user.getId(), request);
            abuseProtectionService.recordSuccess(AbuseProtectionService.WALLET_WITHDRAW, throttleKey);
            return ResponseEntity.ok(Map.of(
                    "status", true,
                    "message", "Withdrawal successful",
                    "data", Map.of(
                            "status", true,
                            "message", "Withdrawal request has been received and being processed",
                            "data", Map.of(
                                    "withdrawalId", withdrawal.getId(),
                                    "clientReference", withdrawal.getClientReference(),
                                    "reference", withdrawal.getProviderReference(),
                                    "amount", withdrawal.getAmount(),
                                    "narration", withdrawal.getNarration(),
                                    "status", withdrawal.getStatus().name()
                            )
                    )
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.WALLET_WITHDRAW, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.WALLET_WITHDRAW, throttleKey);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}

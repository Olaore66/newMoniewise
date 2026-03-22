package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.UpdateBankDetailsRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.dto.response.WalletResponse;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.externalTransfers.PaymentProvider;
import com.moniewise.moniewise_backend.service.UserService;
import com.moniewise.moniewise_backend.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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
    private final PaymentProvider paymentProvider;


    // Constructor Injection (Spring Boot will automatically inject SecureWave because we added @Primary to it!)
    public WalletController(WalletService walletService, UserService userService, PaymentProvider paymentProvider) {
        this.walletService = walletService;
        this.userService = userService;
        this.paymentProvider = paymentProvider;
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

    // =========================================================================
    // SECUREWAVE CLOSED-LOOP WITHDRAWAL ENDPOINTS
    // =========================================================================

    /**
     * GET: Fetch all supported banks for withdrawals
     */
    @GetMapping("/banks")
    public ResponseEntity<?> getSupportedBanks() {
        try {
            List<Map<String, Object>> banks = paymentProvider.getSupportedBanks();

            if (banks.isEmpty()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Bank list is currently unavailable");
            }

            return ResponseEntity.ok(banks);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "An error occurred while fetching banks"));
        }
    }

    /**
     * GET: Resolve Account Name (KYC Check)
     */
    /**
     * GET: Resolve Account Name (KYC Check)
     */
    @GetMapping("/resolve-account")
    public ResponseEntity<?> resolveBankAccount(
            @RequestParam String bankCode,
            @RequestParam String accountNumber) {

        // 🚨 THE ALARM: This proves Postman is hitting the right server!
        log.error("\n\n🚨🚨🚨 ALARM: WALLET CONTROLLER HIT! 🚨🚨🚨\nBank: {}, Account: {}\n\n", bankCode, accountNumber);

        try {
            String accountName = paymentProvider.resolveAccount(bankCode, accountNumber);
            return ResponseEntity.ok(Map.of("accountName", accountName));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST: Save the verified Settlement Account details to the Wallet
     */
    @PostMapping("/bank-info")
    public ResponseEntity<?> updateWithdrawalBank(
            @Valid @RequestBody UpdateBankDetailsRequest request,
            Principal principal) {
        try {
            User user = userService.findByEmail(principal.getName());

            Wallet updatedWallet = walletService.updateSettlementAccount(user.getId(), request);

            return ResponseEntity.ok(Map.of(
                    "message", "Bank details updated successfully",
                    "accountName", updatedWallet.getSettlementAccountName(),
                    "accountNumber", updatedWallet.getSettlementAccountNumber(),
                    "bankName", updatedWallet.getSettlementBankName()
            ));

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST: Initiate a Withdrawal to the Settlement Account
     */
    @PostMapping("/withdraw")
    public ResponseEntity<?> withdrawFunds(
            @Valid @RequestBody WithdrawalRequest request,
            Principal principal) {
        try {
            User user = userService.findByEmail(principal.getName());

            TransactionLog transactionLog = walletService.processWithdrawal(user.getId(), request);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Withdrawal initiated successfully",
                    "reference", transactionLog.getReference(),
                    "amount", transactionLog.getAmount()
            ));

        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
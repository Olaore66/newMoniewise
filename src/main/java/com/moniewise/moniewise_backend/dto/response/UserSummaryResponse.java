package com.moniewise.moniewise_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor // <--- This is the mechanic that fixes the "Cannot resolve constructor" error
@NoArgsConstructor
public class UserSummaryResponse {
    private String name;        // Display: "Sarah Johnson"
    private String username;    // Display: "@sarah_j"
    private String avatarUrl;   // Display: [Image]
    private String email;       // HIDDEN LOGIC: "sarah@gmail.com" <--- Add this back!
    private WalletMetadata wallet;

    public UserSummaryResponse(String name, String username, String avatarUrl, String email) {
        this(name, username, avatarUrl, email, WalletMetadata.pending());
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class WalletMetadata {
        private boolean hasWallet;
        private String accountNumber;
        private String bankName;
        private String status;

        public static WalletMetadata of(String accountNumber, String bankName, String status) {
            boolean hasWallet = accountNumber != null && !accountNumber.isBlank();
            return new WalletMetadata(
                    hasWallet,
                    hasWallet ? accountNumber : "PENDING_SETUP",
                    bankName != null && !bankName.isBlank() ? bankName : "PENDING_SETUP",
                    status != null && !status.isBlank() ? status : "PENDING_SETUP"
            );
        }

        public static WalletMetadata pending() {
            return new WalletMetadata(false, "PENDING_SETUP", "PENDING_SETUP", "PENDING_SETUP");
        }
    }
}

package com.moniewise.moniewise_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WalletResponse {
            BigDecimal balance;
            String currency;
            String accountNumber;
            String bankName;
            String status;
            LocalDateTime updatedAt;
    {}
}

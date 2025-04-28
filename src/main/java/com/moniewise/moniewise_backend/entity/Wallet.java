package com.moniewise.moniewise_backend.entity;


import com.moniewise.moniewise_backend.enums.WalletStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.jpa.convert.threeten.Jsr310JpaConverters;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "wallets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Wallet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

//    @OneToOne
//    @JoinColumn(name = "user_id", nullable = false)
//    private User userId;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user; // NOT Long userId

    @Column(nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false)
    private String currency = "NGN";

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private WalletStatus status = WalletStatus.ACTIVE;

    @Column(name = "account_number")
    private String accountNumber;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "updated_at")
    @Convert(converter = Jsr310JpaConverters.LocalDateTimeConverter.class) // Add this
    private LocalDateTime updatedAt = LocalDateTime.now();
}
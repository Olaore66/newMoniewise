//package com.moniewise.moniewise_backend.entity;
//
//import lombok.Getter;
//import lombok.Setter;
//import lombok.NoArgsConstructor;
//import lombok.AllArgsConstructor;
//
//import javax.persistence.*;
//import java.math.BigDecimal;
//import java.time.LocalDateTime;
//
//
//@Entity
//@Getter
//@Setter
//@NoArgsConstructor
//@AllArgsConstructor
//@Table(name = "revenue_logs")
//public class RevenueLog {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    @Column(name = "user_id", nullable = false)
//    private Long userId;
//
//    @Column(nullable = false)
//    private String type; // e.g., "budget_creation", "movement_fee"
//
//    @Column(nullable = false)
//    private BigDecimal amount;
//
//    @Column
//    private String description;
//
//    @Column(name = "created_at")
//    private LocalDateTime createdAt = LocalDateTime.now();
//
//}

package com.moniewise.moniewise_backend.entity;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "revenue_logs")
public class RevenueLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "type", nullable = false, length = 50) // Updated length
    private String type;


    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "description", nullable = false, length = 255) // Updated length
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // Constructors
    public RevenueLog() {}

    public RevenueLog(Long userId, String type, BigDecimal amount, String description) {
        this.userId = userId;
        this.type = type;
        this.amount = amount;
        this.description = description;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

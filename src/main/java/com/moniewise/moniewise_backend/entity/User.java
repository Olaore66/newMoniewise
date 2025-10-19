package com.moniewise.moniewise_backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.moniewise.moniewise_backend.enums.Role;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import org.springframework.data.jpa.convert.threeten.Jsr310JpaConverters;

import javax.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

@Entity

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "is_verified", nullable = false)
    private boolean isVerified = false; // Default to false

    @JsonIgnore  // Add this annotation
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL)
    private Wallet wallet;

    @Column(unique = true)
    private String phone;

    @Column(name = "fcm_token")
    private String fcmToken; // Added for FCM push notifications

    @Column
    private String password; // NULL for OAuth users

    // newly added --->04/06/25
    @Column(name = "tnc_accepted")
    private Boolean tncAccepted;

//    @Convert(disableConversion = true) // Disable auto-converter
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> profileData;

    @Column(unique = true)
    private String bvn;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Role role = Role.USER; // Default to USER

    @Column(name = "created_at", updatable = false)
    @Convert(converter = Jsr310JpaConverters.LocalDateTimeConverter.class) // Add this
    private LocalDateTime createdAt = LocalDateTime.now();
//    private Instant createdAt = Instant.now();

    @Column(name = "last_login")
    private Instant lastLogin;

    // For JwtUtil compatibility (pass email as token subject)
    public String getUsername() {
        return email;
    }

    // Extract name from profile_data
    public String getName() {
        if (profileData != null && profileData.containsKey("name")) {
            return (String) profileData.get("name");
        }
        return null;
    }
}
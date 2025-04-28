package com.moniewise.moniewise_backend.dto.request;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {
    private Long id;
    private String email;
    private String phone;
    private Role role;
    private LocalDateTime createdAt;
    private Instant lastLogin; // Added
    private Map<String, Object> profileData; // Added
    private boolean isVerified; // Add this

    public UserDTO(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.phone = user.getPhone();
        this.role = user.getRole();
        this.createdAt = user.getCreatedAt();
        this.lastLogin = user.getLastLogin(); // Added
        this.profileData = user.getProfileData(); // Added
        this.isVerified = user.isVerified(); // Populate from entity
    }

    // Getters (NO setters unless needed)
    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public Role getRole() { return role; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Instant getLastLogin() { return lastLogin; }
    public Map<String, Object> getProfileData() { return profileData; }
}
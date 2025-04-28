package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthRequest {
    private String emailOrPhone; // For login
    private String email;        // For signup
    private String phone;        // For signup
    private String password;
    private String role;         // Optional, for signup
}
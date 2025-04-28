package com.moniewise.moniewise_backend.enums;

import org.springframework.security.core.GrantedAuthority;

public enum Role implements GrantedAuthority {
    USER, ADMIN;

    @Override
    public String getAuthority() {
        return name(); // Returns "USER" or "ADMIN"
    }
}
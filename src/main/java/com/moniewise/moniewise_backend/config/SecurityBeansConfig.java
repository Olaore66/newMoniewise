package com.moniewise.moniewise_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

public class SecurityBeansConfig {

    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

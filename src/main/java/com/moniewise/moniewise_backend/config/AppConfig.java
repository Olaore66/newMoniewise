package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.service.NotificationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AppConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

//    @Bean
//    public com.moniewise.moniewise_backend.thirdParty.NotificationService notificationService() {
//        return new NotificationService();
//        // TODO: Replace with real service (e.g., Twilio for SMS, WebSocket for in-app) in future
//    }
}
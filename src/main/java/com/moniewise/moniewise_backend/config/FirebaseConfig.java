package com.moniewise.moniewise_backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.io.FileInputStream;
import java.io.IOException;

@Configuration
public class FirebaseConfig {
    private static final Logger logger = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${spring.profiles.active:stub}")
    private String activeProfile;

    @Bean
    public FirebaseApp initializeFirebase() {
        if ("stub".equals(activeProfile) || "dev".equals(activeProfile)) {
            logger.info("[STUB] FirebaseApp not initialized in {} mode", activeProfile);
            return null;
        }
        try {
            FileInputStream serviceAccount = new FileInputStream("src/main/resources/moniewise-firebase-adminsdk.json");
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();
            FirebaseApp app = FirebaseApp.initializeApp(options);
            logger.info("FirebaseApp initialized successfully");
            return app;
        } catch (IOException e) {
            logger.error("Failed to initialize Firebase: {}", e.getMessage());
            return null;
        }
    }

    @Bean
    @Conditional(FirebaseNotStubCondition.class)
    public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        if (firebaseApp == null) {
            logger.info("[STUB] FirebaseMessaging not initialized due to null FirebaseApp");
            return null;
        }
        return FirebaseMessaging.getInstance(firebaseApp);
    }

    static class FirebaseNotStubCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String activeProfile = context.getEnvironment().getProperty("spring.profiles.active", "stub");
            return !"stub".equals(activeProfile) && !"dev".equals(activeProfile);
        }
    }
}
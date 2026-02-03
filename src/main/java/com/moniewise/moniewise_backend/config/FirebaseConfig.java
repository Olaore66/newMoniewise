package com.moniewise.moniewise_backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// SERVER DEVELOPMENT
@Configuration
public class FirebaseConfig {

    @Bean
    public FirebaseApp firebaseApp() {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }

        try {
            String firebaseConfig = System.getenv("FIREBASE_SERVICE_ACCOUNT");
            InputStream serviceAccount;

            if (firebaseConfig != null && !firebaseConfig.isEmpty()) {
                serviceAccount = new ByteArrayInputStream(firebaseConfig.getBytes(StandardCharsets.UTF_8));
            } else {
                // Fallback to local file if Env Var is missing
                serviceAccount = getClass().getClassLoader().getResourceAsStream("serviceAccountKey.json");
            }

            if (serviceAccount == null) {
                System.err.println("❌ Firebase credentials not found. Notification features will be disabled.");
                return null;
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();

            return FirebaseApp.initializeApp(options);
        } catch (Exception e) {
            System.err.println("❌ Failed to initialize Firebase: " + e.getMessage());
            return null;
        }
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        if (firebaseApp == null) {
            // This prevents the "DEFAULT app doesn't exist" crash
            return null;
        }
        return FirebaseMessaging.getInstance(firebaseApp);
    }
}


//LOCAL DEVELOPMENT

//@Configuration
//public class FirebaseConfig {
//    @PostConstruct
//    public void initialize() {
//        try {
//            if (FirebaseApp.getApps().isEmpty()) {
//                // This looks for the file in src/main/resources
//                ClassPathResource resource = new ClassPathResource("serviceAccountKey.json");
//
//                FirebaseOptions options = FirebaseOptions.builder()
//                        .setCredentials(GoogleCredentials.fromStream(resource.getInputStream()))
//                        .build();
//
//                FirebaseApp.initializeApp(options);
//                System.out.println("✅ Firebase initialized successfully!");
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }
//
//    // 👇 ADD THIS BEAN DEFINITION 👇
//    @Bean
//    public FirebaseMessaging firebaseMessaging() {
//        // This makes the object available for @Autowired in your Service
//        return FirebaseMessaging.getInstance();
//    }
//}
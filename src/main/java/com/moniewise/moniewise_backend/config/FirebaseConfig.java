package com.moniewise.moniewise_backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// SERVER DEVELOPMENT
@Configuration
public class FirebaseConfig {

    @PostConstruct
    public void initialize() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {

                String firebaseConfig = System.getenv("FIREBASE_SERVICE_ACCOUNT");

                if (firebaseConfig == null) {
                    throw new IllegalStateException("FIREBASE_SERVICE_ACCOUNT env variable not set");
                }

                InputStream serviceAccount =
                        new ByteArrayInputStream(firebaseConfig.getBytes(StandardCharsets.UTF_8));

                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();

                FirebaseApp.initializeApp(options);
                System.out.println("✅ Firebase initialized successfully!");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Bean
    public FirebaseMessaging firebaseMessaging() {
        return FirebaseMessaging.getInstance();
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
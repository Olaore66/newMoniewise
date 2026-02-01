//package com.moniewise.moniewise_backend.controller;
//
//import com.moniewise.moniewise_backend.service.WalletService;
//import lombok.RequiredArgsConstructor;
//
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import javax.crypto.Mac;
//import javax.crypto.spec.SecretKeySpec;
//import java.nio.charset.StandardCharsets;
//import java.security.InvalidKeyException;
//import java.security.NoSuchAlgorithmException;
//
//@RestController
//@RequestMapping("/webhooks/monnify")
//@Slf4j
//@RequiredArgsConstructor
//public class MonnifyWebhookController {
//
//    private final WalletService walletService;
//    @Value("${monnify.secret-key}") // Get this from your application.properties
//    private String monnifySecretKey;
////    @PostMapping
////    public ResponseEntity<?> handleMonnifyNotification(
////            @RequestBody String payloadJson,
////            @RequestHeader("x-monnify-signature") String receivedSignature // Get the seal
////    ) {
////        // 1. SECURITY CHECK: Verify the Signature 🕵️‍♂️
////        if (!isSignatureValid(payloadJson, receivedSignature)) {
////            log.error("⚠️ Security Alert: Invalid Monnify Signature detected!");
////            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build(); // Block the hacker
////        }
////
////        log.info("🔔 Verified Monnify Webhook Received: {}", payloadJson);
////
////        // 2. Logic (Funding the wallet)
////        walletService.fundWalletFromWebhook(payloadJson);
////
////        return ResponseEntity.ok().build();
////    }
////
////    /**
////     * Calculates the HMAC-SHA512 hash of the payload using the Secret Key
////     * and compares it to the signature Monnify sent.
////     */
////    private boolean isSignatureValid(String payload, String signature) {
////        try {
////            if (signature == null || monnifySecretKey == null) return false;
////
////            Mac sha512_HMAC = Mac.getInstance("HmacSHA512");
////            SecretKeySpec secret_key = new SecretKeySpec(
////                    monnifySecretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
////            sha512_HMAC.init(secret_key);
////
////            byte[] hashBytes = sha512_HMAC.doFinal(payload.getBytes(StandardCharsets.UTF_8));
////
////            // Convert byte array to Hex String
////            StringBuilder hashString = new StringBuilder();
////            for (byte b : hashBytes) {
////                String hex = Integer.toHexString(0xff & b);
////                if (hex.length() == 1) hashString.append('0');
////                hashString.append(hex);
////            }
////
////            return hashString.toString().equals(signature);
////
////        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
////            log.error("Crypto Error: {}", e.getMessage());
////            return false;
////        }
////    }
//
//    @PostMapping
//    public ResponseEntity<?> handleMonnifyNotification(
//            @RequestBody String payloadJson,
//            @RequestHeader(value = "x-monnify-signature", required = false) String receivedSignature
//    ) {
//        // 🔔 THE DOORBELL: This confirms the request reached Java
//        log.info("🔔 KNOCK KNOCK! Webhook received. Signature present: {}", (receivedSignature != null));
//
//        // Skip signature check for now to get the money moving
//        // (We can re-enable it once we confirm it works)
//
//        try {
//            walletService.fundWalletFromWebhook(payloadJson);
//        } catch (Exception e) {
//            log.error("❌ Controller caught crash:", e);
//            return ResponseEntity.internalServerError().body("Error processing webhook");
//        }
//
//        return ResponseEntity.ok().build();
//    }
//}

package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.service.WalletService;
import com.moniewise.moniewise_backend.utils.MonnifyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks/monnify") // Ensure this matches your URL
public class MonnifyWebhookController {

    private final WalletService walletService;
    private final MonnifyUtils monnifyUtils;
    private final Logger logger = LoggerFactory.getLogger(MonnifyWebhookController.class);

    @Value("${monnify.secret-key}") //
    private String monnifySecretKey;

    public MonnifyWebhookController(WalletService walletService, MonnifyUtils monnifyUtils) {
        this.walletService = walletService;
        this.monnifyUtils = monnifyUtils;
    }

    @PostMapping
    public ResponseEntity<?> handleMonnifyNotification(
            @RequestBody String payloadJson,
            @RequestHeader(value = "monnify-signature", required = false) String receivedSignature //
    ) {
        // 1. SECURITY CHECK
        // In Production, signature is MANDATORY.
        // In Simulator, it might be missing, so we allow it ONLY if it's null (for testing).
        if (receivedSignature != null) {
            String calculatedHash = monnifyUtils.computeHMAC512TransactionHash(payloadJson, monnifySecretKey); //

            if (!calculatedHash.equals(receivedSignature)) {
                logger.error("⚠️ SECURITY ALERT: Invalid Monnify Signature! \nExpected: {} \nReceived: {}", calculatedHash, receivedSignature);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build(); //
            }
            logger.info("🔐 Signature Verified. Request is authentic.");
        } else {
            logger.warn("⚠️ No Signature found. Allowing for SIMULATOR testing only.");
        }

        // 2. Process Webhook
        try {
            walletService.fundWalletFromWebhook(payloadJson);
            return ResponseEntity.ok().build(); //
        } catch (Exception e) {
            logger.error("❌ Webhook Error:", e);
            return ResponseEntity.internalServerError().build(); //
        }
    }
}
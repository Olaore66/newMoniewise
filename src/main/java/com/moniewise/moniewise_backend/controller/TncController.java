package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.TncRequest;
import com.moniewise.moniewise_backend.dto.response.TncResponse;
import com.moniewise.moniewise_backend.service.UserService;
import org.apache.tomcat.util.net.openssl.ciphers.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;


@RestController
@RequestMapping("/tnc")
public class TncController {

    @Autowired
    private UserService userService;

    @GetMapping
    public ResponseEntity<?> getTnc() {
        TncResponse tnc = new TncResponse(
                "Moniewise helps you budget with discipline. Charges: ₦100 per 30 days, 5% on Emergency withdrawals, 2% on transfers (Safe Lock transfers free). We protect your data and don’t share bank details.",
                "1.0"
        );
        return ResponseEntity.ok(tnc);
    }

//    @PatchMapping
//    public ResponseEntity<?> acceptTnc(Authentication authentication, @RequestBody TncRequest request) {
//        userService.acceptTnc(authentication.getName(), request.isAccepted());
//        String message = request.isAccepted() ? "TnC accepted" : "TnC declined";
//        return ResponseEntity.ok(Map.of("message", message));
//    }

    @PatchMapping
    public ResponseEntity<?> acceptTnc(Principal principal, @RequestBody TncRequest request) {
        String email = principal.getName(); // this gives you the logged-in user's email/username
        userService.acceptTnc(email, request.isAccepted());
        String message = request.isAccepted() ? "TnC accepted" : "TnC declined";
        return ResponseEntity.ok(Map.of("message", message));
    }

}

package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.UserSummaryResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.service.BeneficiaryService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/beneficiaries")
public class BeneficiaryController {

    @Autowired
    private BeneficiaryService beneficiaryService;
    @Autowired private UserService userService; // To get ID from email

    @PostMapping
    public ResponseEntity<?> addBeneficiary(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> payload // { "email": "...", "alias": "..." }
    ) {
        User user = userService.findByEmail(email);
        beneficiaryService.addBeneficiary(user.getId(), payload.get("email"), payload.get("alias"));
        return ResponseEntity.ok("Beneficiary saved successfully");
    }

    @GetMapping
    public ResponseEntity<List<UserSummaryResponse>> getMyBeneficiaries(
            Authentication authentication // <--- Change this
    ) {
        // 1. Get the email safely from the Authentication object
        String email = authentication.getName();

        // 2. Now this will work because email is not null
        User user = userService.findByEmail(email);

        return ResponseEntity.ok(beneficiaryService.getMyBeneficiaries(user.getId()));
    }
}
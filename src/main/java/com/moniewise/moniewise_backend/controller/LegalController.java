// src/main/java/com/moniewise/moniewise_backend/controller/LegalController.java
package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.entity.LegalDocument;
import com.moniewise.moniewise_backend.repository.LegalDocumentRepository;
import com.moniewise.moniewise_backend.service.BudgetService;
import com.moniewise.moniewise_backend.service.LegalDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;



@RestController
@RequestMapping("/legal")
public class LegalController {

    private static final Logger logger = LoggerFactory.getLogger(LegalController.class);
    private final LegalDocumentService service;
    private final LegalDocumentRepository legalDocumentRepository;

    public LegalController(LegalDocumentService service, LegalDocumentRepository legalDocumentRepository) {
        this.service = service;
        this.legalDocumentRepository = legalDocumentRepository;
    }

    @GetMapping("/privacy-policy")
    public ResponseEntity<String> getPrivacyPolicy() {
        return ResponseEntity.ok(service.getContent("privacy_policy"));
    }

    @GetMapping("/terms")
    public ResponseEntity<String> getTerms() {
        return ResponseEntity.ok(service.getContent("terms_and_conditions"));
    }

    // ONLY ADMINS CAN UPDATE LEGAL DOCS
    @PutMapping("/privacy-policy")
    @PreAuthorize("hasRole('ADMIN')")  // ← Security: only admins
    public ResponseEntity<String> updatePrivacyPolicy(@RequestBody String newContent) {
        updateDocument("privacy_policy", newContent, "1.1");  // bump version as you like
        return ResponseEntity.ok("Privacy Policy updated successfully");
    }

    @PutMapping("/terms")
    @PreAuthorize("hasRole('ADMIN')")  // ← Only admins
    public ResponseEntity<String> updateTerms(@RequestBody String newContent) {
        updateDocument("terms_and_conditions", newContent, "1.1");
        return ResponseEntity.ok("Terms & Conditions updated successfully");
    }

    private void updateDocument(String docType, String newContent, String newVersion) {
        LegalDocument doc = legalDocumentRepository.findByDocTypeAndActiveTrue(docType)
                .orElseThrow(() -> new RuntimeException("Document not found: " + docType));

        doc.setContent(newContent.trim());
        doc.setVersion(newVersion);
        doc.setUpdatedAt(LocalDateTime.now());

        legalDocumentRepository.save(doc);

        logger.info("Legal document '{}' updated to version {}", docType, newVersion);
    }
}
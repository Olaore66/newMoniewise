// src/main/java/com/moniewise/moniewise_backend/service/LegalDocumentService.java
package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.LegalDocument;
import com.moniewise.moniewise_backend.repository.LegalDocumentRepository;
import org.springframework.stereotype.Service;

@Service
public class LegalDocumentService {
    private final LegalDocumentRepository repo;

    public LegalDocumentService(LegalDocumentRepository repo) {
        this.repo = repo;
    }

    public String getContent(String docType) {
        return repo.findByDocTypeAndActiveTrue(docType)
                .map(LegalDocument::getContent)
                .orElseThrow(() -> new RuntimeException("Document not found: " + docType));
    }
}
// src/main/java/com/moniewise/moniewise_backend/repository/LegalDocumentRepository.java
package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.LegalDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
@Repository
public interface LegalDocumentRepository extends JpaRepository<LegalDocument, Long> {
    Optional<LegalDocument> findByDocTypeAndActiveTrue(String docType);
}
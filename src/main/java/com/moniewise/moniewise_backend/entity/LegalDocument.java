package com.moniewise.moniewise_backend.entity;

import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "legal_documents")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class LegalDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_type", unique = true, nullable = false)
    private String docType;          // "privacy_policy" or "terms_and_conditions"

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    private String version = "1.0";

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "is_active")
    private boolean active = true;
}
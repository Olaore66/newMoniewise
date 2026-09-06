package com.moniewise.moniewise_backend.ai.sdk;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_conversation_threads")
@Getter
@Setter
public class AiConversationThread {
    @Id
    private String id;
    @Column(name = "user_email", nullable = false)
    private String userEmail;
    private String surface;
    private String title;
    @Column(columnDefinition = "TEXT")
    private String instructions;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
    @Column(name = "deleted_at")
    private Instant deletedAt;
}

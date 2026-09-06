package com.moniewise.moniewise_backend.ai.sdk;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_conversation_messages")
@Getter
@Setter
public class AiConversationMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "thread_id", nullable = false)
    private String threadId;
    @Column(name = "turn_id")
    private String turnId;
    private String role;
    @Column(name = "body", nullable = false)
    private String body;
    private boolean hidden;
    @Column(name = "stream_status")
    private String streamStatus;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}

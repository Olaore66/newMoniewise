package com.moniewise.moniewise_backend.ai.sdk;

import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "ai_prepared_actions")
@Getter
@Setter
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class AiPreparedActionEntity {
    @Id
    private String id;
    @Column(name = "thread_id", nullable = false)
    private String threadId;
    @Column(name = "user_email", nullable = false)
    private String userEmail;
    private String kind;
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params;
    @Column(name = "params_hash", nullable = false)
    private String paramsHash;
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> render;
    @Type(type = "jsonb")
    @Column(name = "editable_fields", columnDefinition = "jsonb")
    private List<String> editableFields;
    @Column(name = "requires_pin")
    private boolean requiresPin;
    private String status;
    private int version;
    @Column(name = "idempotency_key", unique = true, nullable = false)
    private String idempotencyKey;
    @Column(name = "prepared_by_agent")
    private String preparedByAgent;
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> evidence;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Column(name = "failure_code")
    private String failureCode;
    @Column(name = "execution_ref")
    private String executionRef;
}

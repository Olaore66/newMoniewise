package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.Type;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "analytics_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(columnDefinition = "jsonb")
    @Type(type = "jsonb")
    private String details;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();
}
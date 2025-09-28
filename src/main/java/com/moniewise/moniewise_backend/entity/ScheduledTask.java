package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "scheduled_tasks")
public class ScheduledTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "envelope_id", nullable = false)
    private Long envelopeId;

    @Column(name = "task_type", nullable = false)
    private String taskType; // e.g., "disbursement"

    @Column(name = "trigger_time", nullable = false)
    private LocalDateTime triggerTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // Constructors
    public ScheduledTask() {}
    public ScheduledTask(Long envelopeId, String taskType, LocalDateTime triggerTime) {
        this.envelopeId = envelopeId;
        this.taskType = taskType;
        this.triggerTime = triggerTime;
    }
}
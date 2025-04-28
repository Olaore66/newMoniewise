package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_badges", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "badge_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserBadge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "badge_id", nullable = false)
    private Badge badge;

    @Column(name = "earned_at", updatable = false)
    private Instant earnedAt = Instant.now();
}
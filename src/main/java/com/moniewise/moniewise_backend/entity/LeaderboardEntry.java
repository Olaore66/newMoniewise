package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "leaderboard_entries", uniqueConstraints = @UniqueConstraint(columnNames = {"leaderboard_id", "user_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LeaderboardEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "leaderboard_id", nullable = false)
    private Leaderboard leaderboard;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private BigDecimal score;

    @Column
    private Integer rank;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
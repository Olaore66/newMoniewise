package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.LeaderboardEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeaderboardEntryRepository extends JpaRepository<LeaderboardEntry, Long> {
    List<LeaderboardEntry> findByLeaderboardId(Long leaderboardId);
    List<LeaderboardEntry> findByUserId(Long userId);
}
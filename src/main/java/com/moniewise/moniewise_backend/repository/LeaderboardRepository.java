package com.moniewise.moniewise_backend.repository;


import com.moniewise.moniewise_backend.entity.Leaderboard;
import com.moniewise.moniewise_backend.enums.LeaderboardPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeaderboardRepository extends JpaRepository<Leaderboard, Long> {
    List<Leaderboard> findByPeriod(LeaderboardPeriod period);
}
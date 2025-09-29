package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Notification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(Long userId);
    List<Notification> findByUserIdAndTypeOrderByCreatedAtDesc(Long userId, String type);
    Optional<Notification> findByIdAndUserId(Long id, Long userId);
    void deleteByCreatedAtBefore(LocalDateTime threshold);
}
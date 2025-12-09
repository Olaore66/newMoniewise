package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.enums.TransactionType;
import org.springframework.data.domain.Page;
import com.moniewise.moniewise_backend.entity.Notification;
import com.moniewise.moniewise_backend.repository.NotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEPOSIT;
import static org.springframework.data.domain.Sort.Direction.DESC;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    @Autowired
    public NotificationController(NotificationRepository notificationRepository, UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<Page<Notification>> getNotifications(
            @AuthenticationPrincipal UserDetails userDetails,
            @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable) {
        Long userId = getUserIdFromUserDetails(userDetails);

        Page<Notification> page = notificationRepository.findByUserId(userId, pageable);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/unread")
    public ResponseEntity<List<Notification>> getUnreadNotifications(
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = getUserIdFromUserDetails(userDetails);

        // Only show HIGH-VALUE, USER-FACING notifications
        List<Notification> importantUnread = notificationRepository
                .findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId)
                .stream()
                .filter(this::isImportantNotification)
                .collect(Collectors.toList());

        return ResponseEntity.ok(importantUnread);
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<List<Notification>> getNotificationsByType(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String type) {
        Long userId = getUserIdFromUserDetails(userDetails);
        List<Notification> notifications = notificationRepository.findByUserIdAndTypeOrderByCreatedAtDesc(userId, type);
        return ResponseEntity.ok(notifications);
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Notification> markNotificationAsRead(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        Long userId = getUserIdFromUserDetails(userDetails);
        Optional<Notification> notificationOpt = notificationRepository.findByIdAndUserId(id, userId);
        if (notificationOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Notification notification = notificationOpt.get();
        notification.setRead(true);
        notificationRepository.save(notification);
        return ResponseEntity.ok(notification);
    }

    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllNotificationsAsRead(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserIdFromUserDetails(userDetails);
        List<Notification> unreadNotifications = notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId);
        unreadNotifications.forEach(notification -> notification.setRead(true));
        notificationRepository.saveAll(unreadNotifications);
        return ResponseEntity.ok().build();
    }

    private Long getUserIdFromUserDetails(UserDetails userDetails) {
        String username = userDetails.getUsername();
        return userRepository.findByEmail(username)
                .map(user -> user.getId())
                .orElseThrow(() -> new IllegalArgumentException("User not found for email: " + username));
    }


    // ———————————————————————————————————————
    // ONLY SHOW THESE — EVERYTHING ELSE IS NOISE
    // ———————————————————————————————————————
    private boolean isImportantNotification(Notification n) {
        return switch (n.getType()) {
            // Money actually moved — ALWAYS show
            case ENVELOPE_TRANSFER,
                    EXTERNAL_TRANSFER,
                    WALLET_DEPOSIT,
                    DISBURSEMENT_SUCCESS,
                    DISBURSEMENT_FAILED,
                    DISBURSEMENT,
                    REFUND_ISSUED,
                    BUDGET_CREATION,           // ← ADD THIS LINE
                    BUDGET_CREATION_SUCCESS,
                    BUDGET_EXPIRED,
                    ENVELOPE_LOCKED,
                    ENVELOPE_UNLOCKED,
                    LOW_BALANCE_WARNING,
                    PRE_DISBURSEMENT,
                    GOAL_ACHIEVED -> true;

            // NEVER show these — they are system spam
            case  ENVELOPE_CREATED,           // Too noisy
                    ENVELOPE_UPDATED,
                    BUDGET_UPDATED,
                    DISBURSEMENT_REMINDER,
                    SYSTEM -> false;

            // Default: hide unknown types (safe)
            default -> false;
        };
    }

}
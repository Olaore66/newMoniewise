package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.NotificationBulkReadResponse;
import com.moniewise.moniewise_backend.entity.Notification;
import com.moniewise.moniewise_backend.repository.NotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

import static org.springframework.data.domain.Sort.Direction.DESC;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Autowired
    public NotificationController(
            NotificationRepository notificationRepository,
            UserRepository userRepository,
            NotificationService notificationService
    ) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<Page<Notification>> getNotifications(
            @AuthenticationPrincipal UserDetails userDetails,
            @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable) {
        Long userId = getUserIdFromUserDetails(userDetails);
        // Just return what is in the DB. The Service already filtered the junk.
        return ResponseEntity.ok(notificationRepository.findByUserId(userId, pageable));
    }

    @GetMapping("/unread")
    public ResponseEntity<List<Notification>> getUnreadNotifications(
            @AuthenticationPrincipal UserDetails userDetails,
            @PageableDefault(size = 10, sort = "createdAt", direction = DESC) Pageable pageable) {
        Long userId = getUserIdFromUserDetails(userDetails);
        return ResponseEntity.ok(
                notificationRepository.findByUserIdAndIsReadFalse(userId, pageable).getContent()
        );
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
    public ResponseEntity<NotificationBulkReadResponse> markAllNotificationsAsRead(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        Long userId = getUserIdFromUserDetails(userDetails);
        return ResponseEntity.ok(notificationService.markAllNotificationsAsRead(userId));
    }

    private Long getUserIdFromUserDetails(UserDetails userDetails) {
        String username = userDetails.getUsername();
        return userRepository.findByEmail(username)
                .map(user -> user.getId())
                .orElseThrow(() -> new IllegalArgumentException("User not found for email: " + username));
    }

}


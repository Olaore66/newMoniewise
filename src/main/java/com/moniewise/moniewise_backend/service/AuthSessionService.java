package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.AuthSession;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.AuthSessionRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthSessionService {

    private final AuthSessionRepository authSessionRepository;
    private final UserRepository userRepository;

    public AuthSessionService(AuthSessionRepository authSessionRepository,
                              UserRepository userRepository) {
        this.authSessionRepository = authSessionRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public String createSession(User user) {
        // Serialize session rotation per user so duplicate/retried login requests
        // queue instead of inserting two active rows.
        User lockedUser = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalStateException("User not found while creating session"));

        LocalDateTime now = LocalDateTime.now();
        authSessionRepository.revokeAllSessionsForUser(lockedUser.getId(), now);

        AuthSession session = new AuthSession();
        session.setUser(lockedUser);
        session.setSessionId(UUID.randomUUID().toString());
        session.setCreatedAt(now);
        session.setLastSeenAt(now);
        session.setRevoked(false);
        authSessionRepository.save(session);
        return session.getSessionId();
    }

    public record PushTarget(String token, String devicePlatform) {}

    @Transactional(readOnly = true)
    public boolean isSessionActive(String email, String sessionId) {
        return authSessionRepository.findByUserEmailAndSessionIdAndRevokedFalse(email, sessionId).isPresent();
    }

    @Transactional
    public void revokeSession(String sessionId) {
        authSessionRepository.revokeSession(sessionId, LocalDateTime.now());
    }

    @Transactional
    public void attachFcmToken(String email, String sessionId, String token) {
        attachFcmToken(email, sessionId, token, null);
    }

    @Transactional
    public void attachFcmToken(String email, String sessionId, String token, String devicePlatform) {
        AuthSession session = authSessionRepository.findByUserEmailAndSessionIdAndRevokedFalse(email, sessionId)
                .orElseThrow(() -> new IllegalStateException("Active session not found"));

        authSessionRepository.clearTokenFromOtherSessions(token, sessionId);
        session.setFcmToken(token);
        session.setDevicePlatform(normalizeDevicePlatform(devicePlatform));
        session.setLastSeenAt(LocalDateTime.now());
        authSessionRepository.save(session);
    }

    @Transactional
    public void clearSessionFcmToken(String email, String sessionId) {
        AuthSession session = authSessionRepository.findByUserEmailAndSessionIdAndRevokedFalse(email, sessionId)
                .orElseThrow(() -> new IllegalStateException("Active session not found"));
        session.setFcmToken(null);
        session.setDevicePlatform(null);
        session.setLastSeenAt(LocalDateTime.now());
        authSessionRepository.save(session);
    }

    @Transactional
    public void clearSessionFcmTokenByValue(String email, String sessionId, String token) {
        AuthSession session = authSessionRepository.findByUserEmailAndSessionIdAndRevokedFalse(email, sessionId)
                .orElseThrow(() -> new IllegalStateException("Active session not found"));

        if (token == null || token.isBlank() || token.equals(session.getFcmToken())) {
            session.setFcmToken(null);
            session.setDevicePlatform(null);
            session.setLastSeenAt(LocalDateTime.now());
            authSessionRepository.save(session);
        }
    }

    @Transactional(readOnly = true)
    public List<String> getActiveFcmTokens(Long userId) {
        return authSessionRepository.findActiveFcmTokensByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<PushTarget> getActivePushTargets(Long userId) {
        return authSessionRepository.findActivePushSessionsByUserId(userId).stream()
                .map(session -> new PushTarget(session.getFcmToken(), session.getDevicePlatform()))
                .toList();
    }

    @Transactional
    public void clearDeadFcmToken(String token) {
        authSessionRepository.clearFcmTokenByToken(token);
    }

    private String normalizeDevicePlatform(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ANDROID" -> "ANDROID";
            case "IOS", "IPHONE", "IPAD", "IPADOS" -> "IOS";
            default -> null;
        };
    }
}

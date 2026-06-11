package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.AuthSession;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.AuthSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AuthSessionService {

    private final AuthSessionRepository authSessionRepository;

    public AuthSessionService(AuthSessionRepository authSessionRepository) {
        this.authSessionRepository = authSessionRepository;
    }

    @Transactional
    public String createSession(User user) {
        // revokeAllSessionsForUser is an atomic @Modifying UPDATE — no row-level
        // lock is needed here. The previous SELECT FOR UPDATE was redundant and
        // caused unnecessary lock contention on the users table during login.
        authSessionRepository.revokeAllSessionsForUser(user.getId(), LocalDateTime.now());

        AuthSession session = new AuthSession();
        session.setUser(user);
        session.setSessionId(UUID.randomUUID().toString());
        session.setCreatedAt(LocalDateTime.now());
        session.setLastSeenAt(LocalDateTime.now());
        session.setRevoked(false);
        authSessionRepository.save(session);
        return session.getSessionId();
    }

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
        AuthSession session = authSessionRepository.findByUserEmailAndSessionIdAndRevokedFalse(email, sessionId)
                .orElseThrow(() -> new IllegalStateException("Active session not found"));

        authSessionRepository.clearTokenFromOtherSessions(token, sessionId);
        session.setFcmToken(token);
        session.setLastSeenAt(LocalDateTime.now());
        authSessionRepository.save(session);
    }

    @Transactional
    public void clearSessionFcmToken(String email, String sessionId) {
        AuthSession session = authSessionRepository.findByUserEmailAndSessionIdAndRevokedFalse(email, sessionId)
                .orElseThrow(() -> new IllegalStateException("Active session not found"));
        session.setFcmToken(null);
        session.setLastSeenAt(LocalDateTime.now());
        authSessionRepository.save(session);
    }

    @Transactional
    public void clearSessionFcmTokenByValue(String email, String sessionId, String token) {
        AuthSession session = authSessionRepository.findByUserEmailAndSessionIdAndRevokedFalse(email, sessionId)
                .orElseThrow(() -> new IllegalStateException("Active session not found"));

        if (token == null || token.isBlank() || token.equals(session.getFcmToken())) {
            session.setFcmToken(null);
            session.setLastSeenAt(LocalDateTime.now());
            authSessionRepository.save(session);
        }
    }

    @Transactional(readOnly = true)
    public List<String> getActiveFcmTokens(Long userId) {
        return authSessionRepository.findActiveFcmTokensByUserId(userId);
    }

    @Transactional
    public void clearDeadFcmToken(String token) {
        authSessionRepository.clearFcmTokenByToken(token);
    }
}

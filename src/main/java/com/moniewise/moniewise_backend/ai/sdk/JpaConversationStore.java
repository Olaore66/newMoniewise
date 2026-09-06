package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.chat.MonnieMessage;
import com.moniewise.monnie.api.model.UserRef;
import com.moniewise.monnie.api.port.ConversationStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class JpaConversationStore implements ConversationStore {

    @PersistenceContext
    private EntityManager em;

    /**
     * {@inheritDoc}
     *
     * <p>Every REST path calls {@link #ensureForTurn} first, which resolves or creates the
     * thread and 404s on someone else's id, so by the time the engine calls this the id is
     * always owned and live. An unresolvable id here therefore means the turn engine was
     * handed something the service layer never validated: fail loudly rather than silently
     * forking a second conversation the user will never find.
     */
    @Override
    @Transactional
    public ThreadRef ensureThread(UserRef user, String threadId, String surface) {
        if (threadId != null && !threadId.isBlank()) {
            AiConversationThread existing = em.find(AiConversationThread.class, threadId);
            if (existing == null || !existing.getUserEmail().equals(user.principal())
                    || existing.getDeletedAt() != null) {
                throw new IllegalStateException(
                        "turn referenced a thread that is not owned or is deleted: " + threadId);
            }
            return new ThreadRef(existing.getId(), existing.getTitle(), false);
        }
        AiConversationThread created = newThread(user.principal(),
                SurfaceGuidance.normalise(surface), null, null);
        em.persist(created);
        return new ThreadRef(created.getId(), created.getTitle(), true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MonnieMessage> history(UserRef user, String threadId, int maxMessages) {
        AiConversationThread thread = em.find(AiConversationThread.class, threadId);
        if (thread == null || !thread.getUserEmail().equals(user.principal())
                || thread.getDeletedAt() != null) {
            return List.of();
        }
        List<AiConversationMessage> rows = em.createQuery(
                        "SELECT m FROM AiConversationMessage m WHERE m.threadId = :tid "
                                + "AND m.hidden = false AND m.streamStatus <> 'FAILED' ORDER BY m.id",
                        AiConversationMessage.class)
                .setParameter("tid", threadId)
                .getResultList();
        int from = Math.max(0, rows.size() - maxMessages);
        return rows.subList(from, rows.size()).stream()
                .map(row -> "USER".equals(row.getRole())
                        ? (MonnieMessage) new MonnieMessage.User(row.getBody())
                        : new MonnieMessage.Assistant(row.getBody(), List.of()))
                .toList();
    }

    @Override
    @Transactional
    public String appendUserMessage(UserRef user, String threadId, String turnId,
                                    String text, boolean hidden) {
        String id = append(threadId, turnId, "USER", text, hidden, "COMPLETE");
        touchThread(threadId, text, hidden);
        return id;
    }

    @Override
    @Transactional
    public String appendAssistantMessage(UserRef user, String threadId, String turnId,
                                         String text, StreamStatus status) {
        String id = append(threadId, turnId, "ASSISTANT", text, false, status.name());
        touchThread(threadId, null, true);
        return id;
    }

    @Transactional
    public AiConversationThread createOwned(String email, String surface, String title,
                                            String instructions) {
        // Canonical upper-case, so a client sending "onboarding" gets the onboarding
        // starter and still matches its own ?surface=ONBOARDING listing filter.
        String resolvedSurface = SurfaceGuidance.normalise(surface);
        String resolvedTitle = title == null || title.isBlank() ? resolvedSurface : title.trim();
        AiConversationThread created = newThread(email, resolvedSurface, resolvedTitle, instructions);
        em.persist(created);
        return created;
    }

    @Transactional
    public AiConversationThread ensureForTurn(String email, String threadId, String surface,
                                              String incomingInstructions) {
        if (threadId != null && !threadId.isBlank()) {
            AiConversationThread existing = requireOwned(threadId, email);
            if (InstructionPolicy.acceptIncoming(existing.getInstructions(), incomingInstructions)) {
                existing.setInstructions(incomingInstructions);
                existing.setUpdatedAt(Instant.now());
            }
            return existing;
        }
        return createOwned(email, surface, null, incomingInstructions);
    }

    @Transactional(readOnly = true)
    public AiConversationThread requireOwned(String threadId, String email) {
        AiConversationThread thread = em.find(AiConversationThread.class, threadId);
        if (thread == null || !thread.getUserEmail().equals(email) || thread.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found.");
        }
        return thread;
    }

    @Transactional(readOnly = true)
    public List<AiConversationThread> listOwned(String email, String surface, Instant before,
                                                int limit) {
        StringBuilder jpql = new StringBuilder(
                "SELECT t FROM AiConversationThread t WHERE t.userEmail = :email AND t.deletedAt IS NULL");
        if (surface != null && !surface.isBlank()) {
            jpql.append(" AND t.surface = :surface");
        }
        if (before != null) {
            jpql.append(" AND t.updatedAt < :before");
        }
        jpql.append(" ORDER BY t.updatedAt DESC");
        var query = em.createQuery(jpql.toString(), AiConversationThread.class)
                .setParameter("email", email)
                .setMaxResults(limit);
        if (surface != null && !surface.isBlank()) {
            query.setParameter("surface", surface.trim());
        }
        if (before != null) {
            query.setParameter("before", before);
        }
        return query.getResultList();
    }

    @Transactional(readOnly = true)
    public Map<String, String> lastVisibleBodies(List<String> threadIds) {
        if (threadIds.isEmpty()) {
            return Map.of();
        }
        List<AiConversationMessage> rows = em.createQuery(
                        "SELECT m FROM AiConversationMessage m WHERE m.threadId IN :ids "
                                + "AND m.hidden = false ORDER BY m.id DESC",
                        AiConversationMessage.class)
                .setParameter("ids", threadIds)
                .getResultList();
        Map<String, String> last = new LinkedHashMap<>();
        for (AiConversationMessage row : rows) {
            last.putIfAbsent(row.getThreadId(), row.getBody());
        }
        return last;
    }

    @Transactional(readOnly = true)
    public List<AiConversationMessage> visibleMessages(String threadId, Long afterId, int limit) {
        StringBuilder jpql = new StringBuilder(
                "SELECT m FROM AiConversationMessage m WHERE m.threadId = :tid AND m.hidden = false");
        if (afterId != null) {
            jpql.append(" AND m.id > :after");
        }
        jpql.append(" ORDER BY m.id ASC");
        var query = em.createQuery(jpql.toString(), AiConversationMessage.class)
                .setParameter("tid", threadId)
                .setMaxResults(limit);
        if (afterId != null) {
            query.setParameter("after", afterId);
        }
        return query.getResultList();
    }

    @Transactional
    public AiConversationThread patchOwned(String threadId, String email, String title,
                                           String instructions) {
        AiConversationThread thread = requireOwned(threadId, email);
        if (title != null && !title.isBlank()) {
            thread.setTitle(title.trim());
        }
        if (instructions != null) {
            if (thread.getInstructions() != null && !thread.getInstructions().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This thread already has instructions and they cannot be changed. "
                                + "Create a new thread to use different guidance.");
            }
            thread.setInstructions(instructions);
        }
        thread.setUpdatedAt(Instant.now());
        return thread;
    }

    @Transactional
    public void softDeleteOwned(String threadId, String email) {
        AiConversationThread thread = requireOwned(threadId, email);
        thread.setDeletedAt(Instant.now());
        thread.setUpdatedAt(Instant.now());
    }

    private void touchThread(String threadId, String userText, boolean skipTitle) {
        AiConversationThread thread = em.find(AiConversationThread.class, threadId);
        if (thread == null) {
            return;
        }
        thread.setUpdatedAt(Instant.now());
        if (!skipTitle && userText != null && !userText.isBlank()
                && isDefaultTitle(thread)) {
            thread.setTitle(truncateTitle(userText));
        }
    }

    private static boolean isDefaultTitle(AiConversationThread thread) {
        String title = thread.getTitle();
        return title == null || title.isBlank() || title.equals(thread.getSurface());
    }

    private static String truncateTitle(String text) {
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 77) + "...";
    }

    private static AiConversationThread newThread(String email, String surface, String title,
                                                  String instructions) {
        Instant now = Instant.now();
        AiConversationThread created = new AiConversationThread();
        created.setId(UUID.randomUUID().toString());
        created.setUserEmail(email);
        created.setSurface(surface == null ? "CHAT" : surface);
        created.setTitle(title);
        created.setInstructions(instructions);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        return created;
    }

    private String append(String threadId, String turnId, String role, String text,
                          boolean hidden, String streamStatus) {
        AiConversationMessage row = new AiConversationMessage();
        row.setThreadId(threadId);
        row.setTurnId(turnId);
        row.setRole(role);
        row.setBody(text == null ? "" : text);
        row.setHidden(hidden);
        row.setStreamStatus(streamStatus);
        em.persist(row);
        em.flush();
        return String.valueOf(row.getId());
    }
}

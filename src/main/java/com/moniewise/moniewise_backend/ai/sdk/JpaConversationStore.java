package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.chat.MonnieMessage;
import com.moniewise.monnie.api.model.UserRef;
import com.moniewise.monnie.api.port.ConversationStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;

@Component
public class JpaConversationStore implements ConversationStore {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public ThreadRef ensureThread(UserRef user, String threadId, String surface) {
        if (threadId != null) {
            AiConversationThread existing = em.find(AiConversationThread.class, threadId);
            if (existing != null && existing.getUserEmail().equals(user.principal())) {
                return new ThreadRef(existing.getId(), existing.getTitle(), false);
            }
        }
        AiConversationThread created = new AiConversationThread();
        created.setId(UUID.randomUUID().toString());
        created.setUserEmail(user.principal());
        created.setSurface(surface == null ? "CHAT" : surface);
        created.setTitle(surface);
        em.persist(created);
        return new ThreadRef(created.getId(), created.getTitle(), true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MonnieMessage> history(UserRef user, String threadId, int maxMessages) {
        AiConversationThread thread = em.find(AiConversationThread.class, threadId);
        if (thread == null || !thread.getUserEmail().equals(user.principal())) {
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
        return append(threadId, turnId, "USER", text, hidden, "COMPLETE");
    }

    @Override
    @Transactional
    public String appendAssistantMessage(UserRef user, String threadId, String turnId,
                                         String text, StreamStatus status) {
        return append(threadId, turnId, "ASSISTANT", text, false, status.name());
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

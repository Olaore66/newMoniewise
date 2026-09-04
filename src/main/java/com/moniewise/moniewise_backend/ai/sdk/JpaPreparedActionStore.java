package com.moniewise.moniewise_backend.ai.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.monnie.api.action.ActionKind;
import com.moniewise.monnie.api.action.ActionStatus;
import com.moniewise.monnie.api.action.PreparedAction;
import com.moniewise.monnie.api.action.RenderSpec;
import com.moniewise.monnie.api.model.UserRef;
import com.moniewise.monnie.api.port.PreparedActionStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class JpaPreparedActionStore implements PreparedActionStore {

    @PersistenceContext
    private EntityManager em;
    private final ObjectMapper mapper;

    public JpaPreparedActionStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public PreparedAction save(PreparedAction action) {
        Optional<PreparedAction> existing = findByIdempotencyKey(action.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        em.persist(toEntity(action));
        return action;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PreparedAction> findById(String actionId) {
        AiPreparedActionEntity entity = em.find(AiPreparedActionEntity.class, actionId);
        return Optional.ofNullable(entity).map(this::fromEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PreparedAction> findByIdForUser(String actionId, UserRef user) {
        return findById(actionId).filter(action -> action.user().equals(user));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PreparedAction> findByIdempotencyKey(String idempotencyKey) {
        List<AiPreparedActionEntity> rows = em.createQuery(
                        "SELECT e FROM AiPreparedActionEntity e WHERE e.idempotencyKey = :k",
                        AiPreparedActionEntity.class)
                .setParameter("k", idempotencyKey)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromEntity(rows.get(0)));
    }

    @Override
    @Transactional
    public boolean compareAndSetStatus(String actionId, int expectedVersion,
                                       ActionStatus from, ActionStatus to) {
        int updated = em.createQuery(
                        "UPDATE AiPreparedActionEntity e SET e.status = :to, e.version = e.version + 1 "
                                + "WHERE e.id = :id AND e.version = :ver AND e.status = :from")
                .setParameter("to", to.name())
                .setParameter("id", actionId)
                .setParameter("ver", expectedVersion)
                .setParameter("from", from.name())
                .executeUpdate();
        em.flush();
        em.clear();
        return updated == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PreparedAction> findPendingByThread(String threadId) {
        return em.createQuery(
                        "SELECT e FROM AiPreparedActionEntity e WHERE e.threadId = :t AND e.status = 'PENDING'",
                        AiPreparedActionEntity.class)
                .setParameter("t", threadId)
                .getResultList()
                .stream()
                .map(this::fromEntity)
                .toList();
    }

    /**
     * Owner-scoped action listing for the REST layer.
     *
     * <p>Not part of {@code PreparedActionStore}: the SDK's own port is thread-scoped
     * because that is all the turn engine needs. A user asking "what am I waiting to
     * confirm?" spans threads, and filtering in memory after loading every action would
     * scale with the user's whole history rather than with the answer.
     *
     * @param threadId optional extra narrowing; null means every thread
     */
    @Transactional(readOnly = true)
    public List<PreparedAction> findByUserAndStatus(String email, ActionStatus status,
                                                    String threadId, int limit) {
        StringBuilder jpql = new StringBuilder(
                "SELECT e FROM AiPreparedActionEntity e WHERE e.userEmail = :email AND e.status = :status");
        if (threadId != null && !threadId.isBlank()) {
            jpql.append(" AND e.threadId = :threadId");
        }
        jpql.append(" ORDER BY e.createdAt DESC");
        var query = em.createQuery(jpql.toString(), AiPreparedActionEntity.class)
                .setParameter("email", email)
                .setParameter("status", status.name())
                .setMaxResults(limit);
        if (threadId != null && !threadId.isBlank()) {
            query.setParameter("threadId", threadId);
        }
        return query.getResultList().stream().map(this::fromEntity).toList();
    }

    @Override
    @Transactional
    public int supersedePending(String threadId, ActionKind kind, String exceptActionId) {
        return em.createQuery(
                        "UPDATE AiPreparedActionEntity e SET e.status = 'SUPERSEDED', e.version = e.version + 1 "
                                + "WHERE e.threadId = :t AND e.kind = :k AND e.status = 'PENDING' AND e.id <> :id")
                .setParameter("t", threadId)
                .setParameter("k", kind.name())
                .setParameter("id", exceptActionId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public int expireOlderThan(Instant cutoff) {
        return em.createQuery(
                        "UPDATE AiPreparedActionEntity e SET e.status = 'EXPIRED', e.version = e.version + 1 "
                                + "WHERE e.status = 'PENDING' AND e.expiresAt < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }

    @Override
    @Transactional
    public PreparedAction update(PreparedAction action) {
        em.merge(toEntity(action));
        return action;
    }

    private AiPreparedActionEntity toEntity(PreparedAction action) {
        AiPreparedActionEntity entity = new AiPreparedActionEntity();
        entity.setId(action.id());
        entity.setThreadId(action.threadId());
        entity.setUserEmail(action.user().principal());
        entity.setKind(action.kind().name());
        entity.setParams(Map.copyOf(action.params()));
        entity.setParamsHash(action.paramsHash());
        entity.setRender(mapper.convertValue(action.render(), Map.class));
        entity.setEditableFields(List.copyOf(action.editableFields()));
        entity.setRequiresPin(action.requiresPin());
        entity.setStatus(action.status().name());
        entity.setVersion(action.version());
        entity.setIdempotencyKey(action.idempotencyKey());
        entity.setPreparedByAgent(action.preparedByAgent());
        entity.setEvidence(Map.copyOf(action.evidence()));
        entity.setCreatedAt(action.createdAt());
        entity.setExpiresAt(action.expiresAt());
        entity.setResolvedAt(action.resolvedAt());
        entity.setFailureCode(action.failureCode());
        entity.setExecutionRef(action.executionRef());
        return entity;
    }

    private PreparedAction fromEntity(AiPreparedActionEntity entity) {
        RenderSpec render;
        if (entity.getRender() == null || entity.getRender().isEmpty()) {
            render = RenderSpec.builder("Confirm").build();
        } else {
            render = mapper.convertValue(entity.getRender(), RenderSpec.class);
        }
        return new PreparedAction(
                entity.getId(),
                entity.getThreadId(),
                UserRef.of(entity.getUserEmail()),
                ActionKind.valueOf(entity.getKind()),
                entity.getParams() == null ? Map.of() : entity.getParams(),
                entity.getParamsHash(),
                render,
                entity.getEditableFields(),
                entity.isRequiresPin(),
                ActionStatus.valueOf(entity.getStatus()),
                entity.getVersion(),
                entity.getIdempotencyKey(),
                entity.getPreparedByAgent(),
                entity.getEvidence() == null ? Map.of() : entity.getEvidence(),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.getResolvedAt(),
                entity.getFailureCode(),
                entity.getExecutionRef());
    }
}

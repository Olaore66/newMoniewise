package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.ScheduledTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
@Repository
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {
//    @Query("SELECT st FROM ScheduledTask st WHERE st.triggerTime <= :now")
//    List<ScheduledTask> findTasksDueBy(LocalDateTime now);


    // ✅ NEW: Safe Batch Method (Returns a Page, not a huge List)
    @Query("SELECT st FROM ScheduledTask st WHERE st.triggerTime <= :now")
    Page<ScheduledTask> findTasksDueBy(@Param("now") LocalDateTime now, Pageable pageable);

    void deleteByEnvelopeId(Long envelopeId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ScheduledTask st WHERE st.triggerTime < :threshold")
    void deleteByTriggerTimeBefore(LocalDateTime threshold);



    // New method to fix the error
    List<ScheduledTask> findByEnvelopeId(Long envelopeId);

    @Query("SELECT st FROM ScheduledTask st " +
            "WHERE st.envelopeId = :envelopeId " +
            "AND st.taskType = 'DISBURSEMENT' " +
            "AND st.triggerTime > :now " +
            "ORDER BY st.triggerTime ASC")
    List<ScheduledTask> findNextDisbursementTask(Long envelopeId, LocalDateTime now);


    void deleteByEnvelopeIdAndTaskType(Long id, String disbursement);

    // 👇 ADD THIS NEW SAFE DELETE METHOD 👇
    @Modifying
    @Transactional
    @Query("DELETE FROM ScheduledTask s WHERE s.id IN :ids")
    void deleteTasksSafely(@Param("ids") List<Long> ids);
}
package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.PayeelordVasTransaction;
import com.moniewise.moniewise_backend.enums.VasTransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PayeelordVasTransactionRepository extends JpaRepository<PayeelordVasTransaction, Long> {

    Optional<PayeelordVasTransaction> findByReference(String reference);

    Optional<PayeelordVasTransaction> findByPayeelordTransactionId(String payeelordTransactionId);

    List<PayeelordVasTransaction> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("SELECT SUM(v.sellingAmount) FROM PayeelordVasTransaction v " +
           "WHERE v.envelopeId = :envelopeId AND v.status = :status AND v.createdAt >= :since")
    BigDecimal sumSettledSellingAmount(@Param("envelopeId") Long envelopeId,
                                       @Param("status") VasTransactionStatus status,
                                       @Param("since") LocalDateTime since);
}

package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.Withdrawal;
import com.moniewise.moniewise_backend.enums.WithdrawalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {
    Optional<Withdrawal> findByClientReference(String clientReference);
    Optional<Withdrawal> findByProviderReference(String providerReference);
    List<Withdrawal> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    /** True if the user has an in-flight (not yet settled) withdrawal — used by
     *  account deletion to wait for an async transfer to finish before closing. */
    boolean existsByUserIdAndStatusIn(Long userId, Collection<WithdrawalStatus> statuses);

    /**
     * Feeds the "transferred before" auto-suggest dropdown on the Transfer to
     * Bank screen — pulls the user's most recent successfully-completed
     * withdrawals so {@code WalletService.getRecentRecipients} can dedupe them
     * by destination (bankCode + accountNumber) and surface the most-recent
     * accounts the user has actually sent money to. Capped at 50 so the dedupe
     * pass has enough history to find ~10 distinct destinations without
     * scanning the user's entire withdrawal history.
     */
    List<Withdrawal> findTop50ByUserIdAndStatusOrderByCreatedAtDesc(Long userId, WithdrawalStatus status);
}

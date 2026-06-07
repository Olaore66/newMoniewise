package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.UserSummary;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.transaction.Transactional;
import javax.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByPhone(String phone);
    Optional<User> findByEmailOrPhone(String email, String phone);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    // Custom query to find ANY user (Active or Deleted)
    @Query(value = "SELECT * FROM users WHERE email = :email", nativeQuery = true)
    Optional<User> findGlobalByEmail(@Param("email") String email);

//     ✅ FIXED: Native Query (Bypasses Hibernate HQL parser errors)
//     Uses Postgres JSON operator (->>) to extract text directly.
//     Note: We provide a countQuery to ensure pagination works efficiently.
@Query(value = "SELECT " +
        "u.id AS id, " +
        "u.profile_data ->> 'firstName' AS firstName, " +
        "u.profile_data ->> 'lastName' AS lastName, " +
        "u.email AS email, " +
        "u.profile_data ->> 'userTag' AS userTag, " +
        "u.profile_image_url AS profileImageUrl, " +
        "w.account_number AS walletAccountNumber, " +
        "w.bank_name AS walletBankName, " +
        "w.status AS walletStatus, " +
        "w.provider_name AS walletProviderName, " +
        "u.bvn AS bvn " +
        "FROM users u " +
        "LEFT JOIN wallets w ON w.user_id = u.id " +
        "WHERE (" +
        "   lower(coalesce(u.email, '')) LIKE lower(concat('%', :query, '%')) " +
        "   OR coalesce(u.phone, '') LIKE concat('%', :query, '%') " +
        "   OR lower(coalesce(u.profile_data ->> 'firstName', '')) LIKE lower(concat('%', :query, '%')) " +
        "   OR lower(coalesce(u.profile_data ->> 'lastName', '')) LIKE lower(concat('%', :query, '%')) " +
        "   OR lower(coalesce(u.profile_data ->> 'userTag', '')) LIKE lower(concat('%', :query, '%'))" +
        ") AND coalesce(u.is_deleted, false) = false",
        countQuery = "SELECT count(*) FROM users u WHERE (" +
                "   lower(coalesce(u.email, '')) LIKE lower(concat('%', :query, '%')) " +
                "   OR coalesce(u.phone, '') LIKE concat('%', :query, '%') " +
                "   OR lower(coalesce(u.profile_data ->> 'firstName', '')) LIKE lower(concat('%', :query, '%')) " +
                "   OR lower(coalesce(u.profile_data ->> 'lastName', '')) LIKE lower(concat('%', :query, '%')) " +
                "   OR lower(coalesce(u.profile_data ->> 'userTag', '')) LIKE lower(concat('%', :query, '%'))" +
                ") AND coalesce(u.is_deleted, false) = false",
        nativeQuery = true)
List<UserSummary> searchUsers(@Param("query") String query, Pageable pageable);

//    @Query(value = "SELECT " +
//            "u.id AS id, " +
//            "u.profile_data ->> 'firstName' AS firstName, " +
//            "u.profile_data ->> 'lastName' AS lastName, " +
//            "u.email AS email, " +
//            "u.profile_data ->> 'userTag' AS userTag, " +
//            "u.profile_image_url AS profileImageUrl " +
//            "FROM users u " +
//            "WHERE (" +
//            "   LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) " +
//            "   OR u.phone LIKE CONCAT('%', :query, '%') " +  // 👈 Search Phone
//            "   OR LOWER(u.profile_data ->> 'firstName') LIKE LOWER(CONCAT('%', :query, '%')) " +
//            "   OR LOWER(u.profile_data ->> 'lastName') LIKE LOWER(CONCAT('%', :query, '%')) " +
//            "   OR LOWER(u.profile_data ->> 'userTag') LIKE LOWER(CONCAT('%', :query, '%'))" + // 👈 Search Tag
//            ") " +
//            "AND u.email NOT IN (:excludedEmails) " + // 👈 Exclude Self & Revenue
//            "AND u.deleted = false",
//
//            // Count Query is mandatory for Pageable in Native Queries
//            countQuery = "SELECT count(*) FROM users u WHERE (" +
//                    "   LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) " +
//                    "   OR u.phone LIKE CONCAT('%', :query, '%') " +
//                    "   OR LOWER(u.profile_data ->> 'firstName') LIKE LOWER(CONCAT('%', :query, '%')) " +
//                    "   OR LOWER(u.profile_data ->> 'lastName') LIKE LOWER(CONCAT('%', :query, '%')) " +
//                    "   OR LOWER(u.profile_data ->> 'userTag') LIKE LOWER(CONCAT('%', :query, '%'))" +
//                    ") AND u.email NOT IN (:excludedEmails) AND u.deleted = false",
//            nativeQuery = true)
//    List<UserSummary> searchUsers(
//            @Param("query") String query,
//            @Param("excludedEmails") List<String> excludedEmails,
//            Pageable pageable
//    );

    // In UserRepository.java

    // ✅ FIXED: Uses COALESCE to handle NULLs and removes the List parameter complexity
//    @Query(value = "SELECT " +
//            "u.id AS id, " +
//            "u.profile_data ->> 'firstName' AS firstName, " +
//            "u.profile_data ->> 'lastName' AS lastName, " +
//            "u.email AS email, " +
//            "u.profile_data ->> 'userTag' AS userTag, " +
//            "u.profile_image_url AS profileImageUrl " +
//            "FROM users u " +
//            "WHERE (" +
//            "   LOWER(COALESCE(u.email, '')) LIKE :pattern " + // Handle NULL email
//            "   OR COALESCE(u.phone, '') LIKE :pattern " +     // Handle NULL phone
//            "   OR LOWER(COALESCE(u.profile_data ->> 'firstName', '')) LIKE :pattern " +
//            "   OR LOWER(COALESCE(u.profile_data ->> 'lastName', '')) LIKE :pattern " +
//            "   OR LOWER(COALESCE(u.profile_data ->> 'userTag', '')) LIKE :pattern" +
//            ") " +
//            "AND u.deleted = false",
//
//            countQuery = "SELECT count(*) FROM users u WHERE (" +
//                    "   LOWER(COALESCE(u.email, '')) LIKE :pattern " +
//                    "   OR COALESCE(u.phone, '') LIKE :pattern " +
//                    "   OR LOWER(COALESCE(u.profile_data ->> 'firstName', '')) LIKE :pattern " +
//                    "   OR LOWER(COALESCE(u.profile_data ->> 'lastName', '')) LIKE :pattern " +
//                    "   OR LOWER(COALESCE(u.profile_data ->> 'userTag', '')) LIKE :pattern" +
//                    ") AND u.deleted = false",
//            nativeQuery = true)
//    List<UserSummary> searchUsers(
//            @Param("pattern") String pattern, // We pass "%query%" from Java
//            Pageable pageable
//    );

    // ✅ OPTIMIZED: Fetch only the token string (JPQL is fine here)
    @Query("SELECT u.fcmToken FROM User u WHERE u.id = :id")
    String findFcmTokenById(@Param("id") Long id);

    // ✅ NEW: Clear dead tokens
    @Modifying
    @Transactional // ✅ Uses Spring Transactional now
    @Query("UPDATE User u SET u.fcmToken = NULL WHERE u.id = :id")
    void clearFcmToken(@Param("id") Long id);

    /**
     * Finds "abandoned signups" — users who registered but never finished
     * onboarding, i.e. they have NEITHER a wallet NOR a KYC profile.
     * (Wallet creation requires verified KYC data, so "no wallet + no KYC"
     * reliably identifies someone who dropped off before completing their
     * profile — as opposed to, say, a user mid-KYC whose wallet creation
     * merely failed.)
     * <p>
     * Used by {@code IncompleteSignupLifecycleManager} to drive the
     * "complete your profile" nudge-email cadence and, eventually, the
     * 30-day purge of registrations that never went anywhere.
     */
    @Query(value = """
            SELECT u.*
            FROM users u
            LEFT JOIN wallets w ON w.user_id = u.id
            LEFT JOIN kyc_profiles k ON k.user_id = u.id
            WHERE w.id IS NULL
              AND k.id IS NULL
              AND u.is_deleted = false
            ORDER BY u.created_at ASC
            """, nativeQuery = true)
    List<User> findIncompleteSignups();
}

package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.UserSummary;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByPhone(String phone);
    Optional<User> findByEmailOrPhone(String email, String phone);

    // Custom query to find ANY user (Active or Deleted)
    @Query(value = "SELECT * FROM users WHERE email = :email", nativeQuery = true)
    Optional<User> findGlobalByEmail(@Param("email") String email);

    // ✅ FIXED: Native Query (Bypasses Hibernate HQL parser errors)
    // Uses Postgres JSON operator (->>) to extract text directly.
    // Note: We provide a countQuery to ensure pagination works efficiently.
    @Query(value = "SELECT " +
            "u.id AS id, " +
            "u.profile_data ->> 'firstName' AS firstName, " +
            "u.profile_data ->> 'lastName' AS lastName, " +
            "u.email AS email, " +
            "u.profile_data ->> 'userTag' AS userTag, " +
            "u.profile_image_url AS profileImageUrl " +
            "FROM users u " +
            "WHERE lower(u.email) LIKE lower(concat('%', :query, '%')) " +
            "OR lower(u.profile_data ->> 'firstName') LIKE lower(concat('%', :query, '%'))",
            countQuery = "SELECT count(*) FROM users u WHERE lower(u.email) LIKE lower(concat('%', :query, '%')) OR lower(u.profile_data ->> 'firstName') LIKE lower(concat('%', :query, '%'))",
            nativeQuery = true)
    List<UserSummary> searchUsers(@Param("query") String query, Pageable pageable);

    // ✅ OPTIMIZED: Fetch only the token string (JPQL is fine here)
    @Query("SELECT u.fcmToken FROM User u WHERE u.id = :id")
    String findFcmTokenById(@Param("id") Long id);

    // ✅ NEW: Clear dead tokens
    @Modifying
    @Transactional // ✅ Uses Spring Transactional now
    @Query("UPDATE User u SET u.fcmToken = NULL WHERE u.id = :id")
    void clearFcmToken(@Param("id") Long id);
}
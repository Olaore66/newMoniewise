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
    @Query(value = "SELECT * FROM users WHERE email = :email", nativeQuery = true)
    Optional<User> findGlobalByEmail(@Param("email") String email);
    @Query("SELECT u.id as id, " +
            "cast(u.profileData['firstName'] as string) as firstName, " +
            "cast(u.profileData['lastName'] as string) as lastName, " +
            "u.email as email, " +
            "cast(u.profileData['userTag'] as string) as userTag, " +
            "u.profileImageUrl as profileImageUrl " +
            "FROM User u WHERE " +
            "lower(u.email) LIKE lower(concat('%', :query, '%')) OR " +
            "lower(cast(u.profileData['firstName'] as string)) LIKE lower(concat('%', :query, '%'))")
    List<UserSummary> searchUsers(@Param("query") String query, Pageable pageable);
    @Query("SELECT u.fcmToken FROM User u WHERE u.id = :id")
    String findFcmTokenById(@Param("id") Long id);

    // ✅ Notification Optimization: Clear dead tokens
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.fcmToken = NULL WHERE u.id = :id")
    void clearFcmToken(@Param("id") Long id);
}
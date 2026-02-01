package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByPhone(String phone);

    // Inside UserRepository interface
    Optional<User> findByEmailOrPhone(String email, String phone);

    @Query("SELECT u FROM User u WHERE " +
            "(LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) " +
            "OR LOWER(u.phone) LIKE LOWER(CONCAT('%', :query, '%')) " + // <--- Added Phone Check
            "OR function('jsonb_extract_path_text', u.profileData, 'fullName') LIKE LOWER(CONCAT('%', :query, '%')) " +
            "OR function('jsonb_extract_path_text', u.profileData, 'name') LIKE LOWER(CONCAT('%', :query, '%')))")
    List<User> searchUsers(@Param("query") String query);

    // Custom query to find ANY user (Active or Deleted)
    @Query(value = "SELECT * FROM users WHERE email = :email", nativeQuery = true)
    Optional<User> findGlobalByEmail(@Param("email") String email);
}
package com.ubot.user.repository;

import com.ubot.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
	Optional<User> findByEmailAndDeletedAtIsNull(String email);
	Optional<User> findByIdAndDeletedAtIsNull(Long id);
	boolean existsByEmailAndDeletedAtIsNull(String email);

}

package com.ubot.user.service;

import com.ubot.user.entity.User;
import com.ubot.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {
	private final UserRepository userRepository;

	public Optional<User> findActiveUserByEmail(String email) {
		return userRepository.findByEmailAndDeletedAtIsNull(email);
	}

	public boolean existsActiveUserByEmail(String email) {
		return userRepository.existsByEmailAndDeletedAtIsNull(email);
	}

	@Transactional
	public void save(User user) {
		userRepository.saveAndFlush(user);
	}
}

package com.ubot.forbiddenword.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ubot.forbiddenword.dto.request.ForbiddenWordCreateRequestDto;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class ForbiddenWordCatalogLockTest {

	@Test
	void transactionIsRequiredEvenWhenLockIsConstructedDirectly() {
		var jdbc = mock(JdbcTemplate.class);
		var lock = new ForbiddenWordCatalogLock(jdbc);
		assertThatThrownBy(lock::acquire).isInstanceOf(IllegalStateException.class).hasMessageContaining("활성 트랜잭션");
		verifyNoInteractions(jdbc);
	}

	@Test
	void missingRequiredLockPreventsServiceStartup() {
		try (var context = new AnnotationConfigApplicationContext()) {
			context.registerBean(ForbiddenWordRepository.class, () -> mock(ForbiddenWordRepository.class));
			context.register(ForbiddenWordService.class);
			assertThatThrownBy(context::refresh).isInstanceOf(UnsatisfiedDependencyException.class)
					.hasMessageContaining("ForbiddenWordCatalogLock");
		}
	}

	@Test
	void coreModuleCanBeWiredWithoutReviewModuleAndCannotSilentlySkipLocking() {
		var jdbc = mock(JdbcTemplate.class);
		var repository = mock(ForbiddenWordRepository.class);
		try (var context = new AnnotationConfigApplicationContext()) {
			context.registerBean(JdbcTemplate.class, () -> jdbc);
			context.registerBean(ForbiddenWordRepository.class, () -> repository);
			context.register(ForbiddenWordCatalogLock.class, ForbiddenWordService.class);
			context.refresh();
			clearInvocations(jdbc, repository);
			assertThatThrownBy(() -> context.getBean(ForbiddenWordService.class)
					.createForbiddenWord(new ForbiddenWordCreateRequestDto("검토표현")))
					.isInstanceOf(IllegalStateException.class).hasMessageContaining("활성 트랜잭션");
			verifyNoInteractions(jdbc, repository);
		}
	}
}

package com.ubot.counsel.safety.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.safety.client.EmbeddingClient;
import com.ubot.counsel.safety.repository.SafetyRepository;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.exception.ForbiddenWordException;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("상담 금지어·의미 검사")
class SafetyGuardServiceTest {
	private final ForbiddenWordFilterService forbiddenWords = mock(ForbiddenWordFilterService.class);
	private final EmbeddingClient embedding = mock(EmbeddingClient.class);
	private final SafetyRepository repository = mock(SafetyRepository.class);
	private final CounselProperties properties = new CounselProperties(true, 30, 120, 15, 60, 30,
			new CounselProperties.Guard("http://unused", "test-model", "test-v1", 0.95, 3, 2, 500, 1000));
	private final SafetyGuardService guard = new SafetyGuardService(forbiddenWords, embedding, repository, properties);

	@Test
	@DisplayName("기존 필터가 금지어를 탐지하면 임베딩과 벡터 검색 없이 차단한다")
	void forbiddenWordStopsBeforeEmbedding() {
		doThrow(new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED))
				.when(forbiddenWords).validateForbiddenWord("등록한 금지어");

		var result = guard.checkMessage("등록한 금지어");

		assertThat(result.status()).isEqualTo("BLOCKED");
		assertThat(result.vectorId()).isNull();
		assertThat(result.score()).isNull();
		assertThat(result.errorCode()).isNull();
		verifyNoInteractions(embedding, repository);
	}

	@Test
	@DisplayName("기존 채팅과 같은 원문을 먼저 검사하고 통과한 메시지만 임베딩한다")
	void validatesOriginalBeforeEmbedding() {
		String original = "  요금　문의입니다.  ";
		when(embedding.embed(original)).thenReturn("vector");
		when(repository.findTopMatches("vector")).thenReturn(List.of(new SafetyRepository.Match(1, 0.2, "INSULT")));

		assertThat(guard.checkMessage(original).status()).isEqualTo("ALLOWED");

		var order = inOrder(forbiddenWords, embedding, repository);
		order.verify(forbiddenWords).validateForbiddenWord(original);
		order.verify(embedding).embed(original);
		order.verify(repository).findTopMatches("vector");
	}

	@Test
	@DisplayName("유사도가 임계값 이상이면 차단하고 미만이면 허용한다")
	void blocksOnlyAtOrAboveThreshold() {
		when(embedding.embed("문의")).thenReturn("vector");
		when(repository.findTopMatches("vector")).thenReturn(List.of(new SafetyRepository.Match(7, 0.95, "INSULT")));

		var blocked = guard.checkMessage("문의");

		assertThat(blocked.status()).isEqualTo("BLOCKED");
		assertThat(blocked.vectorId()).isEqualTo(7L);
		assertThat(blocked.score()).isEqualTo(0.95);

		when(repository.findTopMatches("vector")).thenReturn(List.of(new SafetyRepository.Match(7, 0.949, "INSULT")));

		assertThat(guard.checkMessage("문의").status()).isEqualTo("ALLOWED");
	}

	@Test
	@DisplayName("금지어 통과 후 워커 오류는 검사 실패로 남긴다")
	void workerFailureIsNotAllowed() {
		when(embedding.embed("문의")).thenThrow(new EmbeddingClient.Failure("WORKER_TIMEOUT"));

		var result = guard.checkMessage("문의");

		assertThat(result.status()).isEqualTo("CHECK_FAILED");
		assertThat(result.errorCode()).isEqualTo("WORKER_TIMEOUT");
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("탐지 이외의 금지어 오류를 차단 판정으로 숨기지 않는다")
	void unrelatedFilterErrorIsNotConvertedToBlocked() {
		var failure = new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_NOT_FOUND);
		doThrow(failure).when(forbiddenWords).validateForbiddenWord("문의");

		assertThatThrownBy(() -> guard.checkMessage("문의")).isSameAs(failure);
		verifyNoInteractions(embedding, repository);
	}
}

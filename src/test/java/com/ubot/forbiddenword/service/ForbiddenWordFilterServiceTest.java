package com.ubot.forbiddenword.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.exception.ForbiddenWordException;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

@DisplayName("금지어 필터 서비스 테스트")
class ForbiddenWordFilterServiceTest {
	private final ForbiddenWordRepository repository = mock(ForbiddenWordRepository.class);
	private final ForbiddenWordFilterService service = new ForbiddenWordFilterService(repository);

	@Test
	@DisplayName("캐시를 초기화하기 전에는 아무 문장도 차단하지 않는다")
	void validate_passesBeforeInitialization() {
		assertThatCode(() -> service.validateForbiddenWord("너 진짜 바보야")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("활성 금지어가 포함된 문장은 차단한다")
	void validate_blocksContainedWord() {
		cache("바보");

		assertThatThrownBy(() -> service.validateForbiddenWord("너 진짜 바보야"))
				.isInstanceOfSatisfying(ForbiddenWordException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED);
					assertThat(e.getMessage()).doesNotContain("바보");
				});
	}

	@Test
	@DisplayName("금지어가 없는 문장은 통과한다")
	void validate_passesCleanSentence() {
		cache("바보");

		assertThatCode(() -> service.validateForbiddenWord("요금제 알려주세요")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("띄어쓰기로 나뉜 금지어는 초기 구현에서 통과한다")
	void validate_doesNotDetectSpacedWord() {
		cache("바보");

		assertThatCode(() -> service.validateForbiddenWord("너 진짜 바 보야")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("여러 금지어 중 하나라도 포함되면 차단한다")
	void validate_blocksAnyOfMultipleWords() {
		cache("바보", "멍청이");

		assertThatThrownBy(() -> service.validateForbiddenWord("이 멍청이 같은")).isInstanceOf(ForbiddenWordException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@DisplayName("비어 있는 입력은 검사하지 않고 통과한다")
	void validate_passesEmptyInput(String content) {
		cache("바보");

		assertThatCode(() -> service.validateForbiddenWord(content)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("캐시 갱신은 ACTIVE 금지어만 조회하며 이전 캐시를 교체한다")
	void refresh_replacesCache() {
		cache("바보");
		when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenReturn(List.of(word("멍청이")));

		service.refreshForbiddenWordCache();

		assertThatCode(() -> service.validateForbiddenWord("너 바보야")).doesNotThrowAnyException();
		assertThatThrownBy(() -> service.validateForbiddenWord("너 멍청이야")).isInstanceOf(ForbiddenWordException.class);
	}

	@Test
	@DisplayName("서버 시작 시 활성 금지어를 적재한다")
	void initialize_loadsActiveWords() {
		when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenReturn(List.of(word("바보")));

		service.initializeForbiddenWordCache();

		verify(repository).findAllByStatus(ForbiddenWordStatus.ACTIVE);
		assertThatThrownBy(() -> service.validateForbiddenWord("바보")).isInstanceOf(ForbiddenWordException.class);
	}

	@Test
	@DisplayName("금지어 변경 이벤트를 받으면 캐시를 갱신한다")
	void onChanged_refreshesCache() {
		when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenReturn(List.of(word("바보")));

		service.onForbiddenWordChanged(new ForbiddenWordChangedEvent());

		verify(repository).findAllByStatus(ForbiddenWordStatus.ACTIVE);
		assertThatThrownBy(() -> service.validateForbiddenWord("바보")).isInstanceOf(ForbiddenWordException.class);
	}

	@Test
	@DisplayName("검사만 할 때는 DB를 조회하지 않는다")
	void validate_doesNotQueryDatabase() {
		cache("바보");
		org.mockito.Mockito.clearInvocations(repository);

		service.validateForbiddenWord("안녕하세요");

		verify(repository, never()).findAllByStatus(ForbiddenWordStatus.ACTIVE);
	}

	private void cache(String... words) {
		when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE))
				.thenReturn(java.util.Arrays.stream(words).map(this::word).toList());
		service.refreshForbiddenWordCache();
	}

	private ForbiddenWord word(String word) {
		return ForbiddenWord.builder().word(word).status(ForbiddenWordStatus.ACTIVE).build();
	}
}

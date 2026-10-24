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
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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
	@DisplayName("띄어쓰기로 나뉜 금지어와 짧은 조사가 붙은 표현도 차단한다")
	void validate_blocksSpacedWord() {
		cache("바보");

		assertThatThrownBy(() -> service.validateForbiddenWord("너 진짜 바 보야"))
				.isInstanceOf(ForbiddenWordException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"바 보", "바\t보", "바\r\n보", "바\u00A0보", "바\u3000보",
			"바 보입니다.", "바 보입니까?", "바 보예요.", "바 보이에요.",
			"바 보라고", "바 보이라고", "바 보라고요", "바 보라는 말", "바 보이라는 말",
			"바\u200B보", "바\u200C보", "바\u200D보", "바\u2060보", "바\uFEFF보", "바\u202E보",
			"바\u200B 보입니다.", "바\u3000보라고요",
			"바\uDB40\uDC01보", "BAD", "bAd", "ＢＡＤ", "ｂａｄ", "B a D", "B\u200B a\u00A0D"
	})
	@DisplayName("공백·숨은 문자·전각 문자·영문 대소문자로 변형한 활성 금지어를 차단한다")
	void validate_blocksSelectedVariants(String content) {
		cache("바보", "bad");

		assertThatThrownBy(() -> service.validateForbiddenWord(content))
				.isInstanceOfSatisfying(ForbiddenWordException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED));
		assertThat(service.findMatchingForbiddenWords(List.of(content))).isNotEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"BAD", "ＢＡＤ", "Ｂ\u200BＡＤ"})
	@DisplayName("등록된 금지어도 동일한 검사용 문자 정리를 적용한다")
	void validate_normalizesRegisteredWord(String forbiddenWord) {
		cache(forbiddenWord);

		assertThatThrownBy(() -> service.validateForbiddenWord("b a d"))
				.isInstanceOf(ForbiddenWordException.class);
		assertThat(service.findMatchingForbiddenWords(List.of("b a d"))).containsExactly(forbiddenWord);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"다시 발급받으려면 어떻게 하나요?", "다시\t발급받으려면 어떻게 하나요?",
			"시 발급 절차를 알려주세요.", "b a day plan",
			"상단 바 보기 설정을 알려주세요.", "스크롤 바 보이게 하는 방법을 알려주세요.",
			"바.보", "바*보", "바-보", "ba.d", "ba*d", "ba-d",
			"ㅂㅂ", "ㅂㅏㅂㅗ", "b4d", "ⓑⓐⓓ"
	})
	@DisplayName("정상 단어 사이의 공백과 이번 범위 밖인 기호·초성·숫자 변형은 통과한다")
	void validate_passesUnrelatedWordsAndUnselectedVariants(String content) {
		cache("바보", "bad", "시발");

		assertThatCode(() -> service.validateForbiddenWord(content)).doesNotThrowAnyException();
		assertThat(service.findMatchingForbiddenWords(List.of(content))).isEmpty();
	}

	@Test
	@DisplayName("문자 정리 후 비어 있는 금지어가 모든 질문을 차단하지 않는다")
	void validate_ignoresEmptyInspectionWord() {
		cache("\u200B");

		assertThatCode(() -> service.validateForbiddenWord("요금제를 알려주세요.")).doesNotThrowAnyException();
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
		assertThatCode(() -> service.validateForbiddenWord("너 바 보야")).doesNotThrowAnyException();
		assertThatThrownBy(() -> service.validateForbiddenWord("너 멍청이야")).isInstanceOf(ForbiddenWordException.class);
		assertThatThrownBy(() -> service.validateForbiddenWord("너 멍 청 이야")).isInstanceOf(ForbiddenWordException.class);
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
		assertThatThrownBy(() -> service.validateForbiddenWord("바\u200B 보"))
				.isInstanceOf(ForbiddenWordException.class);

		verify(repository, never()).findAllByStatus(ForbiddenWordStatus.ACTIVE);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"앱 BADGE 알림 표시를 끄는 방법을 알려줘.",
			"BADMINTON 동호회에서 쓸 단체 요금제가 있나요?",
			"Bad Request 오류가 나오는데 어떻게 하나요?",
			"＂ＢＡＤＧＥ＂ 아이콘 표시 설정을 알려줘.",
			"badge",
			"badminton",
			"badger",
			"notbad",
			"BADGE",
			"ＢＡＤＭＩＮＴＯＮ",
			"BA​DGE",
			"Bad Request error",
			"BAD REQUEST 에러가 나와요.",
			"HTTP 400 Bad Request 응답이 와요.",
			"400 BAD REQUEST",
			"ＢＡＤ ＲＥＱＵＥＳＴ 오류",
			"Bad\nRequest 오류",
			"Bad Request 오류와 Bad Request 에러가 발생했어요."
	})
	@DisplayName("bad가 포함된 정상 영단어와 명확한 Bad Request 오류명은 허용한다")
	void allowsEnglishWordsAndHttpErrorNames(String content) {
		cache("bad", "바보", "fuck");

		assertThatCode(() -> service.validateForbiddenWord(content)).doesNotThrowAnyException();
		assertThat(service.findMatchingForbiddenWords(List.of(content))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"bad",
			"b a d",
			"bad야",
			"이bad놈",
			"bad123",
			"bad_01",
			"1bad",
			"bad Request",
			"Bad Requester 오류",
			"Bad Request 오류. 너는 bad야.",
			"bad. Bad Request 오류",
			"Bad Request 오류. 바 보야.",
			"Bad Request 오류. f u c k",
			"b a d Request 오류",
			"400 bad",
			"ＢＡＤＧＥ 알림이고 b a d야."
	})
	@DisplayName("정상 표현과 함께 나온 실제 금칙어와 변형은 질문 전체를 차단한다")
	void blocksForbiddenWordsMixedWithNormalExpressions(String content) {
		cache("bad", "바보", "fuck");

		assertThatThrownBy(() -> service.validateForbiddenWord(content))
				.isInstanceOfSatisfying(ForbiddenWordException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED));
		assertThat(service.findMatchingForbiddenWords(List.of(content))).isNotEmpty();
	}

	@Test
	void matchingReadsOnlyTheActiveCacheAndDoesNotChangeItOrQueryTheDatabase() {
		cache("바보", "bad", "무관등록어");
		org.mockito.Mockito.clearInvocations(repository);

		assertThat(service.findMatchingForbiddenWords(List.of("바 보야", "BAD", "바보")))
				.containsExactly("bad", "바보");
		assertThat(service.getActiveForbiddenWords()).containsExactly("bad", "무관등록어", "바보");
		org.mockito.Mockito.verifyNoInteractions(repository);
	}

	@Test
	void matchingUsesTheNewSnapshotAfterACacheRefresh() {
		cache("바보");
		assertThat(service.findMatchingForbiddenWords(List.of("바보", "멍청이"))).containsExactly("바보");
		cache("멍청이");
		assertThat(service.findMatchingForbiddenWords(List.of("바보", "멍청이"))).containsExactly("멍청이");
	}

	@Test
	void matchingIgnoresMissingContentsAndDoesNotJoinUnrelatedFields() {
		cache("바보");
		assertThat(service.findMatchingForbiddenWords(null)).isEmpty();
		assertThat(service.findMatchingForbiddenWords(List.of())).isEmpty();
		assertThat(service.findMatchingForbiddenWords(java.util.Arrays.asList(null, "", "바", "보"))).isEmpty();
	}

	@Test
	void equivalentNormalizedWordsKeepTheirOriginalCatalogEntriesAndPreviewsStayIndependent() {
		cache("bad", "ＢＡＤ");
		var preview = service.preview(Set.of("바 보", "x.y"));
		org.mockito.Mockito.clearInvocations(repository);

		assertThat(service.findMatchingForbiddenWords(List.of("b a d", "b a d"))).containsExactly("bad", "ＢＡＤ");
		assertThat(service.getActiveForbiddenWords()).containsExactly("bad", "ＢＡＤ");
		assertThat(preview.findMatchingForbiddenWords(List.of("바 보입니다.", "x . y")))
				.containsExactly("x.y", "바 보");
		assertThat(preview.findMatchingForbiddenWords(List.of("b a d"))).isEmpty();
		assertThat(service.findMatchingForbiddenWords(List.of("바 보입니다."))).isEmpty();
		org.mockito.Mockito.verifyNoInteractions(repository);
	}

	@Test
	void failedRefreshKeepsThePreviousCompleteSnapshotAndALaterRefreshReplacesItsVariants() {
		cache("ＢＡＤ");
		when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenThrow(new IllegalStateException("DB unavailable"));

		assertThatThrownBy(service::refreshForbiddenWordCache).isInstanceOf(IllegalStateException.class);
		assertThat(service.findMatchingForbiddenWords(List.of("b a d"))).containsExactly("ＢＡＤ");
		org.mockito.Mockito.doReturn(List.of(word("바보"))).when(repository).findAllByStatus(ForbiddenWordStatus.ACTIVE);
		service.refreshForbiddenWordCache();

		assertThat(service.findMatchingForbiddenWords(List.of("b a d"))).isEmpty();
		assertThat(service.findMatchingForbiddenWords(List.of("바 보입니다."))).containsExactly("바보");
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

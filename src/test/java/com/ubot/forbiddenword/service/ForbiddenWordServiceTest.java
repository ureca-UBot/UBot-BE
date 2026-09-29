package com.ubot.forbiddenword.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;
import com.ubot.common.PageResponseDto;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.forbiddenword.dto.request.ForbiddenWordCreateRequestDto;
import com.ubot.forbiddenword.dto.request.ForbiddenWordStatusUpdateRequestDto;
import com.ubot.forbiddenword.dto.request.ForbiddenWordUpdateRequestDto;
import com.ubot.forbiddenword.dto.response.ForbiddenWordResponseDto;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@DisplayName("금지어 서비스 테스트")
class ForbiddenWordServiceTest {
	private final ForbiddenWordRepository repository = mock(ForbiddenWordRepository.class);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
	private final ForbiddenWordService service = new ForbiddenWordService(repository, eventPublisher);

	@Test
	@DisplayName("금지어를 생성하면 ACTIVE 상태로 저장하고 변경 이벤트를 발행한다")
	void createForbiddenWord_savesAsActive() {
		when(repository.existsByWord("바보")).thenReturn(false);
		when(repository.saveAndFlush(any(ForbiddenWord.class))).thenAnswer(i -> i.getArgument(0));

		ForbiddenWordResponseDto result = service.createForbiddenWord(new ForbiddenWordCreateRequestDto("바보"));

		assertThat(result.word()).isEqualTo("바보");
		assertThat(result.status()).isEqualTo(ForbiddenWordStatus.ACTIVE);
		assertThat(result.updatedAt()).isNotNull();
		verify(eventPublisher).publishEvent(any(ForbiddenWordChangedEvent.class));
	}

	@Test
	@DisplayName("금지어 앞뒤 공백은 제거하고 저장한다")
	void createForbiddenWord_stripsWord() {
		when(repository.existsByWord("바보")).thenReturn(false);
		when(repository.saveAndFlush(any(ForbiddenWord.class))).thenAnswer(i -> i.getArgument(0));

		ForbiddenWordResponseDto result = service.createForbiddenWord(new ForbiddenWordCreateRequestDto("  바보 "));

		assertThat(result.word()).isEqualTo("바보");
	}

	@Test
	@DisplayName("이미 존재하는 금지어를 생성하면 중복 예외가 발생하고 이벤트를 발행하지 않는다")
	void createForbiddenWord_throwsWhenExists() {
		when(repository.existsByWord("바보")).thenReturn(true);

		assertError(() -> service.createForbiddenWord(new ForbiddenWordCreateRequestDto("바보")),
				ForbiddenWordErrorCode.FORBIDDEN_WORD_EXIST);
		verify(repository, never()).saveAndFlush(any());
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	@Test
	@DisplayName("중복 확인 이후 동시 등록으로 UNIQUE 제약에 걸리면 중복 예외로 변환한다")
	void createForbiddenWord_mapsUniqueViolationToExist() {
		when(repository.existsByWord("바보")).thenReturn(false);
		when(repository.saveAndFlush(any(ForbiddenWord.class)))
				.thenThrow(new DataIntegrityViolationException("uk_forbidden_words_word"));

		assertError(() -> service.createForbiddenWord(new ForbiddenWordCreateRequestDto("바보")),
				ForbiddenWordErrorCode.FORBIDDEN_WORD_EXIST);
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	@Test
	@DisplayName("중복 확인 이후 동시 수정으로 UNIQUE 제약에 걸리면 중복 예외로 변환한다")
	void updateForbiddenWord_mapsUniqueViolationToExist() {
		when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(word(1L, "바보", ForbiddenWordStatus.ACTIVE)));
		when(repository.existsByWordAndIdNot("멍청이", 1L)).thenReturn(false);
		doThrow(new DataIntegrityViolationException("uk_forbidden_words_word")).when(repository).flush();

		assertError(() -> service.updateForbiddenWord(1L, new ForbiddenWordUpdateRequestDto("멍청이")),
				ForbiddenWordErrorCode.FORBIDDEN_WORD_EXIST);
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t "})
	@DisplayName("비어 있는 금지어를 생성하면 예외가 발생한다")
	void createForbiddenWord_throwsWhenBlank(String word) {
		assertError(() -> service.createForbiddenWord(new ForbiddenWordCreateRequestDto(word)),
				ForbiddenWordErrorCode.FORBIDDEN_WORD_REQUIRED);
		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("금지어 목록은 id 내림차순으로 페이지 조회한다")
	void getForbiddenWordList_returnsPage() {
		Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("id")));
		when(repository.findAll(pageable))
				.thenReturn(new PageImpl<>(List.of(word(2L, "욕설", ForbiddenWordStatus.INACTIVE),
						word(1L, "바보", ForbiddenWordStatus.ACTIVE)), pageable, 2));

		PageResponseDto<ForbiddenWordResponseDto> result = service.getForbiddenWordList(0, 20);

		assertThat(result.content()).extracting(ForbiddenWordResponseDto::id).containsExactly(2L, 1L);
		assertThat(result.content()).extracting(ForbiddenWordResponseDto::status)
				.containsExactly(ForbiddenWordStatus.INACTIVE, ForbiddenWordStatus.ACTIVE);
		assertThat(result.totalElements()).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(ints = {10, 20, 50})
	@DisplayName("허용된 페이지 크기로 조회할 수 있다")
	void getForbiddenWordList_allowsSizes(int size) {
		Pageable pageable = PageRequest.of(0, size, Sort.by(Sort.Order.desc("id")));
		when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

		assertThat(service.getForbiddenWordList(0, size).size()).isEqualTo(size);
	}

	@ParameterizedTest
	@ValueSource(ints = {-1, 0, 1, 15, 30, 100})
	@DisplayName("허용되지 않은 페이지 크기는 예외가 발생한다")
	void getForbiddenWordList_rejectsSizes(int size) {
		assertThatThrownBy(() -> service.getForbiddenWordList(0, size))
				.isInstanceOfSatisfying(GlobalException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
		verify(repository, never()).findAll(any(Pageable.class));
	}

	@Test
	@DisplayName("단어를 수정하면 단어만 바뀌고 상태는 유지하며 이벤트를 발행한다")
	void updateForbiddenWord_updatesWordOnly() {
		ForbiddenWord forbiddenWord = word(1L, "바보", ForbiddenWordStatus.INACTIVE);
		when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(forbiddenWord));
		when(repository.existsByWordAndIdNot("멍청이", 1L)).thenReturn(false);

		ForbiddenWordResponseDto result = service.updateForbiddenWord(1L, new ForbiddenWordUpdateRequestDto(" 멍청이 "));

		assertThat(result.word()).isEqualTo("멍청이");
		assertThat(result.status()).isEqualTo(ForbiddenWordStatus.INACTIVE);
		verify(eventPublisher).publishEvent(any(ForbiddenWordChangedEvent.class));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"  "})
	@DisplayName("수정 시 word가 비어 있으면 예외가 발생하고 수정하지 않는다")
	void updateForbiddenWord_throwsWhenWordBlank(String word) {
		ForbiddenWord forbiddenWord = word(1L, "바보", ForbiddenWordStatus.ACTIVE);
		when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(forbiddenWord));

		assertError(() -> service.updateForbiddenWord(1L, new ForbiddenWordUpdateRequestDto(word)),
				ForbiddenWordErrorCode.FORBIDDEN_WORD_REQUIRED);
		assertThat(forbiddenWord.getWord()).isEqualTo("바보");
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	@Test
	@DisplayName("존재하지 않는 금지어를 수정하면 예외가 발생한다")
	void updateForbiddenWord_throwsWhenNotFound() {
		when(repository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

		assertError(() -> service.updateForbiddenWord(1L, new ForbiddenWordUpdateRequestDto("멍청이")),
				ForbiddenWordErrorCode.FORBIDDEN_WORD_NOT_FOUND);
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	@Test
	@DisplayName("다른 금지어와 중복되는 단어로 수정하면 예외가 발생한다")
	void updateForbiddenWord_throwsWhenDuplicated() {
		ForbiddenWord forbiddenWord = word(1L, "바보", ForbiddenWordStatus.ACTIVE);
		when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(forbiddenWord));
		when(repository.existsByWordAndIdNot("멍청이", 1L)).thenReturn(true);

		assertError(() -> service.updateForbiddenWord(1L, new ForbiddenWordUpdateRequestDto("멍청이")),
				ForbiddenWordErrorCode.FORBIDDEN_WORD_EXIST);
		assertThat(forbiddenWord.getWord()).isEqualTo("바보");
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	@ParameterizedTest
	@EnumSource(ForbiddenWordStatus.class)
	@DisplayName("상태를 수정하면 상태만 바뀌고 단어는 유지하며 이벤트를 발행한다")
	void updateForbiddenWordStatus_updatesStatusOnly(ForbiddenWordStatus status) {
		ForbiddenWord forbiddenWord = word(1L, "바보", status == ForbiddenWordStatus.ACTIVE
				? ForbiddenWordStatus.INACTIVE : ForbiddenWordStatus.ACTIVE);
		when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(forbiddenWord));

		ForbiddenWordResponseDto result = service.updateForbiddenWordStatus(1L,
				new ForbiddenWordStatusUpdateRequestDto(status));

		assertThat(result.status()).isEqualTo(status);
		assertThat(result.word()).isEqualTo("바보");
		verify(repository, never()).existsByWordAndIdNot(any(), any());
		verify(eventPublisher).publishEvent(any(ForbiddenWordChangedEvent.class));
	}

	@Test
	@DisplayName("상태 수정 시 status가 없으면 예외가 발생하고 수정하지 않는다")
	void updateForbiddenWordStatus_throwsWhenStatusNull() {
		ForbiddenWord forbiddenWord = word(1L, "바보", ForbiddenWordStatus.ACTIVE);
		when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(forbiddenWord));

		assertError(() -> service.updateForbiddenWordStatus(1L, new ForbiddenWordStatusUpdateRequestDto(null)),
				ForbiddenWordErrorCode.FORBIDDEN_WORD_STATUS_REQUIRED);
		assertThat(forbiddenWord.getStatus()).isEqualTo(ForbiddenWordStatus.ACTIVE);
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	@Test
	@DisplayName("존재하지 않는 금지어의 상태를 수정하면 예외가 발생한다")
	void updateForbiddenWordStatus_throwsWhenNotFound() {
		when(repository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

		assertError(() -> service.updateForbiddenWordStatus(1L,
						new ForbiddenWordStatusUpdateRequestDto(ForbiddenWordStatus.INACTIVE)),
				ForbiddenWordErrorCode.FORBIDDEN_WORD_NOT_FOUND);
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	private void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
				.isInstanceOfSatisfying(GlobalException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	private ForbiddenWord word(Long id, String word, ForbiddenWordStatus status) {
		LocalDateTime now = LocalDateTime.now();
		return ForbiddenWord.builder().id(id).word(word).status(status).createdAt(now).updatedAt(now).build();
	}
}

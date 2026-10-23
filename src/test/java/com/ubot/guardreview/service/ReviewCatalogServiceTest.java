package com.ubot.guardreview.service;

import static com.ubot.guardreview.model.ReviewModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.guardreview.exception.ReviewException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReviewCatalogServiceTest {
	ForbiddenWordRepository words = mock(ForbiddenWordRepository.class);
	ReviewCatalogService catalog = new ReviewCatalogService(words);
	Candidate candidate(String status, Long wordId) {
		return new Candidate(1, Kind.WORD, "ABUSE", "검토 표현", "이유", "HARMFUL_OUTPUT", 1, status, wordId, null, 1, 0);
	}
	ForbiddenWord word(String expression, ForbiddenWordStatus status) {
		return ForbiddenWord.builder().id(55L).word(expression).status(status).build();
	}
	@Test
	void 기존_수동등록_단어는_소유권_없이_등록됨으로_보여준다() {
		when(words.findAll()).thenReturn(List.of(word("검토 표현", ForbiddenWordStatus.ACTIVE)));
		var view = catalog.display(List.of(candidate("PENDING", null))).getFirst();
		assertThat(view.status()).isEqualTo("REGISTERED");
		assertThat(view.wordId()).isNull();
	}
	@Test
	void 기존_비활성_단어도_중복등록_대상으로_선택하지_않는다() {
		when(words.existsByWord("검토 표현")).thenReturn(true);
		assertThatThrownBy(() -> catalog.requireSelectable(candidate("PENDING", null)))
				.isInstanceOf(ReviewException.class).hasMessageContaining("이미 등록된");
	}
	@Test
	void 기존화면의_비활성화를_현재_상태로_보여준다() {
		when(words.findAll()).thenReturn(List.of(word("검토 표현", ForbiddenWordStatus.INACTIVE)));
		assertThat(catalog.display(List.of(candidate("APPLIED", 55L))).getFirst().status()).isEqualTo("DISABLED");
	}
	@Test
	void 기존화면의_재활성화를_현재_상태로_보여준다() {
		when(words.findAll()).thenReturn(List.of(word("검토 표현", ForbiddenWordStatus.ACTIVE)));
		assertThat(catalog.display(List.of(candidate("DISABLED", 55L))).getFirst().status()).isEqualTo("APPLIED");
	}
	@Test
	void 수동으로_이름을_바꾸면_연결변경을_표시한다() {
		when(words.findAll()).thenReturn(List.of(word("수동 변경", ForbiddenWordStatus.ACTIVE)));
		assertThat(catalog.display(List.of(candidate("APPLIED", 55L))).getFirst().status())
				.isEqualTo("CATALOG_CHANGED");
	}
	@Test
	void 연결단어가_삭제돼도_후보_수정으로_복구할수있다() {
		when(words.findById(55L)).thenReturn(Optional.empty());
		assertThat(catalog.linkForEdit(candidate("APPLIED", 55L), "새 표현")).isNull();
	}
	@Test
	void 옛_연결이_바뀐_검사결과는_승인에_사용할수없다() {
		when(words.findById(55L)).thenReturn(Optional.of(word("수동 변경", ForbiddenWordStatus.ACTIVE)));
		assertThatThrownBy(() -> catalog.requireSelectable(candidate("PENDING", 55L)))
				.isInstanceOf(ReviewException.class).hasMessageContaining("연결된 금칙어");
	}
	@Test
	void 상태확인과_연결해제는_기존_금칙어를_수정하지않는다() {
		when(words.findById(55L)).thenReturn(Optional.of(word("수동 변경", ForbiddenWordStatus.ACTIVE)));
		assertThat(catalog.linkForEdit(candidate("APPLIED", 55L), "새 표현")).isNull();
		verify(words, never()).save(any());
		verify(words, never()).delete(any());
	}
	@Test
	void 짧은_연결후보는_비활성_금칙어_재활성화_분기로_우회하지못한다() {
		var shortWord = new Candidate(1, Kind.WORD, "ABUSE", "요금", "검토", "HARMFUL_OUTPUT", 1, "PENDING", 55L, null, 1,
				0);
		when(words.findById(55L)).thenReturn(Optional.of(word("요금", ForbiddenWordStatus.INACTIVE)));
		assertThatThrownBy(() -> catalog.requireSelectable(shortWord)).isInstanceOfSatisfying(ReviewException.class,
				e -> assertThat(e.getErrorCode())
						.isEqualTo(com.ubot.guardreview.exception.ReviewErrorCode.WORD_TOO_SHORT));
	}
	@Test
	void 이미_활성인_짧은_금칙어의_적용해제는_계속_허용한다() {
		var linked = word("요금", ForbiddenWordStatus.ACTIVE);
		when(words.findById(55L)).thenReturn(Optional.of(linked));
		var shortWord = new Candidate(1, Kind.WORD, "ABUSE", "요금", "검토", "HARMFUL_OUTPUT", 1, "APPLIED", 55L, null, 1,
				0);
		assertThat(catalog.requireOwnedActive(shortWord)).isSameAs(linked);
	}

}

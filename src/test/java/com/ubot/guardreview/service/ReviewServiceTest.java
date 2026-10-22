package com.ubot.guardreview.service;

import static com.ubot.guardreview.model.ReviewModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.forbiddenword.dto.response.ForbiddenWordResponseDto;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordService;
import com.ubot.guardreview.exception.ReviewException;
import com.ubot.guardreview.repository.ReviewRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class ReviewServiceTest {
	ReviewRepository repository = mock(ReviewRepository.class);
	ReviewValidationService validator = mock(ReviewValidationService.class);
	ForbiddenWordService wordService = mock(ForbiddenWordService.class);
	ForbiddenWordRepository words = mock(ForbiddenWordRepository.class);
	ReviewService service = new ReviewService(repository, validator, wordService, words,
			new ReviewCatalogService(words));
	Candidate word = word(1, 1, "PENDING");
	List<Approval> selection = List.of(new Approval(1, 1));
	JsonMapper json = JsonMapper.builder().build();
	static Candidate word(long id, long revision, String status) {
		return new Candidate(id, Kind.WORD, "ABUSE", "검증용모욕표현", "검토 필요", "HARMFUL_OUTPUT", revision, status,
				"APPLIED".equals(status) ? 55L : null, null, 1, 0);
	}
	static Candidate warning() {
		return new Candidate(3, Kind.WARNING, "FRAUD", "인증 우회 요구", "검토 필요", "HARMFUL_REQUEST", 1, "PENDING", null, null,
				1, 0);
	}
	@BeforeEach
	void prepare() {
		when(repository.candidate(1, true)).thenReturn(word);
		when(repository.candidate(1, false)).thenReturn(word);
		when(validator.currentWords()).thenReturn(Set.of("기존금칙어"));
		when(validator.baseVersion()).thenReturn("base");
		when(validator.baseVersion(anySet())).thenReturn("base");
		when(validator.datasetVersion()).thenReturn("dataset");
	}
	void passedRun(int falsePositives, int regressions) {
		var c = new CaseResult("safe", "NORMAL", Stage.INPUT, "정상", "정상", false, false, false, false);
		var result = new Validation("base", "dataset", List.of(c), falsePositives, regressions, 0, 0);
		when(repository.run(7)).thenReturn(new Run(7, "VALIDATION", "COMPLETED", 1, 1, LocalDateTime.now(),
				json.writeValueAsString(result), LocalDateTime.now()));
		when(repository.request(7)).thenReturn(json.writeValueAsString(
				new ReviewService.TestRequest(selection, List.of(word), Set.of("기존금칙어"), "base", "dataset")));
	}
	@Test
	void 후보수정은_활성목록을_건드리지_않고_이력을_남긴다() {
		service.updateCandidate(1, 1, "새 후보", "수정 이유", 99);
		verify(repository).edit(eq(1L), eq("새 후보"), eq("수정 이유"), anyString(), isNull());
		verify(repository).audit(any(), eq("EDIT"), eq(99L));
		verifyNoInteractions(wordService);
	}
	@Test
	void 오래된_버전의_수정은_거절한다() {
		assertThatThrownBy(() -> service.updateCandidate(1, 2, "후보", "이유", 99)).isInstanceOf(ReviewException.class);
		verify(repository, never()).edit(anyLong(), anyString(), anyString(), anyString(), any());
	}
	@Test
	void 위험문장을_금칙어후보처럼_수정할수없다() {
		when(repository.candidate(3, true)).thenReturn(warning());
		assertThatThrownBy(() -> service.updateCandidate(3, 1, "표현", "이유", 99)).isInstanceOf(ReviewException.class);
		verifyNoInteractions(wordService);
	}
	@Test
	void 입력_검사기준과_후보버전을_동결해_검사를_접수한다() {
		when(repository.createRun(eq("VALIDATION"), anyString(), eq(99L))).thenReturn(7L);
		assertThat(service.startValidation(selection, 99)).isEqualTo(7);
		verify(repository).createRun(eq("VALIDATION"), argThat(s -> s.contains("dataset") && s.contains("기존금칙어")),
				eq(99L));
		verifyNoInteractions(wordService);
	}
	@Test
	void 경고는_검사대상_승인대상이_아니다() {
		when(repository.candidate(3, true)).thenReturn(warning());
		assertThatThrownBy(() -> service.startValidation(List.of(new Approval(3, 1)), 99))
				.isInstanceOf(ReviewException.class);
		verifyNoInteractions(wordService);
	}
	@Test
	void 검사후_수정된_후보는_승인되지_않는다() {
		passedRun(0, 0);
		when(repository.candidate(1, true)).thenReturn(word(1, 2, "PENDING"));
		assertThatThrownBy(() -> service.approveCandidates(7, selection, 99)).isInstanceOf(ReviewException.class);
		verifyNoInteractions(wordService);
	}
	@Test
	void 검사후_운영금칙어가_바뀌면_재검사한다() {
		passedRun(0, 0);
		when(validator.baseVersion()).thenReturn("changed");
		assertThatThrownBy(() -> service.approveCandidates(7, selection, 99)).isInstanceOf(ReviewException.class);
		verifyNoInteractions(wordService);
	}
	@Test
	void 검사자료가_바뀌면_재검사한다() {
		passedRun(0, 0);
		when(validator.datasetVersion()).thenReturn("changed");
		assertThatThrownBy(() -> service.approveCandidates(7, selection, 99)).isInstanceOf(ReviewException.class);
		verifyNoInteractions(wordService);
	}
	@Test
	void 오차단이_발견되면_승인되지_않는다() {
		passedRun(1, 0);
		assertThatThrownBy(() -> service.approveCandidates(7, selection, 99)).isInstanceOf(ReviewException.class);
		verifyNoInteractions(wordService);
	}
	@Test
	void 기존차단_누락이_있으면_승인되지_않는다() {
		passedRun(0, 1);
		assertThatThrownBy(() -> service.approveCandidates(7, selection, 99)).isInstanceOf(ReviewException.class);
		verifyNoInteractions(wordService);
	}
	@Test
	void 검사한_묶음과_다른_선택은_승인되지_않는다() {
		passedRun(0, 0);
		assertThatThrownBy(() -> service.approveCandidates(7, List.of(new Approval(2, 1)), 99))
				.isInstanceOf(ReviewException.class);
		verifyNoInteractions(wordService);
	}
	@Test
	void 검사를_통과한_후보만_기존등록서비스로_적용한다() {
		passedRun(0, 0);
		when(wordService.createForbiddenWord(any())).thenReturn(
				new ForbiddenWordResponseDto(55L, word.expression(), ForbiddenWordStatus.ACTIVE, LocalDateTime.now()));
		service.approveCandidates(7, selection, 99);
		verify(wordService).createForbiddenWord(argThat(dto -> dto.word().equals(word.expression())));
		verify(repository).status(1, "APPLIED", 55L);
		verify(repository).audit(any(), eq("APPLY"), eq(99L));
	}
	@Test
	void 이미적용된_후보를_중복승인하지_않는다() {
		passedRun(0, 0);
		when(repository.candidate(1, true)).thenReturn(word(1, 1, "APPLIED"));
		assertThatThrownBy(() -> service.approveCandidates(7, selection, 99)).isInstanceOf(ReviewException.class);
		verifyNoInteractions(wordService);
	}
	@Test
	void 해제했던_같은단어는_재검사후_중복등록없이_재활성화한다() {
		word = new Candidate(1, Kind.WORD, "ABUSE", "검증용모욕표현", "검토 필요", "HARMFUL_OUTPUT", 2, "PENDING", 55L, null, 1,
				0);
		selection = List.of(new Approval(1, 2));
		when(repository.candidate(1, true)).thenReturn(word);
		passedRun(0, 0);
		when(words.findById(55L)).thenReturn(java.util.Optional
				.of(ForbiddenWord.builder().word(word.expression()).status(ForbiddenWordStatus.INACTIVE).build()));
		service.approveCandidates(7, selection, 99);
		verify(wordService, never()).createForbiddenWord(any());
		verify(wordService).updateForbiddenWordStatus(eq(55L),
				argThat(dto -> dto.status() == ForbiddenWordStatus.ACTIVE));
	}
	@Test
	void 적용해제는_연결된_단어_한건만_비활성화한다() {
		when(repository.candidate(1, true)).thenReturn(word(1, 1, "APPLIED"));
		when(words.findById(55L)).thenReturn(java.util.Optional
				.of(ForbiddenWord.builder().word(word.expression()).status(ForbiddenWordStatus.ACTIVE).build()));
		service.updateCandidateDecision(1, 1, "DISABLE", 99);
		verify(wordService).updateForbiddenWordStatus(eq(55L),
				argThat(dto -> dto.status() == ForbiddenWordStatus.INACTIVE));
		verify(repository).status(1, "DISABLED", 55L);
	}
	@Test
	void 수동으로_바뀐_단어는_잘못_비활성화하지_않는다() {
		when(repository.candidate(1, true)).thenReturn(word(1, 1, "APPLIED"));
		when(words.findById(55L)).thenReturn(java.util.Optional.of(ForbiddenWord.builder().word("다른관리자수정").build()));
		assertThatThrownBy(() -> service.updateCandidateDecision(1, 1, "DISABLE", 99))
				.isInstanceOf(ReviewException.class);
		verifyNoInteractions(wordService);
	}
	@Test
	void 이미_등록된_단어는_검사를_접수하지_않는다() {
		when(words.existsByWord(word.expression())).thenReturn(true);
		assertThatThrownBy(() -> service.startValidation(selection, 99)).isInstanceOf(ReviewException.class)
				.hasMessageContaining("이미 등록된");
		verify(repository, never()).createRun(anyString(), anyString(), any());
	}
	@Test
	void 검사후_수동등록된_비활성_단어도_중복등록하지_않는다() {
		passedRun(0, 0);
		when(words.existsByWord(word.expression())).thenReturn(true);
		assertThatThrownBy(() -> service.approveCandidates(7, selection, 99)).isInstanceOf(ReviewException.class)
				.hasMessageContaining("이미 등록된");
		verifyNoInteractions(wordService);
	}
	@Test
	void 수동수정된_연결은_후보수정으로_해제하고_재검사를_요구한다() {
		when(repository.candidate(1, true)).thenReturn(word(1, 1, "APPLIED"));
		when(words.findById(55L)).thenReturn(java.util.Optional
				.of(ForbiddenWord.builder().word("수동 변경").status(ForbiddenWordStatus.ACTIVE).build()));
		service.updateCandidate(1, 1, "새 후보", "새 이유", 99);
		verify(repository).edit(eq(1L), eq("새 후보"), eq("새 이유"), anyString(), isNull());
		verifyNoInteractions(wordService);
	}
	@Test
	void 활성_연결단어의_후보는_기존대로_해제전_수정하지_않는다() {
		when(repository.candidate(1, true)).thenReturn(word(1, 1, "APPLIED"));
		when(words.findById(55L)).thenReturn(java.util.Optional
				.of(ForbiddenWord.builder().word(word.expression()).status(ForbiddenWordStatus.ACTIVE).build()));
		assertThatThrownBy(() -> service.updateCandidate(1, 1, "새 후보", "새 이유", 99)).isInstanceOf(ReviewException.class);
		verify(repository, never()).edit(anyLong(), anyString(), anyString(), anyString(), any());
	}
	@Test
	void 수동으로_비활성화된_연결은_유지하고_후보를_재검사할수있다() {
		when(repository.candidate(1, true)).thenReturn(word(1, 1, "APPLIED"));
		when(words.findById(55L)).thenReturn(java.util.Optional
				.of(ForbiddenWord.builder().word(word.expression()).status(ForbiddenWordStatus.INACTIVE).build()));
		service.updateCandidate(1, 1, word.expression(), "재검사", 99);
		verify(repository).edit(eq(1L), eq(word.expression()), eq("재검사"), anyString(), eq(55L));
		verifyNoInteractions(wordService);
	}
	@Test
	void 후보페이지는_기존_페이지응답을_재사용한다() {
		when(repository.getCandidatePage("WORD", 10, 20))
				.thenReturn(com.ubot.common.PageResponseDto.of(List.of(word), 10, 20, 201));
		assertThat(service.getCandidatePage(Kind.WORD, 10, 20).totalElements()).isEqualTo(201);
		assertThat(service.getCandidatePage(Kind.WORD, 10, 20).page()).isEqualTo(10);
	}
	@Test
	void 잘못된_후보페이지는_조회하지않는다() {
		assertThatThrownBy(() -> service.getCandidatePage(Kind.WORD, -1, 20)).isInstanceOf(ReviewException.class);
		assertThatThrownBy(() -> service.getCandidatePage(Kind.WARNING, 0, 200)).isInstanceOf(ReviewException.class);
		verify(repository, never()).getCandidatePage(anyString(), anyInt(), anyInt());
	}
	@Test
	void 경고_검토완료는_금칙어에_영향이_없다() {
		when(repository.candidate(3, true)).thenReturn(warning());
		service.updateCandidateDecision(3, 1, "REVIEWED", 99);
		verify(repository).status(3, "REVIEWED", null);
		verifyNoInteractions(wordService);
	}
	@Test
	void 이미_실행중인_분석은_중복접수하지않는다() {
		when(repository.analysisExists()).thenReturn(true);
		assertThatThrownBy(() -> service.startAnalysis(99L)).isInstanceOf(ReviewException.class);
		verify(repository, never()).snapshotSources(anyLong(), any());
	}
	@Test
	void 수동분석은_원본스냅샷만_만들고_모델을_부르지않는다() {
		when(repository.createRun(eq("ANALYSIS"), contains("FULL"), eq(99L))).thenReturn(4L);
		assertThat(service.startAnalysis(99L)).isEqualTo(4);
		verify(repository).snapshotSources(4, AnalysisMode.FULL);
	}
	@Test
	void 분석접수와_재개는_완료알림_잠금대신_전용잠금을_사용한다() {
		when(repository.settings(true)).thenReturn(new Settings(true, 60, 1, 1, LocalDateTime.now().minusMinutes(1)));
		service.startAnalysis(99L);
		service.scheduleDue();
		service.resumeAnalysis(4);
		verify(repository, atLeast(3)).analysisEnqueueLock();
		verify(repository, never()).analysisLock();
		verify(repository).resume(4);
	}
	@Test
	void 주기분석은_신규변경분만_접수한다() {
		when(repository.settings(true)).thenReturn(new Settings(true, 60, 1, 1, LocalDateTime.now().minusMinutes(1)));
		when(repository.createRun(eq("ANALYSIS"), contains("INCREMENTAL"), isNull())).thenReturn(8L);
		service.scheduleDue();
		verify(repository).snapshotSources(8, AnalysisMode.INCREMENTAL);
		verify(repository).advanceSchedule();
		verify(repository, never()).snapshotSources(anyLong(), eq(AnalysisMode.FULL));
		verifyNoInteractions(wordService);
	}
	@Test
	void 진행중인_분석이_있으면_주기실행과_다음시각을_증식시키지않는다() {
		when(repository.settings(true)).thenReturn(new Settings(true, 60, 1, 1, LocalDateTime.now().minusMinutes(1)));
		when(repository.analysisExists()).thenReturn(true);
		service.scheduleDue();
		verify(repository, never()).createRun(anyString(), anyString(), any());
		verify(repository, never()).advanceSchedule();
	}
	@Test
	void 상세가_만료된_검사는_후보와_기준이_같아도_승인할수없다() {
		passedRun(0, 0);
		when(repository.run(7)).thenReturn(new Run(7, "VALIDATION", "COMPLETED", 1, 1, LocalDateTime.now(), "{}",
				LocalDateTime.now(), null, false));
		assertThatThrownBy(() -> service.approveCandidates(7, selection, 99)).isInstanceOf(ReviewException.class);
		verify(repository, never()).request(7);
		verifyNoInteractions(wordService);
	}
	@Test
	void 예약시각_이전에는_분석하지않는다() {
		when(repository.settings(true)).thenReturn(new Settings(true, 60, 1, 1, LocalDateTime.now().plusHours(1)));
		service.scheduleDue();
		verify(repository, never()).createRun(any(), any(), any());
	}
	@Test
	void 비활성_주기는_분석하지않는다() {
		when(repository.settings(true)).thenReturn(new Settings(false, 60, 1, 1, LocalDateTime.now().minusHours(1)));
		service.scheduleDue();
		verify(repository, never()).createRun(any(), any(), any());
	}
	@Test
	void 잘못된_주기와_유사도는_저장하지않는다() {
		assertThatThrownBy(() -> service.updateSettings(true, 0, 1, 1, 99)).isInstanceOf(ReviewException.class);
		assertThatThrownBy(() -> service.updateSettings(true, 60, Double.NaN, 1, 99))
				.isInstanceOf(ReviewException.class);
	}
	@Test
	void AI가_원본에_없는_표현을_추천하면_거절한다() {
		assertThatThrownBy(() -> ReviewService.validateSuggestion(new Source(1, "one", "정상 문의", null),
				new Suggestion(Kind.WORD, "ABUSE", "지어낸표현", "검토", Stage.INPUT, "HARMFUL_REQUEST")))
				.isInstanceOf(ReviewException.class);
	}
	@Test
	void 피해신고_의도는_위험후보로_등록하지않는다() {
		assertThatThrownBy(() -> ReviewService.validateSuggestion(new Source(1, "one", "피해 신고", null),
				new Suggestion(Kind.WARNING, "FRAUD", "피해 신고", "검토", Stage.INPUT, "REPORT")))
				.isInstanceOf(ReviewException.class);
	}
	@ParameterizedTest
	@EnumSource(Kind.class)
	void 기존_검증요청의_벡터는_무시하고_후보정보와_검증기준은_유지한다(Kind kind) {
		String legacy = """
				{"selection":[{"candidateId":7,"revision":3}],"candidates":[
				 {"id":7,"kind":"%s","category":"ABUSE","expression":"검토 표현","reason":"검토 이유",
				  "intent":"HARMFUL_OUTPUT","revision":3,"status":"PENDING","wordId":null,
				  "profile":"legacy-profile","vector":%s,"occurrences":5,"unreadOccurrences":2}],
				 "words":["기존금칙어"],"baseVersion":"base","datasetVersion":"dataset"}
				""".formatted(kind.name(), json.writeValueAsString(new float[1024]));
		var request = json.readValue(legacy, ReviewService.TestRequest.class);
		assertThat(request.selection()).containsExactly(new Approval(7, 3));
		assertThat(request.candidates()).containsExactly(new Candidate(7, kind, "ABUSE", "검토 표현", "검토 이유",
				"HARMFUL_OUTPUT", 3, "PENDING", null, "legacy-profile", 5, 2));
		assertThat(request.words()).containsExactly("기존금칙어");
		assertThat(request.baseVersion()).isEqualTo("base");
		assertThat(request.datasetVersion()).isEqualTo("dataset");
		var serialized = json.readTree(json.writeValueAsString(request));
		assertThat(serialized.path("candidates").get(0).has("vector")).isFalse();
		assertThat(json.readValue(json.writeValueAsString(request), ReviewService.TestRequest.class))
				.isEqualTo(request);
	}
	@Test
	void Worker_결과의_규칙버전이_접수시_버전과_다르면_승인하지않는다() {
		passedRun(0, 0);
		var result = new Validation("different-worker-rules", "dataset",
				List.of(new CaseResult("safe", "NORMAL", Stage.INPUT, "정상", "정상", false, false, false, false)), 0, 0, 0,
				0);
		when(repository.run(7)).thenReturn(new Run(7, "VALIDATION", "COMPLETED", 1, 1, LocalDateTime.now(),
				json.writeValueAsString(result), LocalDateTime.now()));
		assertThatThrownBy(() -> service.approveCandidates(7, selection, 99)).isInstanceOf(ReviewException.class);
		verifyNoInteractions(wordService);
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"가", "요금", "요 금", "요금!!!", "요\u200B금"})
	void 짧은_표현으로_후보를_수정해도_실제금칙어나_후보를_갱신하지않는다(String expression) {
		assertThatThrownBy(() -> service.updateCandidate(1, 1, expression, "검토", 99))
				.isInstanceOfSatisfying(ReviewException.class, e -> assertThat(e.getErrorCode())
						.isEqualTo(com.ubot.guardreview.exception.ReviewErrorCode.WORD_TOO_SHORT));
		verify(repository, never()).edit(anyLong(), anyString(), anyString(), anyString(), any());
		verifyNoInteractions(wordService);
	}
	@Test
	void 기존에_저장된_짧은_후보도_재검사와_승인을_우회하지못한다() {
		word = new Candidate(1, Kind.WORD, "ABUSE", "요금", "검토", "HARMFUL_OUTPUT", 1, "PENDING", null, null, 1, 0);
		when(repository.candidate(1, true)).thenReturn(word);
		assertThatThrownBy(() -> service.startValidation(selection, 99)).isInstanceOfSatisfying(ReviewException.class,
				e -> assertThat(e.getErrorCode())
						.isEqualTo(com.ubot.guardreview.exception.ReviewErrorCode.WORD_TOO_SHORT));
		passedRun(0, 0);
		assertThatThrownBy(() -> service.approveCandidates(7, selection, 99))
				.isInstanceOfSatisfying(ReviewException.class, e -> assertThat(e.getErrorCode())
						.isEqualTo(com.ubot.guardreview.exception.ReviewErrorCode.WORD_TOO_SHORT));
		verify(repository, never()).createRun(anyString(), anyString(), any());
		verifyNoInteractions(wordService);
	}

}

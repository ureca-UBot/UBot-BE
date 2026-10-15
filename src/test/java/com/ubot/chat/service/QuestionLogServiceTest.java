package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.ranking.enums.RegionSido;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("질문 로그의 하위 질문·지역·랭킹 대상 저장 테스트")
class QuestionLogServiceTest {
	private final QuestionLogRepository questionLogRepository = mock(QuestionLogRepository.class);
	private final IpRegionResolver ipRegionResolver = mock(IpRegionResolver.class);
	private final QuestionLogService questionLogService = new QuestionLogService(questionLogRepository, ipRegionResolver);

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(questionLogService, "rankingWindowMinutes", 60);
		when(questionLogRepository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@ParameterizedTest
	@EnumSource(RegionSido.class)
	@DisplayName("지원하는 모든 지역은 첫 질문의 지역으로 저장하고 랭킹 대상에 포함한다")
	void savesEverySupportedRegionAndMarksFirstQuestionAsRankingEligible(RegionSido region) {
		String ip = region.name() + "-ip";
		when(ipRegionResolver.resolve(ip)).thenReturn(Optional.of(region));
		when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
				anyLong(), anyString(), any(), any())).thenReturn(false);

		QuestionLog saved = saveOriginal(1L, null, " 질문 ? ", ip);

		assertThat(saved.getUserId()).isEqualTo(1L);
		assertThat(saved.getUserQuestion()).isEqualTo(" 질문 ? ");
		assertThat(saved.getNormalizedQuestion()).isEqualTo("질문");
		assertThat(saved.getRankingEligible()).isTrue();
		assertThat(saved.getRegion()).isEqualTo(region);
		verify(questionLogRepository).acquireAdvisoryLock(("1:질문").hashCode());
	}

	@Test
	@DisplayName("하위 질문마다 원문과 함께 한 행으로 저장하고, 랭킹 중복 판정은 하위 질문으로 한다")
	void savesEachSubQuestionWithOriginalQuestion() {
		when(ipRegionResolver.resolve("127.0.0.1")).thenReturn(Optional.empty());
		when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
				anyLong(), anyString(), any(), any())).thenReturn(false);
		when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
				eq(1L), eq("5g 프리미엄 데이터 제공량"), any(), any())).thenReturn(true);

		List<QuestionLog> saved = questionLogService.saveTurn(1L, null, 100L, "5G 프리미엄 요금이랑 데이터 알려줘",
				List.of("5G 프리미엄 월 요금", "5G 프리미엄 데이터 제공량?"), "답변", "127.0.0.1");

		assertThat(saved)
				.extracting(QuestionLog::getAttemptId, QuestionLog::getUserQuestion, QuestionLog::getSubQuestion,
						QuestionLog::getSubQuestionIndex, QuestionLog::getNormalizedQuestion,
						QuestionLog::getRankingEligible, QuestionLog::getAnswer)
				.containsExactly(
						tuple(100L, "5G 프리미엄 요금이랑 데이터 알려줘", "5G 프리미엄 월 요금", 0,
								"5g 프리미엄 월 요금", true, "답변"),
						tuple(100L, "5G 프리미엄 요금이랑 데이터 알려줘", "5G 프리미엄 데이터 제공량?", 1,
								"5g 프리미엄 데이터 제공량", false, "답변"));
	}

	@Test
	@DisplayName("하위 질문마다 잡는 잠금은 교착을 막기 위해 항상 같은 순서로 잡는다")
	void acquiresLocksInSortedOrder() {
		when(ipRegionResolver.resolve("127.0.0.1")).thenReturn(Optional.empty());
		long first = ("1:b").hashCode();
		long second = ("1:a").hashCode();

		questionLogService.saveTurn(1L, null, 100L, "원문", List.of("b", "a"), "답변", "127.0.0.1");

		InOrder order = inOrder(questionLogRepository);
		order.verify(questionLogRepository).acquireAdvisoryLock(Math.min(first, second));
		order.verify(questionLogRepository).acquireAdvisoryLock(Math.max(first, second));
	}

	@Test
	@DisplayName("한 턴 안에서 같은 하위 질문이 두 번 나오면 처음 것만 랭킹 대상이고 잠금도 한 번만 잡는다")
	void countsSameSubQuestionInOneTurnOnce() {
		when(ipRegionResolver.resolve("127.0.0.1")).thenReturn(Optional.empty());
		when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
				anyLong(), anyString(), any(), any())).thenReturn(false);

		List<QuestionLog> saved = questionLogService.saveTurn(
				1L, null, 100L, "원문", List.of("유심 재발급", "유심 재발급?"), "답변", "127.0.0.1");

		assertThat(saved).extracting(QuestionLog::getRankingEligible).containsExactly(true, false);
		verify(questionLogRepository, times(1)).acquireAdvisoryLock(anyLong());
	}

	@Test
	@DisplayName("할당된 지역이 없는 IP는 지역을 null로 저장한다")
	void savesNullRegionWhenIpHasNoAssignedRegion() {
		when(ipRegionResolver.resolve("127.0.0.1")).thenReturn(Optional.empty());
		when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
				anyLong(), anyString(), any(), any())).thenReturn(false);

		QuestionLog saved = saveOriginal(1L, null, "질문", "127.0.0.1");

		assertThat(saved.getRegion()).isNull();
		assertThat(saved.getRankingEligible()).isTrue();
	}

	@Test
	@DisplayName("게스트 질문은 잠금과 중복 검사 없이 랭킹 대상에서 제외해 저장한다")
	void savesGuestQuestionWithoutLockOrRankingCheck() {
		when(ipRegionResolver.resolve("region-ip")).thenReturn(Optional.of(RegionSido.SEOUL));

		QuestionLog saved = saveOriginal(null, 7L, "질문", "region-ip");

		assertThat(saved.getUserId()).isNull();
		assertThat(saved.getConversationId()).isEqualTo(7L);
		assertThat(saved.getRankingEligible()).isFalse();
		verify(questionLogRepository, never()).acquireAdvisoryLock(anyLong());
		verify(questionLogRepository, never()).existsByUserIdAndNormalizedQuestionInRankingWindow(
				any(), anyString(), any(), any());
	}

	@Test
	@DisplayName("랭킹 시간 창 안의 같은 유저·같은 질문은 랭킹 대상에서 제외한다")
	void marksDuplicateQuestionAsNotRankingEligible() {
		when(ipRegionResolver.resolve("region-ip")).thenReturn(Optional.of(RegionSido.SEOUL));
		when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
				anyLong(), anyString(), any(), any())).thenReturn(true);

		QuestionLog saved = saveOriginal(1L, null, "질문", "region-ip");

		assertThat(saved.getRankingEligible()).isFalse();
		assertThat(saved.getRegion()).isEqualTo(RegionSido.SEOUL);
	}

	/** 질문을 나누지 않은 턴을 저장합니다. 원문이 하위 질문 하나가 됩니다. */
	private QuestionLog saveOriginal(Long userId, Long conversationId, String question, String ip) {
		List<QuestionLog> saved = questionLogService.saveTurn(
				userId, conversationId, 100L, question, List.of(question), "답변", ip);
		assertThat(saved).hasSize(1);
		assertThat(saved.getFirst().getSubQuestion()).isEqualTo(question);
		assertThat(saved.getFirst().getSubQuestionIndex()).isZero();
		return saved.getFirst();
	}
}

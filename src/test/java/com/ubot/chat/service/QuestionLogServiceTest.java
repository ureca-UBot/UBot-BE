package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.ranking.enums.RegionSido;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("질문 로그의 지역·랭킹 대상 저장 테스트")
class QuestionLogServiceTest {
	private final QuestionLogRepository questionLogRepository = mock(QuestionLogRepository.class);
	private final IpRegionResolver ipRegionResolver = mock(IpRegionResolver.class);
	private final QuestionLogService questionLogService = new QuestionLogService(questionLogRepository, ipRegionResolver);

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(questionLogService, "rankingWindowMinutes", 60);
		when(questionLogRepository.saveAndFlush(any(QuestionLog.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	@ParameterizedTest
	@EnumSource(RegionSido.class)
	@DisplayName("지원하는 모든 지역은 첫 질문의 지역으로 저장하고 랭킹 대상에 포함한다")
	void savesEverySupportedRegionAndMarksFirstQuestionAsRankingEligible(RegionSido region) {
		String ip = region.name() + "-ip";
		when(ipRegionResolver.resolve(ip)).thenReturn(Optional.of(region));
		when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
				anyLong(), anyString(), any(), any())).thenReturn(false);

		QuestionLog saved = questionLogService.saveAndFlush(1L, " 질문 ? ", "답변", ip);

		assertThat(saved.getUserId()).isEqualTo(1L);
		assertThat(saved.getUserQuestion()).isEqualTo(" 질문 ? ");
		assertThat(saved.getNormalizedQuestion()).isEqualTo("질문");
		assertThat(saved.getRankingEligible()).isTrue();
		assertThat(saved.getRegion()).isEqualTo(region);
		verify(questionLogRepository).acquireAdvisoryLock(("1:질문").hashCode());
	}

	@Test
	@DisplayName("할당된 지역이 없는 IP는 지역을 null로 저장한다")
	void savesNullRegionWhenIpHasNoAssignedRegion() {
		when(ipRegionResolver.resolve("127.0.0.1")).thenReturn(Optional.empty());
		when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
				anyLong(), anyString(), any(), any())).thenReturn(false);

		QuestionLog saved = questionLogService.saveAndFlush(1L, "질문", "답변", "127.0.0.1");

		assertThat(saved.getRegion()).isNull();
		assertThat(saved.getRankingEligible()).isTrue();
	}

	@Test
	@DisplayName("랭킹 시간 창 안의 같은 유저·같은 질문은 랭킹 대상에서 제외한다")
	void marksDuplicateQuestionAsNotRankingEligible() {
		when(ipRegionResolver.resolve("region-ip")).thenReturn(Optional.of(RegionSido.SEOUL));
		when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
				anyLong(), anyString(), any(), any())).thenReturn(true);

		QuestionLog saved = questionLogService.saveAndFlush(1L, "질문", "답변", "region-ip");

		assertThat(saved.getRankingEligible()).isFalse();
		assertThat(saved.getRegion()).isEqualTo(RegionSido.SEOUL);
	}
}

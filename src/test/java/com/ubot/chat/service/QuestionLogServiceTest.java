package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.service.TelemapOutboxService;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("질문 로그 서비스")
class QuestionLogServiceTest {

    private final QuestionLogRepository questionLogRepository = mock(QuestionLogRepository.class);
    private final IpRegionResolver ipRegionResolver = mock(IpRegionResolver.class);
    private final TelemapOutboxService telemapOutboxService = mock(TelemapOutboxService.class);
    private final QuestionLogService questionLogService =
            new QuestionLogService(questionLogRepository, ipRegionResolver, telemapOutboxService);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(questionLogService, "rankingWindowMinutes", 60);
        when(questionLogRepository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @ParameterizedTest
    @EnumSource(RegionSido.class)
    @DisplayName("원문 질문을 정규화하고 접수 지역을 모든 하위 질문 로그에 저장한다")
    void normalizesOriginalQuestionAndStoresRegion(RegionSido region) {
        when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
                anyLong(), anyString(), any(), any())).thenReturn(false);

        List<QuestionLog> saved = questionLogService.saveTurn(
                1L, 2L, 3L, "  5G   요금제?!  ",
                subQuestions("5G 프리미엄 요금", "데이터 제공량"), "답변", region);

        assertThat(saved)
                .extracting(QuestionLog::getNormalizedQuestion, QuestionLog::getRankingEligible,
                        QuestionLog::getRegion)
                .containsExactly(
                        tuple("5g 요금제", true, region),
                        tuple("5g 요금제", true, region));
        verify(questionLogRepository).acquireAdvisoryLock((long) "1:5g 요금제".hashCode());
        verify(telemapOutboxService).enqueueSurgingQuestions(saved);
    }

    @Test
    @DisplayName("하위 질문 내용과 관계없이 원문 질문의 중복 여부를 턴 전체에 적용한다")
    void appliesOriginalQuestionEligibilityToWholeTurn() {
        when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
                anyLong(), anyString(), any(), any())).thenReturn(true);

        List<QuestionLog> saved = questionLogService.saveTurn(
                1L, null, 100L, "로밍 요금 알려줘",
                subQuestions("일본 로밍 요금", "로밍 데이터 제공량"), "답변", RegionSido.SEOUL);

        assertThat(saved).extracting(QuestionLog::getNormalizedQuestion)
                .containsExactly("로밍 요금 알려줘", "로밍 요금 알려줘");
        assertThat(saved).extracting(QuestionLog::getRankingEligible)
                .containsExactly(false, false);
        verify(questionLogRepository).existsByUserIdAndNormalizedQuestionInRankingWindow(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("로밍 요금 알려줘"), any(), any());
    }

    @Test
    @DisplayName("최초 원문 질문이면 동일한 하위 질문이 반복되어도 턴 전체를 집계 대상으로 저장한다")
    void marksEveryLogEligibleWhenOriginalQuestionIsNew() {
        when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
                anyLong(), anyString(), any(), any())).thenReturn(false);

        List<QuestionLog> saved = questionLogService.saveTurn(
                1L, null, 100L, "새 원문", subQuestions("같은 질문", "같은 질문"), "답변",
                (RegionSido) null);

        assertThat(saved).extracting(QuestionLog::getRankingEligible).containsExactly(true, true);
        verify(questionLogRepository).acquireAdvisoryLock((long) "1:새 원문".hashCode());
    }

    @Test
    @DisplayName("게스트 질문은 중복 조회와 잠금 없이 모든 로그를 집계 대상에서 제외한다")
    void excludesGuestQuestionWithoutRankingCheck() {
        List<QuestionLog> saved = questionLogService.saveTurn(
                null, 7L, 100L, "게스트 질문", subQuestions("하위 질문 1", "하위 질문 2"),
                "답변", RegionSido.BUSAN);

        assertThat(saved).extracting(QuestionLog::getRankingEligible).containsExactly(false, false);
        assertThat(saved).extracting(QuestionLog::getNormalizedQuestion)
                .containsExactly("게스트 질문", "게스트 질문");
        verify(questionLogRepository, never()).acquireAdvisoryLock(anyLong());
        verify(questionLogRepository, never()).existsByUserIdAndNormalizedQuestionInRankingWindow(
                any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("IP 진입점은 해석한 지역을 저장하고 저장된 로그를 통신 지도 Outbox에 전달한다")
    void resolvesIpAndEnqueuesSavedLogs() {
        when(ipRegionResolver.resolve("127.0.0.1")).thenReturn(Optional.of(RegionSido.DAEGU));
        when(questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
                anyLong(), anyString(), any(), any())).thenReturn(false);

        List<QuestionLog> saved = questionLogService.saveTurn(
                1L, null, 100L, "질문", subQuestions("하위 질문"), "답변", "127.0.0.1");

        assertThat(saved).singleElement().extracting(QuestionLog::getRegion).isEqualTo(RegionSido.DAEGU);
        verify(telemapOutboxService).enqueueSurgingQuestions(saved);
    }

    private static List<SubQuestionFaqs> subQuestions(String... queries) {
        return Arrays.stream(queries)
                .map(query -> new SubQuestionFaqs(query, List.of(), query + " 원문 조각"))
                .toList();
    }
}

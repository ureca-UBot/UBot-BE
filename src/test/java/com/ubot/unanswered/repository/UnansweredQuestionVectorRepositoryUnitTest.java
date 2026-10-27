package com.ubot.unanswered.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ubot.ai.service.GuardPipelineFixtures.*;
import com.ubot.unanswered.enums.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** SQL을 실행하지 않고 기존 저장소에 전달하는 구문·바인딩만 확인합니다. */
class UnansweredQuestionVectorRepositoryUnitTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UnansweredQuestionVectorRepository repository = new UnansweredQuestionVectorRepository(jdbc);

    @Test void 반려_검색은_반려상태와_같은프로필_전용기준으로_분리한다() {
        var vector = vector();
        repository.findRejectedGroups(vector, 9L, .97, 20);
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("g.status = 'REJECTED'", "uge.profile_id = ?", "LIMIT ?")
                .doesNotContain("'PENDING'", "'ON_HOLD'");
        assertThat(args.getValue()).containsExactly(vector, 9L, vector, .97, vector, 20);
    }
    @Test void 기존_대기_보류_검색에는_반려를_넣지_않는다() {
        var vector = vector();
        repository.findNearestGroupId(vector, 9L, .6);
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("'PENDING'", "'ON_HOLD'", "uge.profile_id = ?").doesNotContain("'REJECTED'");
        assertThat(args.getValue()).containsExactly(9L, vector, .6, vector);
    }
    @Test void 미응답_질문은_발생_경로_없이_저장한다() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(501L);
        repository.saveQuestion(100L, 1, 50L, QUESTION, QUESTION, vector(), 9L,
                UnansweredReason.INSUFFICIENT_FAQ, 17L, .91);
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), args.capture());
        assertThat(sql.getValue()).contains("INSERT INTO unanswered_questions").doesNotContain("source");
        assertThat(args.getValue()).containsExactly(100L, 1, 50L, QUESTION, QUESTION,
                "INSUFFICIENT_FAQ", 17L, .91);
    }
}

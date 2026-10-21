package com.ubot.unanswered.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.unanswered.enums.UnansweredGroupStatus;
import com.ubot.unanswered.repository.UnansweredGroupNamingRepository;
import com.ubot.unanswered.repository.UnansweredQuestionGroupRepository;

@SpringBootTest
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("미응답 묶음 LLM 이름·카테고리 추천 테스트")
class UnansweredGroupNamingServiceTest {

    @Autowired
    private UnansweredGroupNamingService namingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UnansweredGroupNamingRepository namingRepository;

    @Autowired
    private UnansweredQuestionGroupRepository groupRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private LlmService llmService;

    private Long userId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM unanswered_questions");
        jdbcTemplate.update("DELETE FROM unanswered_question_groups");
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, name) VALUES (?, 'hash', '테스터') RETURNING user_id",
                Long.class, UUID.randomUUID() + "@test.com");
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO faq_category (name) VALUES (?) RETURNING id", Long.class, "가입/개통-" + UUID.randomUUID());
    }

    @Test
    @DisplayName("이름이 없는 묶음에 LLM이 정한 주제 이름과 목록에 있는 카테고리를 저장한다")
    void namesGroupWithCategory() {
        Long groupId = group("개통할때 머 들고가야돼요", 2, "PENDING", null, null);
        question(groupId, "개통할때 머 들고가야돼요");
        question(groupId, "개통하러 갈 때 뭐 챙겨요?");
        String category = categoryName();
        given(llmService.generateAnswer(any())).willReturn(
                answer("{\"topic\": \"개통 시 필요 서류\", \"category\": \"" + category + "\"}"));

        assertThat(namingService.nameGroups()).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT topic_name, suggested_category_id, named_question_count FROM unanswered_question_groups WHERE id = ?",
                groupId);
        assertThat(row.get("topic_name")).isEqualTo("개통 시 필요 서류");
        assertThat(row.get("suggested_category_id")).isEqualTo(categoryId);
        assertThat(row.get("named_question_count")).isEqualTo(2);

        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().hasResponseSchema()).isTrue();
        assertThat(request.getValue().messages().get(1).content())
                .contains(category)
                .contains("개통하러 갈 때 뭐 챙겨요?");
    }

    @Test
    @DisplayName("목록에 없는 카테고리는 저장하지 않고 이름만 저장한다")
    void ignoresUnknownCategory() {
        Long groupId = group("하이", 1, "PENDING", null, null);
        given(llmService.generateAnswer(any())).willReturn(answer("{\"topic\": \"잡담\", \"category\": \"잡담\"}"));

        namingService.nameGroups();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT topic_name FROM unanswered_question_groups WHERE id = ?", String.class, groupId)).isEqualTo("잡담");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT suggested_category_id FROM unanswered_question_groups WHERE id = ?", Long.class, groupId)).isNull();
    }

    @Test
    @DisplayName("처리 완료·반려 묶음과 이미 이름이 있고 질문이 2배로 늘지 않은 묶음은 건너뛴다")
    void selectsOnlyTargets() {
        group("승인된 묶음", 3, "APPROVED", null, null);
        group("반려된 묶음", 3, "REJECTED", null, null);
        group("이름 있는 묶음", 3, "PENDING", "이미 붙은 이름", 2);
        Long grown = group("질문이 늘어난 묶음", 4, "ON_HOLD", "예전 이름", 2);
        given(llmService.generateAnswer(any())).willReturn(answer("{\"topic\": \"새로 붙인 이름\", \"category\": null}"));

        assertThat(namingService.nameGroups()).isEqualTo(1);

        verify(llmService, times(1)).generateAnswer(any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT topic_name FROM unanswered_question_groups WHERE id = ?", String.class, grown))
                .isEqualTo("새로 붙인 이름");
    }

    @Test
    @DisplayName("LLM 호출이 실패한 묶음은 그대로 두고 다음 묶음을 계속 처리한다")
    void keepsGoingWhenLlmFails() {
        Long failed = group("실패할 묶음", 5, "PENDING", null, null);
        Long succeeded = group("성공할 묶음", 1, "PENDING", null, null);
        given(llmService.generateAnswer(any()))
                .willThrow(new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID))
                .willReturn(answer("{\"topic\": \"로밍 신청 방법\", \"category\": null}"));

        assertThat(namingService.nameGroups()).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT topic_name FROM unanswered_question_groups WHERE id = ?", String.class, failed)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT topic_name FROM unanswered_question_groups WHERE id = ?", String.class, succeeded))
                .isEqualTo("로밍 신청 방법");
    }

    @Test
    @DisplayName("이름 붙이기를 시도한 묶음은 질문 수가 많아도 아직 시도하지 않은 묶음 뒤로 밀린다")
    void putsAttemptedGroupsBehind() {
        Long attempted = group("자주 실패하는 묶음", 50, "PENDING", null, null);
        Long fresh = group("처음 보는 묶음", 1, "PENDING", null, null);
        namingRepository.markAttempted(attempted);

        assertThat(namingRepository.findTargets(1)).extracting(UnansweredGroupNamingRepository.NamingTarget::id)
                .containsExactly(fresh);
    }

    @Test
    @DisplayName("이름을 정하는 동안 처리가 끝난 묶음에는 저장하지 않는다")
    void skipsGroupClosedDuringNaming() {
        Long groupId = group("처리 중 승인될 묶음", 2, "PENDING", null, null);
        given(llmService.generateAnswer(any())).willAnswer(call -> {
            jdbcTemplate.update("UPDATE unanswered_question_groups SET status = 'APPROVED' WHERE id = ?", groupId);
            return answer("{\"topic\": \"늦게 온 이름\", \"category\": null}");
        });

        assertThat(namingService.nameGroups()).isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT topic_name FROM unanswered_question_groups WHERE id = ?", String.class, groupId)).isNull();
    }

    @Test
    @DisplayName("관리자가 상태를 바꿔도 JPA가 네이밍 결과를 옛 값으로 덮어쓰지 않는다")
    void jpaUpdateKeepsNaming() {
        Long groupId = group("상태가 바뀔 묶음", 2, "PENDING", null, null);

        transactionTemplate.executeWithoutResult(status -> {
            var group = groupRepository.findById(groupId).orElseThrow();
            jdbcTemplate.update("UPDATE unanswered_question_groups SET topic_name = '새 이름' WHERE id = ?", groupId);
            group.updateStatus(UnansweredGroupStatus.ON_HOLD);
        });

        assertThat(jdbcTemplate.queryForMap(
                "SELECT topic_name, status FROM unanswered_question_groups WHERE id = ?", groupId))
                .containsEntry("topic_name", "새 이름")
                .containsEntry("status", "ON_HOLD");
    }

    @Test
    @DisplayName("추천 카테고리가 삭제되면 묶음 응답에서 추천 카테고리를 비운다")
    void hidesDeletedSuggestedCategory() {
        Long groupId = group("카테고리가 지워질 묶음", 2, "PENDING", "개통 서류", 2);
        jdbcTemplate.update("UPDATE unanswered_question_groups SET suggested_category_id = ? WHERE id = ?", categoryId, groupId);
        jdbcTemplate.update("UPDATE faq_category SET deleted_at = LOCALTIMESTAMP WHERE id = ?", categoryId);

        assertThat(groupRepository.findById(groupId).orElseThrow().getSuggestedCategoryId()).isNull();
    }

    @Test
    @DisplayName("대상 묶음이 없으면 LLM을 부르지 않는다")
    void skipsWhenNoTarget() {
        assertThat(namingService.nameGroups()).isZero();

        verify(llmService, never()).generateAnswer(any());
    }

    @Test
    @DisplayName("LLM 출력이 JSON이 아니거나 이름이 비었거나 너무 길면 저장하지 않는다")
    void rejectsInvalidOutput() {
        Map<String, Long> categories = Map.of("가입/개통", 1L);

        assertThat(UnansweredGroupNamingService.parse("개통 서류", categories)).isEmpty();
        assertThat(UnansweredGroupNamingService.parse("{\"topic\": \"  \", \"category\": null}", categories)).isEmpty();
        assertThat(UnansweredGroupNamingService.parse(
                "{\"topic\": \"" + "가".repeat(31) + "\", \"category\": null}", categories)).isEmpty();
        assertThat(UnansweredGroupNamingService.parse("{\"topic\": \"개통 서류\", \"category\": \"가입/개통\"}", categories))
                .contains(new UnansweredGroupNamingService.Naming("개통 서류", 1L));
    }

    private String categoryName() {
        return jdbcTemplate.queryForObject("SELECT name FROM faq_category WHERE id = ?", String.class, categoryId);
    }

    private Long group(String question, int count, String status, String topicName, Integer namedCount) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO unanswered_question_groups
                    (representative_question, question_count, status, last_occurred_at, topic_name, named_question_count)
                VALUES (?, ?, ?, now(), ?, ?)
                RETURNING id
                """,
                Long.class, question, count, status, topicName, namedCount);
    }

    private void question(Long groupId, String question) {
        Long attemptId = jdbcTemplate.queryForObject(
                "INSERT INTO answer_attempts_history (user_id, question, attempt_count, status, idempotency_key) VALUES (?, ?, 1, 'FAIL', ?) RETURNING attempt_id",
                Long.class, userId, question, UUID.randomUUID().toString());
        jdbcTemplate.update(
                "INSERT INTO unanswered_questions (attempt_id, group_id, sub_question, user_text, reason) VALUES (?, ?, ?, ?, 'NO_FAQ')",
                attemptId, groupId, question, question);
    }

    private static LlmResponseDto answer(String content) {
        return new LlmResponseDto(content);
    }
}

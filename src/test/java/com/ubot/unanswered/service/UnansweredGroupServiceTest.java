package com.ubot.unanswered.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.common.GlobalException;
import com.ubot.common.PageResponseDto;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.unanswered.dto.request.UnansweredGroupStatusUpdateRequestDto;
import com.ubot.unanswered.dto.response.UnansweredGroupDetailResponseDto;
import com.ubot.unanswered.dto.response.UnansweredGroupResponseDto;
import com.ubot.unanswered.dto.response.UnansweredQuestionResponseDto;
import com.ubot.unanswered.enums.UnansweredGroupStatus;
import com.ubot.unanswered.exception.UnansweredErrorCode;
import com.ubot.unanswered.exception.UnansweredException;

@SpringBootTest
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("관리자 미응답 질문 묶음 조회·처리 통합 테스트")
class UnansweredGroupServiceTest {
	@Autowired private UnansweredGroupService service;
	@Autowired private JdbcTemplate jdbcTemplate;

	private Long userId;
	private Long roamingGroupId;
	private Long penaltyGroupId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM unanswered_questions");
		jdbcTemplate.update("DELETE FROM unanswered_question_groups");
		userId = jdbcTemplate.queryForObject(
				"INSERT INTO users (email, password_hash, name) VALUES (?, 'hash', '테스터') RETURNING user_id",
				Long.class, UUID.randomUUID() + "@test.com");
		roamingGroupId = group("해외에서 데이터가 안 돼요", 3, "PENDING", "2026-09-30 10:00:00");
		penaltyGroupId = group("요금제 해지 위약금", 1, "PENDING", "2026-09-30 12:00:00");
		group("반려된 질문", 5, "REJECTED", "2026-09-29 09:00:00");
		question(roamingGroupId, "해외에서 데이터가 안 돼요", "2026-09-30 09:00:00");
		question(roamingGroupId, "로밍 중 인터넷이 안 돼요", "2026-09-30 10:00:00");
	}

	@Test
	@DisplayName("상태로 거르고 최근 발생순으로 정렬한다")
	void listsByStatusRecentFirst() {
		PageResponseDto<UnansweredGroupResponseDto> result =
				service.getUnansweredGroupList(UnansweredGroupStatus.PENDING, 1, "recent", 0, 20);

		assertThat(result.content()).extracting(UnansweredGroupResponseDto::id)
				.containsExactly(penaltyGroupId, roamingGroupId);
	}

	@Test
	@DisplayName("상태를 지정하지 않으면 전체를 발생 횟수순으로 정렬한다")
	void listsAllByCount() {
		PageResponseDto<UnansweredGroupResponseDto> result = service.getUnansweredGroupList(null, 1, "count", 0, 20);

		assertThat(result.content()).extracting(UnansweredGroupResponseDto::questionCount)
				.containsExactly(5, 3, 1);
	}

	@Test
	@DisplayName("최소 발생 횟수보다 적은 묶음은 제외한다")
	void excludesGroupsBelowMinCount() {
		PageResponseDto<UnansweredGroupResponseDto> result =
				service.getUnansweredGroupList(UnansweredGroupStatus.PENDING, 2, "recent", 0, 20);

		assertThat(result.content()).extracting(UnansweredGroupResponseDto::id)
				.containsExactly(roamingGroupId);
	}

	@Test
	@DisplayName("허용하지 않은 정렬이나 페이지 크기는 거절한다")
	void rejectsInvalidSortOrSize() {
		assertThatThrownBy(() -> service.getUnansweredGroupList(null, 1, "name", 0, 20))
				.isInstanceOfSatisfying(GlobalException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
		assertThatThrownBy(() -> service.getUnansweredGroupList(null, 1, "recent", 0, 7))
				.isInstanceOf(GlobalException.class);
	}

	@Test
	@DisplayName("상세 조회는 소속 질문을 최근순으로 함께 반환한다")
	void returnsDetailWithQuestions() {
		UnansweredGroupDetailResponseDto detail = service.getUnansweredGroup(roamingGroupId);

		assertThat(detail.representativeQuestion()).isEqualTo("해외에서 데이터가 안 돼요");
		assertThat(detail.questions()).extracting(UnansweredQuestionResponseDto::question)
				.containsExactly("로밍 중 인터넷이 안 돼요", "해외에서 데이터가 안 돼요");
	}

	@Test
	@DisplayName("처리 상태를 바꾼다")
	void updatesStatus() {
		UnansweredGroupResponseDto result = service.updateUnansweredGroupStatus(
				roamingGroupId, new UnansweredGroupStatusUpdateRequestDto(UnansweredGroupStatus.APPROVED));

		assertThat(result.status()).isEqualTo(UnansweredGroupStatus.APPROVED);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM unanswered_question_groups WHERE id = ?", String.class, roamingGroupId))
				.isEqualTo("APPROVED");
	}

	@Test
	@DisplayName("없는 묶음은 UQ-001을 반환한다")
	void failsWhenGroupMissing() {
		assertThatThrownBy(() -> service.getUnansweredGroup(-1L))
				.isInstanceOfSatisfying(UnansweredException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(UnansweredErrorCode.UNANSWERED_GROUP_NOT_FOUND));
	}

	private Long group(String question, int count, String status, String lastOccurredAt) {
		return jdbcTemplate.queryForObject(
				"""
				INSERT INTO unanswered_question_groups (representative_question, question_count, status, last_occurred_at)
				VALUES (?, ?, ?, ?::timestamp)
				RETURNING id
				""",
				Long.class, question, count, status, lastOccurredAt);
	}

	private void question(Long groupId, String question, String createdAt) {
		Long attemptId = jdbcTemplate.queryForObject(
				"INSERT INTO answer_attempts_history (user_id, question, attempt_count, status, idempotency_key) VALUES (?, ?, 1, 'FAIL', ?) RETURNING attempt_id",
				Long.class, userId, question, UUID.randomUUID().toString());
		jdbcTemplate.update(
				"""
				INSERT INTO unanswered_questions (attempt_id, group_id, question, reason, created_at)
				VALUES (?, ?, ?, 'NO_FAQ', ?::timestamp)
				""",
				attemptId, groupId, question, createdAt);
	}
}

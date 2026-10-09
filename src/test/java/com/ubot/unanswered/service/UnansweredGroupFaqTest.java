package com.ubot.unanswered.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.enums.Intent;
import com.ubot.unanswered.dto.request.UnansweredGroupFaqCreateRequestDto;
import com.ubot.unanswered.dto.request.UnansweredGroupStatusUpdateRequestDto;
import com.ubot.unanswered.dto.response.UnansweredGroupResponseDto;
import com.ubot.unanswered.enums.UnansweredGroupStatus;
import com.ubot.unanswered.exception.UnansweredErrorCode;
import com.ubot.unanswered.exception.UnansweredException;

@SpringBootTest
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("미응답 질문 묶음 FAQ 등록 통합 테스트")
class UnansweredGroupFaqTest {
	@Autowired private UnansweredGroupService service;
	@Autowired private JdbcTemplate jdbcTemplate;
	@MockitoBean private EmbeddingService embeddingService;

	private Long adminId;
	private Long categoryId;
	private Long groupId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM unanswered_questions");
		jdbcTemplate.update("DELETE FROM unanswered_question_groups");
		adminId = jdbcTemplate.queryForObject(
				"INSERT INTO users (email, password_hash, name, role) VALUES (?, 'hash', '관리자', 'ADMIN') RETURNING user_id",
				Long.class, UUID.randomUUID() + "@test.com");
		categoryId = jdbcTemplate.queryForObject(
				"INSERT INTO faq_category (name) VALUES (?) RETURNING id", Long.class, "로밍-" + UUID.randomUUID());
		groupId = jdbcTemplate.queryForObject(
				"""
				INSERT INTO unanswered_question_groups (representative_question, centroid, question_count)
				VALUES ('해외에서 데이터가 안 돼요', array_fill(0, ARRAY[1024])::vector, 3)
				RETURNING id
				""",
				Long.class);
		float[] values = new float[1024];
		values[0] = 1.0f;
		given(embeddingService.embedText(anyString())).willReturn(new PGvector(values));
	}

	@Test
	@DisplayName("질문을 비우면 대표 질문으로 FAQ를 등록하고 묶음을 처리 완료로 바꾼다")
	void createsFaqFromRepresentativeQuestion() {
		UnansweredGroupResponseDto result = service.createUnansweredGroupFaq(
				groupId, request(null), adminId);

		assertThat(result.status()).isEqualTo(UnansweredGroupStatus.APPROVED);
		assertThat(result.resolvedFaqId()).isNotNull();
		assertThat(jdbcTemplate.queryForObject(
				"""
				SELECT question || '|' || answer || '|' || intent
				FROM faq
				WHERE id = ?
				""",
				String.class,
				result.resolvedFaqId()
		))
				.isEqualTo(
						"해외에서 데이터가 안 돼요|로밍 요금제를 확인해 주세요.|GENERAL"
				);

		Boolean embeddingStored = jdbcTemplate.queryForObject(
				"""
				SELECT EXISTS (
				    SELECT 1
				    FROM faq_embeddings fe
				    JOIN faq f
				      ON f.id = fe.faq_id
				    WHERE fe.faq_id = ?
				      AND fe.vector_type = 'QUESTION'
				      AND fe.faq_version = f.version
				      AND fe.vector IS NOT NULL
				)
				""",
				Boolean.class,
				result.resolvedFaqId()
		);

		assertThat(embeddingStored).isTrue();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT resolved_faq_id FROM unanswered_question_groups WHERE id = ?", Long.class, groupId))
				.isEqualTo(result.resolvedFaqId());
	}

	@Test
	@DisplayName("질문을 입력하면 그 질문으로 FAQ를 등록한다")
	void createsFaqWithCustomQuestion() {
		UnansweredGroupResponseDto result = service.createUnansweredGroupFaq(
				groupId, request("  해외 로밍 데이터가 안 될 때 어떻게 하나요?  "), adminId);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT question FROM faq WHERE id = ?", String.class, result.resolvedFaqId()))
				.isEqualTo("해외 로밍 데이터가 안 될 때 어떻게 하나요?");
	}

	@Test
	@DisplayName("이미 FAQ가 등록된 묶음은 다시 등록할 수 없다")
	void rejectsAlreadyResolvedGroup() {
		service.createUnansweredGroupFaq(groupId, request(null), adminId);

		assertThatThrownBy(() -> service.createUnansweredGroupFaq(groupId, request(null), adminId))
				.isInstanceOfSatisfying(UnansweredException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(UnansweredErrorCode.UNANSWERED_GROUP_ALREADY_RESOLVED));
		assertThat(countFaqs()).isEqualTo(1);
	}

	@Test
	@DisplayName("FAQ가 등록된 묶음은 상태를 바꿀 수 없다")
	void rejectsStatusUpdateOfResolvedGroup() {
		service.createUnansweredGroupFaq(groupId, request(null), adminId);

		assertThatThrownBy(() -> service.updateUnansweredGroupStatus(
				groupId, new UnansweredGroupStatusUpdateRequestDto(UnansweredGroupStatus.PENDING)))
				.isInstanceOfSatisfying(UnansweredException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(UnansweredErrorCode.UNANSWERED_GROUP_ALREADY_RESOLVED));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM unanswered_question_groups WHERE id = ?", String.class, groupId))
				.isEqualTo("APPROVED");
	}

	@Test
	@DisplayName("임베딩에 실패하면 FAQ 등록과 묶음 처리를 모두 취소한다")
	void rollsBackWhenEmbeddingFails() {
		given(embeddingService.embedText(anyString())).willThrow(new RuntimeException("embedding down"));

		assertThatThrownBy(() -> service.createUnansweredGroupFaq(groupId, request(null), adminId))
				.isInstanceOf(RuntimeException.class);
		assertThat(countFaqs()).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status || '|' || (resolved_faq_id IS NULL) FROM unanswered_question_groups WHERE id = ?",
				String.class, groupId))
				.isEqualTo("PENDING|true");
	}

	@Test
	@DisplayName("없는 묶음은 UQ-001을 반환한다")
	void failsWhenGroupMissing() {
		assertThatThrownBy(() -> service.createUnansweredGroupFaq(-1L, request(null), adminId))
				.isInstanceOfSatisfying(UnansweredException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(UnansweredErrorCode.UNANSWERED_GROUP_NOT_FOUND));
	}

	private UnansweredGroupFaqCreateRequestDto request(String question) {
		return new UnansweredGroupFaqCreateRequestDto(categoryId, question, "로밍 요금제를 확인해 주세요.", Intent.GENERAL);
	}

	private int countFaqs() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM faq WHERE category_id = ?", Integer.class, categoryId);
	}
}

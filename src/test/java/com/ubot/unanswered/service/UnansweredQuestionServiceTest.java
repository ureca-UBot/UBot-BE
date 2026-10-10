package com.ubot.unanswered.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.unanswered.entity.UnansweredQuestionGroup;
import com.ubot.unanswered.enums.UnansweredGroupStatus;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.repository.UnansweredQuestionGroupRepository;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;

@SpringBootTest
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("미응답 질문 저장 및 묶기 통합 테스트")
class UnansweredQuestionServiceTest {

    @Autowired
    private UnansweredQuestionService service;

    @Autowired
    private UnansweredQuestionRepository questionRepository;

    @Autowired
    private UnansweredQuestionGroupRepository groupRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private EmbeddingProfileService embeddingProfileService;

    private Long userId;
    private Long profileId;

    @BeforeEach
    void setUp() {
        questionRepository.deleteAll();
        groupRepository.deleteAll();

        profileId = getOrCreateProfile();

        given(embeddingProfileService.getCurrentProfileId())
                .willReturn(profileId);

        userId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (
                    email,
                    password_hash,
                    name
                )
                VALUES (?, 'hash', '테스터')
                RETURNING user_id
                """,
                Long.class,
                UUID.randomUUID() + "@test.com"
        );

        given(
                embeddingService.embedText(
                        "해외에서 데이터가 안 돼요"
                )
        ).willReturn(
                vector(1.0f, 0.0f)
        );

        given(
                embeddingService.embedText(
                        "로밍 중 인터넷이 안 돼요"
                )
        ).willReturn(
                vector(1.0f, 0.3f)
        );

        given(
                embeddingService.embedText(
                        "요금제 해지 위약금"
                )
        ).willReturn(
                vector(0.0f, 0.0f, 1.0f)
        );
    }

    @Test
    @DisplayName("비슷한 질문은 같은 프로필의 같은 묶음에 들어가고 중심 벡터가 갱신된다")
    void similarQuestionsJoinSameGroup() {
        service.createUnansweredQuestion(
                attempt("해외에서 데이터가 안 돼요"),
                "해외에서 데이터가 안 돼요",
                UnansweredReason.NO_FAQ,
                null,
                null
        );

        service.createUnansweredQuestion(
                attempt("로밍 중 인터넷이 안 돼요"),
                "로밍 중 인터넷이 안 돼요",
                UnansweredReason.INSUFFICIENT_FAQ,
                null,
                0.61
        );

        assertThat(groupRepository.count())
                .isEqualTo(1);

        UnansweredQuestionGroup group =
                groupRepository.findAll().getFirst();

        assertThat(group.getQuestionCount())
                .isEqualTo(2);

        assertThat(group.getRepresentativeQuestion())
                .isEqualTo("해외에서 데이터가 안 돼요");

        assertThat(group.getStatus())
                .isEqualTo(UnansweredGroupStatus.PENDING);

        String profileCentroid =
                jdbcTemplate.queryForObject(
                        """
                        SELECT centroid::text
                        FROM unanswered_group_embeddings
                        WHERE group_id = ?
                          AND profile_id = ?
                        """,
                        String.class,
                        group.getId(),
                        profileId
                );

        assertThat(profileCentroid)
                .startsWith("[1,0.15,0");

        Long embeddingCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM unanswered_question_embeddings
                        WHERE profile_id = ?
                        """,
                        Long.class,
                        profileId
                );

        assertThat(embeddingCount)
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("다른 질문은 새 묶음을 만든다")
    void differentQuestionCreatesNewGroup() {
        service.createUnansweredQuestion(
                attempt("해외에서 데이터가 안 돼요"),
                "해외에서 데이터가 안 돼요",
                UnansweredReason.NO_FAQ,
                null,
                null
        );

        service.createUnansweredQuestion(
                attempt("요금제 해지 위약금"),
                "요금제 해지 위약금",
                UnansweredReason.NO_FAQ,
                null,
                null
        );

        assertThat(groupRepository.count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("같은 답변 시도는 한 번만 저장한다")
    void sameAttemptIsSavedOnce() {
        Long attemptId =
                attempt("해외에서 데이터가 안 돼요");

        service.createUnansweredQuestion(
                attemptId,
                "해외에서 데이터가 안 돼요",
                UnansweredReason.NO_FAQ,
                null,
                null
        );

        service.createUnansweredQuestion(
                attemptId,
                "해외에서 데이터가 안 돼요",
                UnansweredReason.NO_FAQ,
                null,
                null
        );

        assertThat(questionRepository.count())
                .isEqualTo(1);

        Long embeddingCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM unanswered_question_embeddings
                        WHERE profile_id = ?
                        """,
                        Long.class,
                        profileId
                );

        assertThat(embeddingCount)
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("반려된 묶음과 비슷한 질문은 새 묶음을 만든다")
    void rejectedGroupIsNotJoined() {
        service.createUnansweredQuestion(
                attempt("해외에서 데이터가 안 돼요"),
                "해외에서 데이터가 안 돼요",
                UnansweredReason.NO_FAQ,
                null,
                null
        );

        jdbcTemplate.update(
                """
                UPDATE unanswered_question_groups
                SET status = 'REJECTED'
                """
        );

        service.createUnansweredQuestion(
                attempt("로밍 중 인터넷이 안 돼요"),
                "로밍 중 인터넷이 안 돼요",
                UnansweredReason.NO_FAQ,
                null,
                null
        );

        assertThat(groupRepository.count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("다른 임베딩 프로필의 묶음에는 합류하지 않는다")
    void doesNotJoinGroupFromAnotherProfile() {
        service.createUnansweredQuestion(
                attempt("해외에서 데이터가 안 돼요"),
                "해외에서 데이터가 안 돼요",
                UnansweredReason.NO_FAQ,
                null,
                null
        );

        Long otherProfileId = getOrCreateOtherProfile();

        given(embeddingProfileService.getCurrentProfileId())
                .willReturn(otherProfileId);

        service.createUnansweredQuestion(
                attempt("로밍 중 인터넷이 안 돼요"),
                "로밍 중 인터넷이 안 돼요",
                UnansweredReason.NO_FAQ,
                null,
                null
        );

        assertThat(groupRepository.count())
                .isEqualTo(2);
    }

    private Long attempt(String question) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO answer_attempts_history (
                    user_id,
                    question,
                    attempt_count,
                    status,
                    idempotency_key
                )
                VALUES (?, ?, 1, 'FAIL', ?)
                RETURNING attempt_id
                """,
                Long.class,
                userId,
                question,
                UUID.randomUUID().toString()
        );
    }

    private Long getOrCreateProfile() {
        return getOrCreateProfile(
                "ollama",
                "bge-m3:567m"
        );
    }

    private Long getOrCreateOtherProfile() {
        return getOrCreateProfile(
                "openai-compatible",
                "ubot-embedding"
        );
    }

    private Long getOrCreateProfile(
            String provider,
            String modelName
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO embedding_profiles (
                    provider,
                    model_name,
                    dimensions,
                    profile_version
                )
                VALUES (?, ?, 1024, 1)
                ON CONFLICT (
                    provider,
                    model_name,
                    dimensions,
                    profile_version
                )
                DO NOTHING
                """,
                provider,
                modelName
        );

        return jdbcTemplate.queryForObject(
                """
                SELECT profile_id
                FROM embedding_profiles
                WHERE provider = ?
                  AND model_name = ?
                  AND dimensions = 1024
                  AND profile_version = 1
                """,
                Long.class,
                provider,
                modelName
        );
    }

    private PGvector vector(float... head) {
        float[] values = new float[1024];

        System.arraycopy(
                head,
                0,
                values,
                0,
                head.length
        );

        return new PGvector(values);
    }
}
package com.ubot.faq.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;

@SpringBootTest
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("FAQ 벡터 저장소 통합 테스트")
class FaqVectorRepositoryTest {

    @Autowired
    private FaqVectorRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long categoryId;
    private Long profileId;
    private Long otherProfileId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM faq_embeddings");
        jdbcTemplate.update("DELETE FROM old_faq");
        jdbcTemplate.update("DELETE FROM faq");
        jdbcTemplate.update("DELETE FROM faq_category");

        categoryId = jdbcTemplate.queryForObject(
                """
                INSERT INTO faq_category (name)
                VALUES ('벡터 테스트')
                RETURNING id
                """,
                Long.class
        );

        profileId = getOrCreateProfile(
                "ollama",
                "bge-m3:567m"
        );

        otherProfileId = getOrCreateProfile(
                "openai-compatible",
                "ubot-embedding"
        );

        insertFaq(
                1L,
                "유심 재발급",
                "매장에서 재발급할 수 있습니다.",
                null
        );

        insertFaq(
                2L,
                "유심 인식 오류",
                "휴대전화를 다시 시작해 주세요.",
                null
        );

        insertFaq(
                3L,
                "삭제된 FAQ",
                "검색되면 안 됩니다.",
                "2026-01-01 00:00:00"
        );

        repository.saveVectorForFaq(
                1L,
                profileId,
                1,
                vectorOf(0)
        );

        repository.saveVectorForFaq(
                2L,
                profileId,
                1,
                vectorOf(1)
        );

        repository.saveVectorForFaq(
                3L,
                profileId,
                1,
                vectorOf(0)
        );
    }

    @Test
    @DisplayName("현재 프로필의 활성 FAQ만 가까운 순서로 검색한다")
    void getSimilarList_returnsNearestActiveFaqOnly() {
        List<FaqSearchResponseDto> results =
                repository.getSimilarList(
                        vectorOf(0),
                        profileId,
                        10
                );

        assertThat(results)
                .extracting(FaqSearchResponseDto::faqId)
                .containsExactly(1L, 2L);

        assertThat(results.getFirst().similarityScore())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("다른 임베딩 프로필의 벡터는 검색하지 않는다")
    void getSimilarList_doesNotUseAnotherProfile() {
        repository.saveVectorForFaq(
                1L,
                otherProfileId,
                1,
                vectorOf(5)
        );

        List<FaqSearchResponseDto> results =
                repository.getSimilarList(
                        vectorOf(0),
                        profileId,
                        10
                );

        assertThat(results)
                .extracting(FaqSearchResponseDto::faqId)
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("현재 프로필 벡터가 없으면 다른 프로필 벡터로 대체하지 않는다")
    void getSimilarList_doesNotFallbackToAnotherProfile() {
        jdbcTemplate.update(
                """
                DELETE FROM faq_embeddings
                WHERE faq_id = ?
                  AND profile_id = ?
                """,
                1L,
                profileId
        );

        repository.saveVectorForFaq(
                1L,
                otherProfileId,
                1,
                vectorOf(0)
        );

        List<FaqSearchResponseDto> results =
                repository.getSimilarList(
                        vectorOf(0),
                        profileId,
                        10
                );

        assertThat(results)
                .extracting(FaqSearchResponseDto::faqId)
                .containsExactly(2L);
    }

    @Test
    @DisplayName("FAQ 버전과 벡터 버전이 다르면 검색하지 않는다")
    void getSimilarList_ignoresStaleVector() {
        jdbcTemplate.update(
                """
                UPDATE faq
                SET version = version + 1
                WHERE id = ?
                """,
                1L
        );

        List<FaqSearchResponseDto> results =
                repository.getSimilarList(
                        vectorOf(0),
                        profileId,
                        10
                );

        assertThat(results)
                .extracting(FaqSearchResponseDto::faqId)
                .containsExactly(2L);
    }

    @Test
    @DisplayName("벡터를 다시 저장하면 지정한 FAQ 버전으로 갱신한다")
    void saveVectorForFaq_updatesFaqVersion() {
        jdbcTemplate.update(
                """
                UPDATE faq
                SET version = 2
                WHERE id = 1
                """
        );

        repository.saveVectorForFaq(
                1L,
                profileId,
                2,
                vectorOf(2)
        );

        Integer savedVersion = jdbcTemplate.queryForObject(
                """
                SELECT faq_version
                FROM faq_embeddings
                WHERE faq_id = ?
                  AND profile_id = ?
                  AND vector_type = 'QUESTION'
                """,
                Integer.class,
                1L,
                profileId
        );

        assertThat(savedVersion).isEqualTo(2);
    }

    @Test
    @DisplayName("질문이 바뀌지 않으면 벡터는 유지하고 FAQ 버전만 갱신한다")
    void updateVectorVersionForFaq_updatesOnlyVersion() {
        repository.updateVectorVersionForFaq(
                1L,
                profileId,
                2
        );

        Integer savedVersion = jdbcTemplate.queryForObject(
                """
                SELECT faq_version
                FROM faq_embeddings
                WHERE faq_id = ?
                  AND profile_id = ?
                  AND vector_type = 'QUESTION'
                """,
                Integer.class,
                1L,
                profileId
        );

        PGvector savedVector = jdbcTemplate.queryForObject(
                """
                SELECT vector
                FROM faq_embeddings
                WHERE faq_id = ?
                  AND profile_id = ?
                  AND vector_type = 'QUESTION'
                """,
                (rs, rowNum) -> new PGvector(rs.getString("vector")),
                1L,
                profileId
        );

        assertThat(savedVersion).isEqualTo(2);
        assertThat(savedVector).isNotNull();
        assertThat(savedVector.getValue()).startsWith("[1.0,0.0");
    }

    @Test
    @DisplayName("intent 검색은 현재 프로필의 해당 intent FAQ만 반환한다")
    void getSimilarListByIntent_returnsOnlyMatchingIntent() {
        jdbcTemplate.update(
                """
                UPDATE faq
                SET intent = 'STORE_DATA'
                WHERE id = 2
                """
        );

        List<FaqSearchResponseDto> results =
                repository.getSimilarListByIntent(
                        vectorOf(0),
                        profileId,
                        Intent.STORE_DATA,
                        10
                );

        assertThat(results)
                .extracting(FaqSearchResponseDto::faqId)
                .containsExactly(2L);

        assertThat(results)
                .extracting(FaqSearchResponseDto::intent)
                .containsOnly(Intent.STORE_DATA);
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

    @Test
    void semanticAliasesAreGroupedAndUnapprovedCompetitorsRemain() {
        jdbcTemplate.update("UPDATE faq SET cacheable = true WHERE id = 1");
        insertAlias(1L, profileId, "다른 표현 하나", vectorOf(0));
        insertAlias(1L, profileId, "다른 표현 둘", vectorOf(0));
        var candidates = repository.getSemanticCandidates(vectorOf(0), profileId);
        assertThat(candidates).extracting(c -> c.faq().faqId()).containsExactly(1L, 2L);
        assertThat(candidates.getFirst().registered()).isTrue();
        assertThat(candidates.get(1).registered()).isFalse();
        assertThat(candidates.get(1).faq().cacheable()).isFalse();
        assertThat(candidates.getFirst().faq().question()).isEqualTo("유심 재발급");
    }

    @Test
    void aliasesFromAnotherProfileNeverBecomeRegisteredCandidates() {
        jdbcTemplate.update("UPDATE faq SET cacheable = true WHERE id = 1");
        repository.saveVectorForFaq(1L, profileId, 1, vectorOf(1));
        repository.saveVectorForFaq(1L, otherProfileId, 1, vectorOf(0));
        insertAlias(1L, otherProfileId, "다른 프로필 표현", vectorOf(0));
        var candidate = repository.getSemanticCandidates(vectorOf(0), profileId).getFirst();
        assertThat(candidate.faq().faqId()).isEqualTo(1L);
        assertThat(candidate.registered()).isFalse();
        assertThat(candidate.faq().similarityScore()).isZero();
    }

    @Test
    void staleContentVersionRevocationAndPersonalIntentInvalidateAliases() {
        jdbcTemplate.update("UPDATE faq SET cacheable = true WHERE id = 1");
        insertAlias(1L, profileId, "검토한 표현", vectorOf(0));
        assertThat(repository.getSemanticCandidates(vectorOf(0), profileId).getFirst().registered()).isTrue();
        jdbcTemplate.update("UPDATE faq SET answer = '바뀐 답변' WHERE id = 1");
        assertThat(repository.getSemanticCandidates(vectorOf(0), profileId).getFirst().registered()).isFalse();
        jdbcTemplate.update("UPDATE faq SET answer = '매장에서 재발급할 수 있습니다.', version = 2 WHERE id = 1");
        repository.updateVectorVersionForFaq(1L, profileId, 2);
        assertThat(repository.getSemanticCandidates(vectorOf(0), profileId).getFirst().registered()).isFalse();
        jdbcTemplate.update("UPDATE faq SET version = 1, cacheable = false WHERE id = 1");
        repository.updateVectorVersionForFaq(1L, profileId, 1);
        assertThat(repository.getSemanticCandidates(vectorOf(0), profileId).getFirst().registered()).isFalse();
        jdbcTemplate.update("UPDATE faq SET cacheable = true, intent = 'USER_DATA' WHERE id = 1");
        assertThat(repository.getSemanticCandidates(vectorOf(0), profileId).getFirst().registered()).isFalse();
    }

    @Test
    void aliasCannotReplaceAMissingCurrentProfileCanonicalVector() {
        jdbcTemplate.update("UPDATE faq SET cacheable = true WHERE id = 1");
        insertAlias(1L, profileId, "검토한 표현", vectorOf(0));
        jdbcTemplate.update("DELETE FROM faq_embeddings WHERE faq_id = 1 AND profile_id = ?", profileId);
        assertThat(repository.getSemanticCandidates(vectorOf(0), profileId))
                .extracting(c -> c.faq().faqId()).containsExactly(2L);
    }

    private void insertAlias(Long faqId, Long aliasProfileId, String question, PGvector vector) {
        jdbcTemplate.update("""
                INSERT INTO faq_cache_question(faq_id, profile_id, question, vector, faq_version, faq_question, faq_answer)
                SELECT id, ?, ?, ?, version, question, answer FROM faq WHERE id = ?
                """, aliasProfileId, question, vector, faqId);
    }

    private void insertFaq(
            Long id,
            String question,
            String answer,
            String deletedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO faq (
                    id,
                    category_id,
                    question,
                    answer,
                    deleted_at,
                    created_at,
                    updated_at
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?::timestamp,
                    now(),
                    now()
                )
                """,
                id,
                categoryId,
                question,
                answer,
                deletedAt
        );
    }

    private PGvector vectorOf(int index) {
        float[] values = new float[1024];
        values[index] = 1.0f;

        return new PGvector(values);
    }
}

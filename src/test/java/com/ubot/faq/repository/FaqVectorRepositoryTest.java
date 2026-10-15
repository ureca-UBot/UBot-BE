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
import org.springframework.transaction.support.TransactionTemplate;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.FaqVectorType;
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

    @Autowired
    private TransactionTemplate transactionTemplate;

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

        repository.saveVectorForVariant(
                1L,
                profileId,
                FaqVectorType.QUESTION,
                1,
                vectorOf(0)
        );

        repository.saveVectorForVariant(
                2L,
                profileId,
                FaqVectorType.QUESTION,
                1,
                vectorOf(1)
        );

        repository.saveVectorForVariant(
                3L,
                profileId,
                FaqVectorType.QUESTION,
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
                        FaqVectorType.QUESTION,
                        10
                );

        assertThat(results)
                .extracting(FaqSearchResponseDto::faqId)
                .containsExactly(1L, 2L);

        assertThat(results.getFirst().similarityScore())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("같은 FAQ의 variant가 상위 점수를 차지해도 FAQ별 한 건을 고른 뒤 topK를 적용한다")
    void getSimilarList_selectsBestVariantPerFaqBeforeTopK() {
        repository.saveVectorForVariant(1L, profileId, FaqVectorType.QUESTION, 1, vectorOf(1));
        insertVariant(11L, 1L, "유심을 다시 발급받고 싶어요");
        insertVariant(12L, 1L, "유심 재발급 방법");
        repository.saveVectorForVariant(11L, profileId, FaqVectorType.QUESTION, 1, vectorOf(0));
        repository.saveVectorForVariant(12L, profileId, FaqVectorType.QUESTION, 1, vectorOf(0));

        List<FaqSearchResponseDto> results = repository.getSimilarList(
                vectorOf(0), profileId, FaqVectorType.QUESTION, 2);

        assertThat(results).extracting(FaqSearchResponseDto::faqId).containsExactly(1L, 2L);
        assertThat(results.getFirst().variantId()).isEqualTo(11L);
        assertThat(results.getFirst().question()).isEqualTo("유심 재발급");
        assertThat(results.getFirst().variantQuestion()).isEqualTo("유심을 다시 발급받고 싶어요");
    }

    @Test
    @DisplayName("삭제된 variant의 벡터도 함께 삭제되어 검색에 사용되지 않는다")
    void getSimilarList_ignoresDeletedVariant() {
        insertVariant(11L, 1L, "삭제된 표현");
        repository.saveVectorForVariant(11L, profileId, FaqVectorType.QUESTION, 1, vectorOf(0));
        jdbcTemplate.update("DELETE FROM faq_question_variants WHERE id = ?", 11L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM faq_embeddings WHERE faq_question_variant_id = ?",
                Integer.class, 11L)).isZero();

        List<FaqSearchResponseDto> results = repository.getSimilarList(
                vectorOf(0), profileId, FaqVectorType.QUESTION, 2);

        assertThat(results.getFirst().variantId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("다른 임베딩 프로필의 벡터는 검색하지 않는다")
    void getSimilarList_doesNotUseAnotherProfile() {
        repository.saveVectorForVariant(
                1L,
                otherProfileId,
                FaqVectorType.QUESTION,
                1,
                vectorOf(5)
        );

        List<FaqSearchResponseDto> results =
                repository.getSimilarList(
                        vectorOf(0),
                        profileId,
                        FaqVectorType.QUESTION,
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
                WHERE faq_question_variant_id = ?
                  AND profile_id = ?
                """,
                1L,
                profileId
        );

        repository.saveVectorForVariant(
                1L,
                otherProfileId,
                FaqVectorType.QUESTION,
                1,
                vectorOf(0)
        );

        List<FaqSearchResponseDto> results =
                repository.getSimilarList(
                        vectorOf(0),
                        profileId,
                        FaqVectorType.QUESTION,
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
                        FaqVectorType.QUESTION,
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

        repository.saveVectorForVariant(
                1L,
                profileId,
                FaqVectorType.QUESTION,
                2,
                vectorOf(2)
        );

        Integer savedVersion = jdbcTemplate.queryForObject(
                """
                SELECT faq_version
                FROM faq_embeddings
                WHERE faq_question_variant_id = ?
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
        repository.updateVectorVersionForVariant(
                1L,
                profileId,
                2
        );

        Integer savedVersion = jdbcTemplate.queryForObject(
                """
                SELECT faq_version
                FROM faq_embeddings
                WHERE faq_question_variant_id = ?
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
                WHERE faq_question_variant_id = ?
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
                        FaqVectorType.QUESTION,
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

    @Test
    @DisplayName("같은 FAQ와 프로필에 질문 벡터와 질문+답변 벡터를 각각 저장한다")
    void saveVectorForFaq_keepsOneVectorPerType() {
        repository.saveVectorForVariant(
                1L,
                profileId,
                FaqVectorType.QUESTION_ANSWER,
                1,
                vectorOf(3)
        );

        List<String> savedTypes = jdbcTemplate.queryForList(
                """
                SELECT vector_type
                FROM faq_embeddings
                WHERE faq_question_variant_id = ?
                  AND profile_id = ?
                ORDER BY vector_type
                """,
                String.class,
                1L,
                profileId
        );

        assertThat(savedTypes)
                .containsExactly("QUESTION", "QUESTION_ANSWER");

        assertThat(storedVector(1L, FaqVectorType.QUESTION))
                .startsWith("[1,0");

        assertThat(storedVector(1L, FaqVectorType.QUESTION_ANSWER))
                .startsWith("[0,0,0,1");
    }

    @Test
    @DisplayName("검색은 지정한 유형의 벡터만 쓰고, 그 유형의 벡터가 없는 FAQ는 다른 유형으로 대체하지 않는다")
    void getSimilarList_usesOnlyRequestedVectorType() {
        // FAQ 2에만 질문+답변 벡터가 있고, 그 벡터는 검색 벡터와 같다.
        repository.saveVectorForVariant(
                2L,
                profileId,
                FaqVectorType.QUESTION_ANSWER,
                1,
                vectorOf(0)
        );

        assertThat(
                repository.getSimilarList(
                        vectorOf(0),
                        profileId,
                        FaqVectorType.QUESTION,
                        10
                )
        )
                .extracting(FaqSearchResponseDto::faqId)
                .containsExactly(1L, 2L);

        assertThat(
                repository.getSimilarList(
                        vectorOf(0),
                        profileId,
                        FaqVectorType.QUESTION_ANSWER,
                        10
                )
        )
                .extracting(FaqSearchResponseDto::faqId)
                .containsExactly(2L);
    }

    @Test
    @DisplayName("벡터 버전 갱신은 현재 variant의 두 유형에 적용한다")
    void updateVectorVersionForVariant_updatesBothTypes() {
        repository.saveVectorForVariant(
                1L,
                profileId,
                FaqVectorType.QUESTION_ANSWER,
                1,
                vectorOf(3)
        );

        repository.updateVectorVersionForVariant(
                1L,
                profileId,
                2
        );

        assertThat(
                repository.hasCurrentVector(
                        1L,
                        profileId,
                        FaqVectorType.QUESTION,
                        2
                )
        ).isTrue();

        assertThat(
                repository.hasCurrentVector(
                        1L,
                        profileId,
                        FaqVectorType.QUESTION_ANSWER,
                        2
                )
        ).isTrue();

        assertThat(
                repository.hasCurrentVector(
                        1L,
                        profileId,
                        FaqVectorType.QUESTION_ANSWER,
                        1
                )
        ).isFalse();
    }

    @Test
    @DisplayName("다른 유형의 벡터가 더 가까이 많이 있어도 지정한 유형의 상위 K개를 모두 돌려준다")
    void getSimilarList_returnsExactTopKWhenOtherVectorTypeIsCloser() {
        // FAQ 100건의 질문 벡터는 검색 벡터와 거의 같고, 질문+답변 벡터는 그보다 멀다.
        // 벡터 인덱스는 가까운 후보를 먼저 고른 뒤 유형 조건을 적용하므로, 인덱스를 타면 질문+답변 벡터가 후보에 들지 못한다.
        for (int offset = 0; offset < 100; offset++) {
            long faqId = 101L + offset;

            insertFaq(
                    faqId,
                    "질문 " + faqId,
                    "답변 " + faqId,
                    null
            );

            repository.saveVectorForVariant(
                    faqId,
                    profileId,
                    FaqVectorType.QUESTION,
                    1,
                    vectorNear(10 + offset, 0.01f)
            );

            repository.saveVectorForVariant(
                    faqId,
                    profileId,
                    FaqVectorType.QUESTION_ANSWER,
                    1,
                    vectorNear(10 + offset, 0.5f + 0.01f * offset)
            );
        }

        // 테스트 DB는 행이 적어 그냥 두면 플래너가 벡터 인덱스를 고르지 않는다. 운영 DB처럼 인덱스로 정렬하려는 상태에서 확인한다.
        List<FaqSearchResponseDto> results =
                transactionTemplate.execute(status -> {
                    jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
                    jdbcTemplate.execute("SET LOCAL enable_sort = off");

                    return repository.getSimilarList(
                            vectorOf(0),
                            profileId,
                            FaqVectorType.QUESTION_ANSWER,
                            3
                    );
                });

        assertThat(results)
                .extracting(FaqSearchResponseDto::faqId)
                .containsExactly(101L, 102L, 103L);
    }

    @Test
    @DisplayName("검색 결과는 캐시 승인 여부와 버전을 함께 반환하고 미승인 FAQ도 순위에 포함한다")
    void searchReturnsCacheMetadataWithoutFilteringOutCompetitors() {
        jdbcTemplate.update("UPDATE faq SET cacheable = true WHERE id = 2");
        List<FaqSearchResponseDto> results = repository.getSimilarList(vectorOf(0), profileId, FaqVectorType.QUESTION, 3);
        assertThat(results).extracting(FaqSearchResponseDto::faqId).containsExactly(1L, 2L);
        assertThat(results.getFirst().cacheable()).isFalse();
        assertThat(results.getFirst().faqVersion()).isEqualTo(1);
        assertThat(results.get(1).cacheable()).isTrue();
    }

    @Test
    @DisplayName("내용이 같으면 승인 해제·버전 변경 후에도 현재 메타데이터로 재사용할 수 있다")
    void revalidationPreservesUnchangedContentWithCurrentMetadata() {
        jdbcTemplate.update("UPDATE faq SET cacheable = true WHERE id = 1");
        List<FaqSearchResponseDto> snapshot = repository.getSimilarList(vectorOf(0), profileId, FaqVectorType.QUESTION, 2);
        jdbcTemplate.update("UPDATE faq SET cacheable = false, version = 2 WHERE id = 1");

        List<FaqSearchResponseDto> current = repository.findUnchangedCandidates(snapshot);

        assertThat(current).extracting(FaqSearchResponseDto::faqId).containsExactly(1L, 2L);
        assertThat(current.getFirst().cacheable()).isFalse();
        assertThat(current.getFirst().faqVersion()).isEqualTo(2);
        assertThat(current.getFirst().answer()).isEqualTo(snapshot.getFirst().answer());
        assertThat(current.getFirst().similarityScore()).isEqualTo(snapshot.getFirst().similarityScore());
    }

    @Test
    @DisplayName("답변·의도·삭제 상태가 달라진 후보는 일괄 재검증에서 제외한다")
    void revalidationExcludesChangedAndDeletedContent() {
        List<FaqSearchResponseDto> snapshot = repository.getSimilarList(vectorOf(0), profileId, FaqVectorType.QUESTION, 2);
        jdbcTemplate.update("UPDATE faq SET answer = '변경된 답변', version = 2 WHERE id = 1");
        assertThat(repository.findUnchangedCandidates(snapshot)).extracting(FaqSearchResponseDto::faqId).containsExactly(2L);
        jdbcTemplate.update("UPDATE faq SET intent = 'USER_DATA' WHERE id = 2");
        assertThat(repository.findUnchangedCandidates(snapshot)).isEmpty();

        jdbcTemplate.update("UPDATE faq SET intent = 'GENERAL', deleted_at = now() WHERE id = 2");
        assertThat(repository.findUnchangedCandidates(snapshot)).isEmpty();
    }

    @Test
    @DisplayName("원본 질문·매칭 표현의 내용이나 소속이 달라지면 이전 유사도를 재사용하지 않는다")
    void revalidationExcludesChangedQuestionsAndVariantOwnership() {
        List<FaqSearchResponseDto> snapshot = repository.getSimilarList(vectorOf(0), profileId, FaqVectorType.QUESTION, 2);
        jdbcTemplate.update("UPDATE faq SET question = '변경된 원본 질문' WHERE id = 1");
        assertThat(repository.findUnchangedCandidates(snapshot)).extracting(FaqSearchResponseDto::faqId).containsExactly(2L);
        jdbcTemplate.update("UPDATE faq_question_variants SET question = '변경된 표현' WHERE id = 2");
        assertThat(repository.findUnchangedCandidates(snapshot)).isEmpty();

        jdbcTemplate.update("UPDATE faq SET question = ? WHERE id = 1", snapshot.getFirst().question());
        jdbcTemplate.update("UPDATE faq_question_variants SET origin_faq_id = 2 WHERE id = 1");
        assertThat(repository.findUnchangedCandidates(snapshot)).isEmpty();
        jdbcTemplate.update("DELETE FROM faq_question_variants WHERE id = 1");
        assertThat(repository.findUnchangedCandidates(snapshot)).isEmpty();
    }

    @Test
    @DisplayName("재검증할 후보가 없으면 빈 결과를 반환한다")
    void revalidationAcceptsEmptyCandidates() {
        assertThat(repository.findUnchangedCandidates(List.of())).isEmpty();
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
        jdbcTemplate.update(
                """
                INSERT INTO faq_question_variants (id, origin_faq_id, question)
                VALUES (?, ?, ?)
                """,
                id, id, question
        );
    }

    private void insertVariant(Long id, Long faqId, String question) {
        jdbcTemplate.update(
                """
                INSERT INTO faq_question_variants (id, origin_faq_id, question)
                VALUES (?, ?, ?)
                """,
                id, faqId, question
        );
    }

    private String storedVector(
            Long faqId,
            FaqVectorType vectorType
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT vector::text
                FROM faq_embeddings
                WHERE faq_question_variant_id = ?
                  AND profile_id = ?
                  AND vector_type = ?
                """,
                String.class,
                faqId,
                profileId,
                vectorType.name()
        );
    }

    /** 0번 축 방향에서 index번 축으로 distance만큼 벗어난 벡터입니다. distance가 클수록 vectorOf(0)에서 멀어집니다. */
    private PGvector vectorNear(
            int index,
            float distance
    ) {
        float[] values = new float[1024];
        values[0] = 1.0f;
        values[index] = distance;

        return new PGvector(values);
    }

    private PGvector vectorOf(int index) {
        float[] values = new float[1024];
        values[index] = 1.0f;

        return new PGvector(values);
    }
}

package com.ubot.faq.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqQuestionVariant;
import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.enums.FaqVectorType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PgvectorTestConfiguration.class)
@DisplayName("FAQ 변형·임베딩 저장 구조 통합 테스트")
class FaqQuestionVariantRepositoryTest {
    @Autowired
    private FaqQuestionVariantRepository variantRepository;
    @Autowired
    private FaqRepository faqRepository;
    @Autowired
    private FaqVectorRepository vectorRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long originFaqId;
    private Long otherOriginFaqId;
    private Long profileId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM faq_embeddings");
        jdbcTemplate.update("DELETE FROM old_faq");
        jdbcTemplate.update("DELETE FROM faq");
        jdbcTemplate.update("DELETE FROM faq_category");
        Long categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO faq_category(name) VALUES ('변형 테스트') RETURNING id", Long.class);
        originFaqId = insertFaq(categoryId, "eSIM이 무엇인가요?", "디지털 SIM입니다.");
        otherOriginFaqId = insertFaq(categoryId, "다른 질문", "다른 답변");
        jdbcTemplate.update("""
                INSERT INTO embedding_profiles(provider, model_name, dimensions, profile_version)
                VALUES ('ollama', 'variant-test', 1024, 1) ON CONFLICT DO NOTHING
                """);
        profileId = jdbcTemplate.queryForObject("""
                SELECT profile_id FROM embedding_profiles
                WHERE provider = 'ollama' AND model_name = 'variant-test' AND profile_version = 1
                """, Long.class);
    }

    @Test
    @DisplayName("캐시 미승인 FAQ에도 질문만 저장하고 벡터는 별도 테이블에 둔다")
    void savesQuestionAndVectorSeparately() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        saveVariantVector(variant, vectorOf(1));

        assertThat(faqRepository.findById(originFaqId).orElseThrow().isCacheable()).isFalse();
        assertThat(variantRepository.findActiveByOriginFaqIdAndQuestion(originFaqId, "이심이 뭐야?"))
                .isPresent();
        assertThat(variant.getCreatedAt()).isNotNull();
        assertThat(variant.getUpdatedAt()).isNotNull();
        assertThat(variant.getDeletedAt()).isNull();
        assertThat(vectorRepository.hasCurrentVariantVector(variant.getId(), profileId)).isTrue();
    }

    @Test
    @DisplayName("원본과 여러 변형의 벡터가 공존하고 원본 버전 갱신이 변형에 영향을 주지 않는다")
    void originalAndMultipleVariantVectorsCoexist() {
        FaqQuestionVariant firstVariant = createVariant(originFaqId, "이심이 뭐야?");
        FaqQuestionVariant secondVariant = createVariant(originFaqId, "eSIM 설명해줘");
        vectorRepository.saveVectorForFaq(originFaqId, profileId, FaqVectorType.QUESTION, 1, vectorOf(0));
        saveVariantVector(firstVariant, vectorOf(1));
        saveVariantVector(secondVariant, vectorOf(2));
        saveVariantVector(firstVariant, vectorOf(3));
        vectorRepository.saveVectorForFaq(originFaqId, profileId, FaqVectorType.QUESTION, 2, vectorOf(4));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM faq_embeddings", Integer.class))
                .isEqualTo(3);
        assertThat(vectorRepository.hasCurrentVector(originFaqId, profileId, FaqVectorType.QUESTION, 2))
                .isTrue();
        vectorRepository.updateVectorVersionForFaq(originFaqId, profileId, FaqVectorType.QUESTION, 3);
        assertThat(vectorRepository.hasCurrentVariantVector(firstVariant.getId(), profileId)).isTrue();
        assertThat(vectorRepository.hasCurrentVariantVector(secondVariant.getId(), profileId)).isTrue();
    }

    @Test
    @DisplayName("질문 수정 후 이전 벡터를 무효로 판단하고 늦게 도착한 이전 임베딩을 저장하지 않는다")
    void rejectsEmbeddingForAnOlderQuestion() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        LocalDateTime previousUpdatedAt = variant.getUpdatedAt();
        saveVariantVector(variant, vectorOf(1));

        variant.update("eSIM이 어떤 건가요?");
        variant = variantRepository.saveAndFlush(variant);
        assertThat(variant.getUpdatedAt()).isAfter(previousUpdatedAt);
        assertThat(vectorRepository.hasCurrentVariantVector(variant.getId(), profileId)).isFalse();
        saveVariantVector(variant, vectorOf(2));
        assertThat(vectorRepository.saveVectorForVariant(
                variant.getId(), profileId, previousUpdatedAt, vectorOf(3))).isFalse();
        assertThat(vectorRepository.hasCurrentVariantVector(variant.getId(), profileId)).isTrue();
        PGvector savedVector = jdbcTemplate.queryForObject(
                "SELECT vector FROM faq_embeddings WHERE variant_id = ? AND profile_id = ?",
                (rows, rowNumber) -> new PGvector(rows.getString("vector")), variant.getId(), profileId);
        assertThat(savedVector.toArray()).containsExactly(vectorOf(2).toArray());
    }

    @Test
    @DisplayName("같은 질문을 저장해도 수정 시각이 변하지 않아 기존 벡터를 유지한다")
    void unchangedQuestionKeepsItsEmbedding() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        LocalDateTime previousUpdatedAt = variant.getUpdatedAt();
        saveVariantVector(variant, vectorOf(1));
        variant.update("이심이 뭐야?");
        variantRepository.saveAndFlush(variant);

        assertThat(variant.getUpdatedAt()).isEqualTo(previousUpdatedAt);
        assertThat(vectorRepository.hasCurrentVariantVector(variant.getId(), profileId)).isTrue();
    }

    @Test
    @DisplayName("기존 검색은 원본 벡터만 사용한다")
    void existingRetrievalUsesOriginalVectorsOnly() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        vectorRepository.saveVectorForFaq(originFaqId, profileId, FaqVectorType.QUESTION, 1, vectorOf(0));
        saveVariantVector(variant, vectorOf(1));
        List<FaqSearchResponseDto> result = vectorRepository.getSimilarList(
                vectorOf(1), profileId, FaqVectorType.QUESTION, 10);

        assertThat(result).extracting(FaqSearchResponseDto::faqId).containsExactly(originFaqId);
        assertThat(result.getFirst().similarityScore()).isZero();
    }

    @Test
    @DisplayName("원본과 변형 동시 지정·미지정 및 없는 변형의 연결을 거절한다")
    void rejectsInvalidVectorOwners() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO faq_embeddings(profile_id, vector_type, source_version, vector)
                VALUES (?, 'QUESTION', 1, ?::vector)
                """, profileId, vectorOf(0).toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO faq_embeddings(faq_id, variant_id, profile_id, vector_type, source_version, vector)
                VALUES (?, ?, ?, 'QUESTION', 1, ?::vector)
                """, originFaqId, variant.getId(), profileId, vectorOf(0).toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO faq_embeddings(variant_id, profile_id, vector_type, source_updated_at, vector)
                VALUES (-1, ?, 'QUESTION', CURRENT_TIMESTAMP, ?::vector)
                """, profileId, vectorOf(0).toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("질문만 있는 변형의 QUESTION_ANSWER 벡터는 DB가 거절한다")
    void rejectsQuestionAnswerVectorsForVariants() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO faq_embeddings(variant_id, profile_id, vector_type, source_updated_at, vector)
                VALUES (?, ?, 'QUESTION_ANSWER', ?, ?::vector)
                """, variant.getId(), profileId, variant.getUpdatedAt(), vectorOf(0).toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("활성 표현은 원본별로 중복을 막고 삭제된 표현은 다시 등록할 수 있다")
    void uniquenessAppliesToActiveQuestionsOnly() {
        FaqQuestionVariant firstVariant = createVariant(originFaqId, "이심이 뭐야?");
        createVariant(otherOriginFaqId, "이심이 뭐야?");
        assertThatThrownBy(() -> createVariant(originFaqId, "이심이 뭐야?"))
                .isInstanceOf(DataIntegrityViolationException.class);
        firstVariant.delete();
        variantRepository.saveAndFlush(firstVariant);
        FaqQuestionVariant replacement = createVariant(originFaqId, "이심이 뭐야?");

        assertThat(replacement.getId()).isNotEqualTo(firstVariant.getId());
        assertThat(variantRepository.findAllActiveByOriginFaqId(originFaqId))
                .extracting(FaqQuestionVariant::getId).containsExactly(replacement.getId());
    }

    @Test
    @DisplayName("표현 soft delete 후 벡터가 있어도 사용·재저장을 막는다")
    void softDeletedVariantIsNotUsable() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        saveVariantVector(variant, vectorOf(1));
        variant.delete();
        variantRepository.saveAndFlush(variant);

        assertThat(variantRepository.findAllActiveByOriginFaqId(originFaqId)).isEmpty();
        assertThat(vectorRepository.hasCurrentVariantVector(variant.getId(), profileId)).isFalse();
        assertThat(vectorRepository.saveVectorForVariant(
                variant.getId(), profileId, variant.getUpdatedAt(), vectorOf(2))).isFalse();
    }

    @Test
    @DisplayName("원본 FAQ soft delete 후 변형 조회와 벡터 사용을 막는다")
    void softDeletedOriginIsNotUsable() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        saveVariantVector(variant, vectorOf(1));
        jdbcTemplate.update("UPDATE faq SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", originFaqId);

        assertThat(variantRepository.findAllActiveByOriginFaqId(originFaqId)).isEmpty();
        assertThat(vectorRepository.hasCurrentVariantVector(variant.getId(), profileId)).isFalse();
        assertThat(vectorRepository.saveVectorForVariant(
                variant.getId(), profileId, variant.getUpdatedAt(), vectorOf(2))).isFalse();
    }

    @Test
    @DisplayName("변형 물리 삭제는 해당 벡터만 삭제한다")
    void deletingVariantPreservesOriginalVector() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        vectorRepository.saveVectorForFaq(originFaqId, profileId, FaqVectorType.QUESTION, 1, vectorOf(0));
        saveVariantVector(variant, vectorOf(1));
        variantRepository.deleteById(variant.getId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM faq_embeddings WHERE variant_id IS NOT NULL", Integer.class)).isZero();
        assertThat(vectorRepository.hasCurrentVector(originFaqId, profileId, FaqVectorType.QUESTION, 1)).isTrue();
    }

    @Test
    @DisplayName("원본 FAQ 물리 삭제는 연결된 변형과 벡터를 함께 삭제한다")
    void deletingOriginCascadesToVariantsAndVectors() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        vectorRepository.saveVectorForFaq(originFaqId, profileId, FaqVectorType.QUESTION, 1, vectorOf(0));
        saveVariantVector(variant, vectorOf(1));
        jdbcTemplate.update("DELETE FROM faq WHERE id = ?", originFaqId);

        assertThat(variantRepository.findAllActiveByOriginFaqId(originFaqId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM faq_embeddings", Integer.class)).isZero();
    }

    @Test
    @DisplayName("FAQ 수정 이력에 캐시 승인 상태를 보관한다")
    void approvalIsCopiedIntoHistory() {
        jdbcTemplate.update("UPDATE faq SET cacheable = true WHERE id = ?", originFaqId);
        Faq faq = faqRepository.findById(originFaqId).orElseThrow();
        assertThat(OldFaq.from(faq, null).isCacheable()).isTrue();
    }

    @Test
    @DisplayName("한글 변형 질문은 160자까지 저장하고 161자는 거절한다")
    void enforcesQuestionLengthBoundary() {
        String maximumLengthQuestion = "가".repeat(160);
        FaqQuestionVariant saved = createVariant(originFaqId, maximumLengthQuestion);
        assertThat(variantRepository.findById(saved.getId()).orElseThrow().getQuestion())
                .isEqualTo(maximumLengthQuestion);
        assertThatThrownBy(() -> createVariant(originFaqId, "가".repeat(161)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long insertFaq(Long categoryId, String question, String answer) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO faq(category_id, question, answer) VALUES (?, ?, ?) RETURNING id
                """, Long.class, categoryId, question, answer);
    }

    private FaqQuestionVariant createVariant(Long originFaqId, String question) {
        return variantRepository.saveAndFlush(FaqQuestionVariant.builder()
                .originFaq(faqRepository.getReferenceById(originFaqId)).question(question).build());
    }

    private void saveVariantVector(FaqQuestionVariant variant, PGvector vector) {
        assertThat(vectorRepository.saveVectorForVariant(
                variant.getId(), profileId, variant.getUpdatedAt(), vector)).isTrue();
    }

    private PGvector vectorOf(int index) {
        float[] values = new float[1024];
        values[index] = 1.0f;
        return new PGvector(values);
    }
}

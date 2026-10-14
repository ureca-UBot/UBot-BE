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
                "INSERT INTO faq_category(name) VALUES ('변형 테스트') RETURNING id",
                Long.class
        );
        originFaqId = jdbcTemplate.queryForObject(
                """
                INSERT INTO faq(category_id, question, answer)
                VALUES (?, 'eSIM이 무엇인가요?', '디지털 SIM입니다.') RETURNING id
                """,
                Long.class,
                categoryId
        );
        otherOriginFaqId = jdbcTemplate.queryForObject(
                """
                INSERT INTO faq(category_id, question, answer)
                VALUES (?, '다른 질문', '다른 답변') RETURNING id
                """,
                Long.class,
                categoryId
        );
        jdbcTemplate.update("""
                INSERT INTO embedding_profiles(provider, model_name, dimensions, profile_version)
                VALUES ('ollama', 'variant-test', 1024, 1) ON CONFLICT DO NOTHING
                """);
        profileId = jdbcTemplate.queryForObject(
                """
                SELECT profile_id FROM embedding_profiles
                WHERE provider = 'ollama' AND model_name = 'variant-test' AND profile_version = 1
                """,
                Long.class
        );
    }

    @Test
    @DisplayName("캐시 미승인 FAQ에도 변형을 저장하며 벡터는 별도 테이블에 둔다")
    void savesVariantAndVectorSeparately() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        vectorRepository.saveVectorForVariant(
                variant.getId(),
                profileId,
                FaqVectorType.QUESTION,
                1,
                vectorOf(1)
        );
        assertThat(faqRepository.findById(originFaqId).orElseThrow().isCacheable()).isFalse();
        assertThat(variantRepository.findByOriginFaq_IdAndQuestion(originFaqId, "이심이 뭐야?")).isPresent();
        assertThat(variant.getCreatedAt()).isNotNull();
        assertThat(
                vectorRepository.hasCurrentVariantVector(variant.getId(), profileId, FaqVectorType.QUESTION, 1)
        ).isTrue();
        Integer removedColumnCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'faq_question_variant'
                  AND column_name IN ('vector', 'normalized_question', 'enabled')
                """,
                Integer.class
        );
        assertThat(removedColumnCount).isZero();
    }

    @Test
    @DisplayName("같은 FAQ의 여러 변형·벡터 유형을 저장하고 재저장 시 해당 벡터만 갱신한다")
    void originalAndMultipleVariantVectorsCoexist() {
        FaqQuestionVariant firstVariant = createVariant(originFaqId, "이심이 뭐야?");
        FaqQuestionVariant secondVariant = createVariant(originFaqId, "eSIM 설명해줘");
        vectorRepository.saveVectorForFaq(originFaqId, profileId, FaqVectorType.QUESTION, 1, vectorOf(0));
        vectorRepository.saveVectorForVariant(
                firstVariant.getId(),
                profileId,
                FaqVectorType.QUESTION,
                1,
                vectorOf(1)
        );
        vectorRepository.saveVectorForVariant(
                secondVariant.getId(),
                profileId,
                FaqVectorType.QUESTION,
                1,
                vectorOf(2)
        );
        vectorRepository.saveVectorForVariant(
                firstVariant.getId(),
                profileId,
                FaqVectorType.QUESTION_ANSWER,
                1,
                vectorOf(3)
        );
        vectorRepository.saveVectorForVariant(
                firstVariant.getId(),
                profileId,
                FaqVectorType.QUESTION,
                2,
                vectorOf(4)
        );
        vectorRepository.saveVectorForFaq(originFaqId, profileId, FaqVectorType.QUESTION, 2, vectorOf(5));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM faq_embeddings", Integer.class)).isEqualTo(4);
        assertThat(
                vectorRepository.hasCurrentVariantVector(firstVariant.getId(), profileId, FaqVectorType.QUESTION, 1)
        ).isFalse();
        assertThat(
                vectorRepository.hasCurrentVariantVector(firstVariant.getId(), profileId, FaqVectorType.QUESTION, 2)
        ).isTrue();
        assertThat(vectorRepository.hasCurrentVector(originFaqId, profileId, FaqVectorType.QUESTION, 2)).isTrue();
        vectorRepository.updateVectorVersionForFaq(originFaqId, profileId, FaqVectorType.QUESTION, 3);
        assertThat(
                vectorRepository.hasCurrentVariantVector(secondVariant.getId(), profileId, FaqVectorType.QUESTION, 1)
        ).isTrue();
    }

    @Test
    @DisplayName("변형 저장 구조 추가 후에도 기존 검색은 원본 벡터만 사용한다")
    void existingRetrievalUsesOriginalVectorsOnly() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        vectorRepository.saveVectorForFaq(originFaqId, profileId, FaqVectorType.QUESTION, 1, vectorOf(0));
        vectorRepository.saveVectorForVariant(
                variant.getId(),
                profileId,
                FaqVectorType.QUESTION,
                1,
                vectorOf(1)
        );
        List<FaqSearchResponseDto> result = vectorRepository.getSimilarList(
                vectorOf(1), profileId, FaqVectorType.QUESTION, 10
        );
        assertThat(result).extracting(FaqSearchResponseDto::faqId).containsExactly(originFaqId);
        assertThat(result.getFirst().similarityScore()).isZero();
    }

    @Test
    @DisplayName("원본과 변형 동시 지정 또는 미지정 벡터를 DB가 거절한다")
    void rejectsInvalidVectorOwners() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO faq_embeddings(profile_id, vector_type, source_version, vector)
                VALUES (?, 'QUESTION', 1, ?::vector)
                """,
                profileId,
                vectorOf(0).toString()
        ))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO faq_embeddings(faq_id, variant_id, profile_id, vector_type, source_version, vector)
                VALUES (?, ?, ?, 'QUESTION', 1, ?::vector)
                """,
                originFaqId,
                variant.getId(),
                profileId,
                vectorOf(0).toString()
        ))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> vectorRepository.saveVectorForVariant(
                -1L, profileId, FaqVectorType.QUESTION, 1, vectorOf(0)
        ))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 원본의 중복 표현을 막고 다른 원본에는 같은 표현을 허용한다")
    void enforcesQuestionUniquenessPerOrigin() {
        createVariant(originFaqId, "이심이 뭐야?");
        createVariant(otherOriginFaqId, "이심이 뭐야?");
        assertThatThrownBy(() -> createVariant(originFaqId, "이심이 뭐야?"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(variantRepository.findAllByOriginFaq_IdOrderByIdAsc(originFaqId)).hasSize(1);
    }

    @Test
    @DisplayName("변형 삭제는 연결된 변형 벡터만 삭제한다")
    void deletingVariantPreservesOriginalVector() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        vectorRepository.saveVectorForFaq(originFaqId, profileId, FaqVectorType.QUESTION, 1, vectorOf(0));
        vectorRepository.saveVectorForVariant(
                variant.getId(),
                profileId,
                FaqVectorType.QUESTION,
                1,
                vectorOf(1)
        );
        variantRepository.deleteById(variant.getId());
        Integer remainingVariantVectorCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM faq_embeddings WHERE variant_id IS NOT NULL",
                Integer.class
        );
        assertThat(remainingVariantVectorCount).isZero();
        assertThat(vectorRepository.hasCurrentVector(originFaqId, profileId, FaqVectorType.QUESTION, 1)).isTrue();
    }

    @Test
    @DisplayName("원본 FAQ 물리 삭제는 변형과 양쪽 벡터를 함께 삭제한다")
    void deletingOriginCascadesToVariantsAndVectors() {
        FaqQuestionVariant variant = createVariant(originFaqId, "이심이 뭐야?");
        vectorRepository.saveVectorForFaq(originFaqId, profileId, FaqVectorType.QUESTION, 1, vectorOf(0));
        vectorRepository.saveVectorForVariant(
                variant.getId(),
                profileId,
                FaqVectorType.QUESTION,
                1,
                vectorOf(1)
        );
        jdbcTemplate.update("DELETE FROM faq WHERE id=?", originFaqId);
        assertThat(variantRepository.findAllByOriginFaq_IdOrderByIdAsc(originFaqId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM faq_embeddings", Integer.class)).isZero();
    }

    @Test
    @DisplayName("FAQ 수정 이력에 캐시 승인 상태를 보관한다")
    void approvalIsCopiedIntoHistory() {
        jdbcTemplate.update("UPDATE faq SET cacheable=true WHERE id=?", originFaqId);
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

    private FaqQuestionVariant createVariant(Long originFaqId, String question) {
        return variantRepository.saveAndFlush(
                FaqQuestionVariant.builder()
                        .originFaq(faqRepository.getReferenceById(originFaqId))
                        .question(question)
                        .answer("디지털 SIM입니다.")
                        .build()
        );
    }

    private PGvector vectorOf(int index) {
        float[] values = new float[1024];
        values[index] = 1.0f;
        return new PGvector(values);
    }
}

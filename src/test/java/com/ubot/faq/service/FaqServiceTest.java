package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.ubot.common.PageResponseDto;
import com.ubot.faq.dto.request.FaqCreateRequestDto;
import com.ubot.faq.dto.request.FaqRestoreRequestDto;
import com.ubot.faq.dto.request.FaqUpdateRequestDto;
import com.ubot.faq.dto.response.FaqResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.entity.FaqQuestionVariant;
import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.repository.FaqCategoryRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.repository.FaqQuestionVariantRepository;
import com.ubot.faq.repository.OldFaqRepository;
import com.ubot.user.entity.User;
import com.ubot.user.exception.UserException;
import com.ubot.user.repository.UserRepository;

@DisplayName("FAQ 서비스 테스트")
class FaqServiceTest {

    private final FaqRepository faqRepository =
            mock(FaqRepository.class);

    private final FaqVectorService vectorService =
            mock(FaqVectorService.class);

    private final FaqQuestionVariantRepository variantRepository =
            mock(FaqQuestionVariantRepository.class);

    private final FaqCategoryRepository categoryRepository =
            mock(FaqCategoryRepository.class);

    private final OldFaqRepository oldFaqRepository =
            mock(OldFaqRepository.class);

    private final UserRepository userRepository =
            mock(UserRepository.class);

    private final FaqService service =
            new FaqService(
                    faqRepository,
                    vectorService,
                    variantRepository,
                    categoryRepository,
                    oldFaqRepository,
                    userRepository
            );

    @Test
    @DisplayName("관리자와 활성 카테고리가 있으면 FAQ를 저장하고 현재 버전의 벡터를 질문과 답변으로 생성한다")
    void createFaq_savesFaqAndVector() {
        var request = new FaqCreateRequestDto(
                2L,
                "요금제가 궁금해요",
                "요금제 안내입니다.",
                Intent.GENERAL
        );

        when(userRepository.findById(1L))
                .thenReturn(
                        Optional.of(
                                User.builder()
                                        .id(1L)
                                        .build()
                        )
                );

        when(categoryRepository.findByIdAndDeletedAtIsNull(2L))
                .thenReturn(
                        Optional.of(
                                category(2L, "요금제")
                        )
                );

        when(faqRepository.save(any(Faq.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(variantRepository.save(any(FaqQuestionVariant.class)))
                .thenAnswer(invocation -> FaqQuestionVariant.builder()
                        .id(100L)
                        .originFaq(invocation.getArgument(0, FaqQuestionVariant.class).getOriginFaq())
                        .question(invocation.getArgument(0, FaqQuestionVariant.class).getQuestion())
                        .build());

        FaqResponseDto result =
                service.createFaq(
                        request,
                        1L
                );

        assertThat(result.question())
                .isEqualTo(request.question());

        assertThat(result.version())
                .isEqualTo(1);

        verify(vectorService)
                .saveVectorsForVariant(
                        100L,
                        1,
                        request.question(),
                        request.answer()
                );

        verify(faqRepository, times(1))
                .save(any(Faq.class));
    }

    @Test
    @DisplayName("FAQ 생성 시 원본 질문과 요청한 유사 질문을 각각 variant와 벡터로 저장한다")
    void createFaq_savesOriginalAndSimilarVariants() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(categoryRepository.findByIdAndDeletedAtIsNull(2L))
                .thenReturn(Optional.of(category(2L, "요금제")));
        when(faqRepository.save(any(Faq.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicLong nextId = new AtomicLong(100L);
        when(variantRepository.save(any(FaqQuestionVariant.class))).thenAnswer(invocation -> {
            FaqQuestionVariant value = invocation.getArgument(0);
            return variant(nextId.getAndIncrement(), value.getOriginFaq(), value.getQuestion());
        });

        FaqResponseDto result = service.createFaq(new FaqCreateRequestDto(
                2L, "원본 질문", "답변", Intent.GENERAL, true, List.of("유사 질문 1", "유사 질문 2")), 1L);

        assertThat(result.cacheable()).isTrue();
        assertThat(result.similarQuestions()).containsExactly("유사 질문 1", "유사 질문 2");
        verify(vectorService).saveVectorsForVariant(100L, 1, "원본 질문", "답변");
        verify(vectorService).saveVectorsForVariant(101L, 1, "유사 질문 1", "답변");
        verify(vectorService).saveVectorsForVariant(102L, 1, "유사 질문 2", "답변");
    }

    @Test
    @DisplayName("존재하지 않는 관리자로 FAQ를 생성하면 사용자 없음 예외가 발생한다")
    void createFaq_throwsWhenAdminMissing() {
        when(userRepository.findById(1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> service.createFaq(
                        new FaqCreateRequestDto(
                                2L,
                                "질문",
                                "답변",
                                Intent.GENERAL
                        ),
                        1L
                )
        ).isInstanceOf(UserException.class);

        verifyNoInteractions(
                categoryRepository,
                faqRepository,
                vectorService
        );
    }

    @Test
    @DisplayName("존재하지 않는 카테고리로 FAQ를 생성하면 카테고리 없음 예외가 발생한다")
    void createFaq_throwsWhenCategoryMissing() {
        when(userRepository.findById(1L))
                .thenReturn(
                        Optional.of(
                                User.builder()
                                        .id(1L)
                                        .build()
                        )
                );

        when(categoryRepository.findByIdAndDeletedAtIsNull(2L))
                .thenReturn(Optional.empty());

        assertFaqError(
                () -> service.createFaq(
                        new FaqCreateRequestDto(
                                2L,
                                "질문",
                                "답변",
                                Intent.GENERAL
                        ),
                        1L
                ),
                FaqErrorCode.FAQ_CATEGORY_NOT_FOUND
        );
    }

    @Test
    @DisplayName("질문과 답변이 바뀐 FAQ는 이전 내용을 이력에 저장하고 두 변경을 벡터 서비스에 알린다")
    void updateFaq_copiesHistoryAndRegeneratesVector() {
        Faq faq = faq(
                10L,
                "기존 질문",
                3,
                category(2L, "기존")
        );

        when(faqRepository.findActiveById(10L))
                .thenReturn(Optional.of(faq));

        when(userRepository.findById(9L))
                .thenReturn(
                        Optional.of(
                                User.builder()
                                        .id(9L)
                                        .build()
                        )
                );

        when(categoryRepository.findByIdAndDeletedAtIsNull(3L))
                .thenReturn(
                        Optional.of(
                                category(3L, "변경")
                        )
                );

        when(faqRepository.save(faq))
                .thenReturn(faq);

        FaqQuestionVariant oldVariant = variant(100L, faq, "기존 질문");
        when(variantRepository.findByOriginFaqIdAndDeletedAtIsNullOrderByIdAsc(10L))
                .thenReturn(List.of(oldVariant));
        when(variantRepository.save(any(FaqQuestionVariant.class)))
                .thenAnswer(invocation -> variant(101L, faq,
                        invocation.getArgument(0, FaqQuestionVariant.class).getQuestion()));

        FaqResponseDto result =
                service.updateActiveFaq(
                        new FaqUpdateRequestDto(
                                10L,
                                3L,
                                "새 질문",
                                "새 답변",
                                Intent.GENERAL
                        ),
                        9L
                );

        ArgumentCaptor<OldFaq> history =
                ArgumentCaptor.forClass(OldFaq.class);

        verify(oldFaqRepository)
                .saveAndFlush(history.capture());

        assertThat(history.getValue().getQuestion())
                .isEqualTo("기존 질문");

        assertThat(oldVariant.getDeletedAt()).isNotNull();
        verify(vectorService).deleteVectorsForVariant(100L);
        verify(vectorService)
                .saveVectorsForVariant(
                        101L,
                        4,
                        "새 질문",
                        "새 답변"
                );

        assertThat(result.version())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("카테고리와 intent만 바뀌면 질문과 답변이 그대로라고 벡터 서비스에 알린다")
    void updateFaq_reportsNoTextChangeWhenOnlyCategoryAndIntentChange() {
        Faq faq = faq(
                10L,
                "같은 질문",
                1,
                category(3L, "기존")
        );

        givenUpdatableFaq(faq);

        when(variantRepository.findByOriginFaqIdAndDeletedAtIsNullOrderByIdAsc(10L))
                .thenReturn(List.of(variant(100L, faq, "같은 질문")));

        FaqResponseDto result =
                service.updateActiveFaq(
                        new FaqUpdateRequestDto(
                                10L,
                                2L,
                                "같은 질문",
                                "답변",
                                Intent.STORE_DATA
                        ),
                        9L
                );

        verify(vectorService)
                .updateVectorsForVariant(
                        100L,
                        2,
                        "같은 질문",
                        "답변",
                        false
                );

        assertThat(result.version())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("답변만 바뀌면 답변 변경만 벡터 서비스에 알린다")
    void updateFaq_updatesOnlyVectorVersionWhenQuestionIsSame() {
        Faq faq = faq(
                10L,
                "같은 질문",
                1,
                category(2L, "기존")
        );

        when(faqRepository.findActiveById(10L))
                .thenReturn(Optional.of(faq));

        when(userRepository.findById(9L))
                .thenReturn(
                        Optional.of(
                                User.builder()
                                        .id(9L)
                                        .build()
                        )
                );

        when(categoryRepository.findByIdAndDeletedAtIsNull(2L))
                .thenReturn(
                        Optional.of(
                                category(2L, "기존")
                        )
                );

        when(faqRepository.save(faq))
                .thenReturn(faq);

        when(variantRepository.findByOriginFaqIdAndDeletedAtIsNullOrderByIdAsc(10L))
                .thenReturn(List.of(variant(100L, faq, "같은 질문")));

        FaqResponseDto result =
                service.updateActiveFaq(
                        new FaqUpdateRequestDto(
                                10L,
                                2L,
                                "같은 질문",
                                "수정 답변",
                                Intent.GENERAL
                        ),
                        9L
                );

        verify(vectorService)
                .updateVectorsForVariant(
                        100L,
                        2,
                        "같은 질문",
                        "수정 답변",
                        true
                );

        assertThat(result.version())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("원본 질문이 같으면 요청 목록에 남은 variant를 유지하고 빠진 variant를 삭제한다")
    void updateFaq_reconcilesSimilarQuestionsAndCacheable() {
        Faq faq = faq(10L, "원본", 1, category(2L, "기존"));
        givenUpdatableFaq(faq);
        FaqQuestionVariant original = variant(100L, faq, "원본");
        FaqQuestionVariant kept = variant(101L, faq, "유지");
        FaqQuestionVariant removed = variant(102L, faq, "제거");
        when(variantRepository.findByOriginFaqIdAndDeletedAtIsNullOrderByIdAsc(10L))
                .thenReturn(List.of(original, kept, removed));
        when(variantRepository.save(any(FaqQuestionVariant.class)))
                .thenAnswer(invocation -> variant(103L, faq,
                        invocation.getArgument(0, FaqQuestionVariant.class).getQuestion()));

        FaqResponseDto result = service.updateActiveFaq(new FaqUpdateRequestDto(
                10L, 2L, "원본", "답변", Intent.GENERAL, true, List.of("유지", "신규")), 9L);

        ArgumentCaptor<OldFaq> oldFaq = ArgumentCaptor.forClass(OldFaq.class);
        verify(oldFaqRepository).saveAndFlush(oldFaq.capture());
        assertThat(oldFaq.getValue().isCacheable()).isFalse();
        assertThat(result.cacheable()).isTrue();
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.similarQuestions()).containsExactly("유지", "신규");
        assertThat(removed.getDeletedAt()).isNotNull();
        verify(vectorService).updateVectorsForVariant(100L, 2, "원본", "답변", false);
        verify(vectorService).updateVectorsForVariant(101L, 2, "유지", "답변", false);
        verify(vectorService).deleteVectorsForVariant(102L);
        verify(vectorService).saveVectorsForVariant(103L, 2, "신규", "답변");
    }

    @Test
    @DisplayName("공백 검색어는 빈 문자열로 정규화해 활성 FAQ 목록을 조회한다")
    void getActiveFaqList_normalizesBlankKeyword() {
        var pageable = PageRequest.of(
                0,
                10,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        when(
                faqRepository.findAllActives(
                        pageable,
                        "",
                        null
                )
        ).thenReturn(
                new PageImpl<>(
                        List.of(
                                faq(
                                        10L,
                                        "질문",
                                        1,
                                        category(
                                                2L,
                                                "카테고리"
                                        )
                                )
                        ),
                        pageable,
                        1
                )
        );

        PageResponseDto<FaqResponseDto> result =
                service.getActiveFaqList(
                        0,
                        10,
                        "  ",
                        null
                );

        assertThat(result.content())
                .extracting(FaqResponseDto::id)
                .containsExactly(10L);

        verify(faqRepository)
                .findAllActives(
                        pageable,
                        "",
                        null
                );
    }

    @Test
    @DisplayName("활성 FAQ를 삭제하면 삭제 시각을 기록한다")
    void deleteFaq_marksFaqDeleted() {
        Faq faq = faq(
                10L,
                "질문",
                1,
                category(
                        2L,
                        "카테고리"
                )
        );

        when(faqRepository.findActiveById(10L))
                .thenReturn(Optional.of(faq));

        service.deleteFaq(10L);

        assertThat(faq.getDeletedAt())
                .isNotNull();
    }

    @Test
    @DisplayName("저장소에서 조회한 삭제 FAQ만 복구한다")
    void restoreFaqs_restoresOnlyDeletedFaqsReturnedByRepository() {
        Faq deletedFaq = faq(
                10L,
                "삭제된 질문",
                1,
                category(
                        2L,
                        "카테고리"
                )
        );

        deletedFaq.delete();

        var request =
                new FaqRestoreRequestDto(
                        List.of(10L, 11L)
                );

        when(
                faqRepository.findDeletedFaqByFaqIds(
                        request.faqIds()
                )
        ).thenReturn(
                List.of(deletedFaq)
        );

        service.restoreFaqs(request);

        assertThat(deletedFaq.getDeletedAt())
                .isNull();

        verify(faqRepository)
                .findDeletedFaqByFaqIds(
                        request.faqIds()
                );
    }

    /** 수정 요청이 통과하도록 FAQ, 수정자, 카테고리(ID 2) 조회를 준비합니다. */
    private void givenUpdatableFaq(Faq faq) {
        when(faqRepository.findActiveById(faq.getId()))
                .thenReturn(Optional.of(faq));

        when(userRepository.findById(9L))
                .thenReturn(
                        Optional.of(
                                User.builder()
                                        .id(9L)
                                        .build()
                        )
                );

        when(categoryRepository.findByIdAndDeletedAtIsNull(2L))
                .thenReturn(
                        Optional.of(
                                category(2L, "변경")
                        )
                );

        when(faqRepository.save(faq))
                .thenReturn(faq);
    }

    private void assertFaqError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            FaqErrorCode code
    ) {
        assertThatThrownBy(call)
                .isInstanceOf(FaqException.class)
                .extracting(
                        error ->
                                ((FaqException) error)
                                        .getErrorCode()
                )
                .isEqualTo(code);
    }

    private FaqCategory category(
            Long id,
            String name
    ) {
        return FaqCategory.builder()
                .id(id)
                .name(name)
                .build();
    }

    private Faq faq(
            Long id,
            String question,
            int version,
            FaqCategory category
    ) {
        LocalDateTime now = LocalDateTime.now();

        return Faq.builder()
                .id(id)
                .admin(
                        User.builder()
                                .id(1L)
                                .build()
                )
                .faqCategory(category)
                .question(question)
                .answer("답변")
                .intent(Intent.GENERAL)
                .version(version)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private FaqQuestionVariant variant(Long id, Faq faq, String question) {
        return FaqQuestionVariant.builder()
                .id(id)
                .originFaq(faq)
                .question(question)
                .build();
    }
}

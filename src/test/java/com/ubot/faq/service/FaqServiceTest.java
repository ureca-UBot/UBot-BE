package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pgvector.PGvector;
import com.ubot.common.ErrorCode;
import com.ubot.common.PageResponseDto;
import com.ubot.common.exception.FaqException;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.request.FaqCreateRequestDto;
import com.ubot.faq.dto.request.FaqUpdateRequestDto;
import com.ubot.faq.dto.response.FaqResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.repository.FaqCategoryRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.repository.OldFaqRepository;
import com.ubot.user.entity.User;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class FaqServiceTest {

    private final FaqRepository faqRepository = mock(FaqRepository.class);
    private final FaqCategoryRepository faqCategoryRepository = mock(FaqCategoryRepository.class);
    private final OldFaqRepository oldFaqRepository = mock(OldFaqRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final FaqService faqService = new FaqService(
            faqRepository, faqCategoryRepository, oldFaqRepository, userRepository, embeddingService
    );

    @Test
    @DisplayName("관리자와 카테고리를 지정해 FAQ를 생성하면 응답 DTO를 반환한다")
    void createFaq_returnsResponseDto() {
        // given
        User admin = User.builder().id(10L).build();
        FaqCategory category = category(20L, "account");
        FaqCreateRequestDto request = new FaqCreateRequestDto(20L, "question", "answer");
        PGvector vector = vector(1.0f);
        when(userRepository.findById(10L)).thenReturn(Optional.of(admin));
        when(faqCategoryRepository.findByIdAndDeletedAtIsNull(20L)).thenReturn(Optional.of(category));
        when(embeddingService.embedText("question")).thenReturn(vector);
        when(faqRepository.save(any(Faq.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        FaqResponseDto result = faqService.createFaq(request, 10L);

        // then
        assertThat(result.categoryId()).isEqualTo(20L);
        assertThat(result.question()).isEqualTo("question");
        assertThat(result.answer()).isEqualTo("answer");
        assertThat(result.adminId()).isEqualTo(10L);
        verify(embeddingService).embedText("question");
        verify(faqRepository).save(any(Faq.class));
    }

    @Test
    @DisplayName("존재하지 않는 카테고리로 FAQ를 생성하면 예외가 발생한다")
    void createFaq_throwsWhenCategoryDoesNotExist() {
        // given
        FaqCreateRequestDto request = new FaqCreateRequestDto(10L, "question", "answer");
        when(userRepository.findById(10L)).thenReturn(Optional.of(User.builder().id(10L).build()));
        when(faqCategoryRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.empty());

        // when
        var throwable = assertThatThrownBy(() -> faqService.createFaq(request, 10L));

        // then
        throwable.isInstanceOf(FaqException.class)
                .extracting(exception -> ((FaqException) exception).getErrorCode())
                .isEqualTo(ErrorCode.FAQ_CATEGORY_NOT_FOUND);
    }

    @Test
    @DisplayName("FAQ를 수정하면 이전 버전을 이력으로 저장하고 새 임베딩을 반영한다")
    void updateActiveFaq_savesHistoryAndReturnsResponseDto() {
        // given
        User creator = User.builder().id(1L).build();
        User editor = User.builder().id(2L).build();
        FaqCategory oldCategory = category(10L, "old");
        FaqCategory newCategory = category(11L, "new");
        LocalDateTime createdAt = LocalDateTime.now().minusDays(1);
        Faq faq = Faq.builder()
                .id(100L).admin(creator).faqCategory(oldCategory)
                .question("old question").answer("old answer").version(3)
                .createdAt(createdAt).updatedAt(createdAt).vector(vector(0.0f))
                .build();
        PGvector newVector = vector(1.0f);
        FaqUpdateRequestDto request = new FaqUpdateRequestDto(100L, 10L, "new question", "new answer");
        when(faqRepository.findActiveById(100L)).thenReturn(Optional.of(faq));
        when(userRepository.findById(2L)).thenReturn(Optional.of(editor));
        when(faqCategoryRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(newCategory));
        when(embeddingService.embedText("new question")).thenReturn(newVector);
        when(faqRepository.save(faq)).thenReturn(faq);

        // when
        FaqResponseDto result = faqService.updateActiveFaq(request, 2L);

        // then
        ArgumentCaptor<OldFaq> historyCaptor = ArgumentCaptor.forClass(OldFaq.class);
        verify(oldFaqRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getQuestion()).isEqualTo("old question");
        assertThat(historyCaptor.getValue().getUpdatedBy()).isSameAs(editor);
        assertThat(faq.getVector()).isSameAs(newVector);
        assertThat(result.version()).isEqualTo(4);
        assertThat(result.categoryId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("활성 FAQ를 삭제하면 삭제 시각을 기록한다")
    void deleteFaq_marksActiveFaqAsDeleted() {
        // given
        Faq faq = Faq.builder().id(100L).build();
        when(faqRepository.findActiveById(100L)).thenReturn(Optional.of(faq));

        // when
        faqService.deleteFaq(100L);

        // then
        assertThat(faq.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("활성 FAQ 목록을 생성일과 ID 내림차순으로 조회한다")
    void getActiveFaqList_returnsPageResponseDto() {
        // given
        int page = 0;
        int size = 10;
        Faq active = faq(1L, "active question", null);
        PageRequest pageable = faqPageRequest(page, size, "createdAt");
        Page<Faq> faqPage = new PageImpl<>(List.of(active), pageable, 1);
        when(faqRepository.findAllActives(pageable, null, null)).thenReturn(faqPage);

        // when
        PageResponseDto<FaqResponseDto> result = faqService.getActiveFaqList(page, size, null, null);

        // then
        assertPageResponse(result, page, size, FaqResponseDto.from(active));
    }

    @Test
    @DisplayName("삭제 FAQ 목록을 삭제일과 ID 내림차순으로 조회한다")
    void getDeletedFaqList_returnsPageResponseDto() {
        // given
        int page = 0;
        int size = 10;
        Faq deleted = faq(2L, "deleted question", LocalDateTime.now());
        PageRequest pageable = faqPageRequest(page, size, "deletedAt");
        Page<Faq> faqPage = new PageImpl<>(List.of(deleted), pageable, 1);
        when(faqRepository.findAllDeletedFaq(pageable)).thenReturn(faqPage);

        // when
        PageResponseDto<FaqResponseDto> result = faqService.getDeletedFaqList(page, size);

        // then
        assertPageResponse(result, page, size, FaqResponseDto.from(deleted));
    }

    @Test
    @DisplayName("카테고리별 FAQ 조회는 삭제 FAQ를 포함한다")
    void getFaqListByFaqCategoryId_includesDeletedFaqs() {
        // given
        int page = 0;
        int size = 10;
        Long categoryId = 20L;
        Faq deleted = faq(2L, "deleted question", LocalDateTime.now());
        PageRequest pageable = faqPageRequest(page, size, "createdAt");
        Page<Faq> faqPage = new PageImpl<>(List.of(deleted), pageable, 1);
        when(faqRepository.findAllByFaqCategoryId(categoryId, pageable)).thenReturn(faqPage);

        // when
        PageResponseDto<FaqResponseDto> result = faqService.getFaqListByFaqCategoryId(page, size, categoryId);

        // then
        assertPageResponse(result, page, size, FaqResponseDto.from(deleted));
        verify(faqRepository).findAllByFaqCategoryId(categoryId, pageable);
    }

    private PageRequest faqPageRequest(int page, int size, String primaryProperty) {
        return PageRequest.of(page, size, Sort.by(
                Sort.Order.desc(primaryProperty),
                Sort.Order.desc("id")
        ));
    }

    private Faq faq(Long id, String question, LocalDateTime deletedAt) {
        LocalDateTime now = LocalDateTime.now();
        return Faq.builder()
                .id(id)
                .admin(User.builder().id(10L).build())
                .faqCategory(category(20L, "account"))
                .question(question)
                .answer("answer")
                .version(1)
                .createdAt(now)
                .updatedAt(now)
                .deletedAt(deletedAt)
                .build();
    }

    private FaqCategory category(Long id, String name) {
        return FaqCategory.builder().id(id).name(name).build();
    }

    private PGvector vector(float value) {
        return new PGvector(new float[]{value});
    }

    private void assertPageResponse(
            PageResponseDto<FaqResponseDto> result,
            int page,
            int size,
            FaqResponseDto expectedContent
    ) {
        assertThat(result.content()).containsExactly(expectedContent);
        assertThat(result.page()).isEqualTo(page);
        assertThat(result.size()).isEqualTo(size);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
    }
}

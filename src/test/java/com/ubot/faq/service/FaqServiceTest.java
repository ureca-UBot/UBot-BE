package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ubot.common.PageResponseDto;
import com.ubot.faq.dto.request.FaqCreateRequestDto;
import com.ubot.faq.dto.request.FaqUpdateRequestDto;
import com.ubot.faq.dto.response.FaqResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.repository.FaqCategoryRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.repository.OldFaqRepository;
import com.ubot.user.entity.User;
import com.ubot.user.exception.UserException;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DisplayName("FAQ 서비스 테스트")
class FaqServiceTest {
    private final FaqRepository faqRepository = mock(FaqRepository.class);
    private final FaqVectorService vectorService = mock(FaqVectorService.class);
    private final FaqCategoryRepository categoryRepository = mock(FaqCategoryRepository.class);
    private final OldFaqRepository oldFaqRepository = mock(OldFaqRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final FaqService service = new FaqService(faqRepository, vectorService, categoryRepository, oldFaqRepository, userRepository);

    @Test
    @DisplayName("관리자와 활성 카테고리가 있으면 FAQ를 저장하고 질문 벡터를 생성한다")
    void createFaq_savesFaqAndVector() {
        var request = new FaqCreateRequestDto(2L, "요금제가 궁금해요", "요금제 안내입니다.");
        when(userRepository.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(categoryRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(category(2L, "요금제")));
        when(faqRepository.save(any(Faq.class))).thenAnswer(i -> i.getArgument(0));
        FaqResponseDto result = service.createFaq(request, 1L);
        assertThat(result.question()).isEqualTo(request.question());
        verify(vectorService).saveVectorForFaq(result.id(), request.question());
        verify(faqRepository, times(2)).save(any(Faq.class));
    }

    @Test
    @DisplayName("존재하지 않는 관리자로 FAQ를 생성하면 사용자 없음 예외가 발생한다")
    void createFaq_throwsWhenAdminMissing() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createFaq(new FaqCreateRequestDto(2L, "질문", "답변"), 1L)).isInstanceOf(UserException.class);
        verifyNoInteractions(categoryRepository, faqRepository, vectorService);
    }

    @Test
    @DisplayName("존재하지 않는 카테고리로 FAQ를 생성하면 카테고리 없음 예외가 발생한다")
    void createFaq_throwsWhenCategoryMissing() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(categoryRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.empty());
        assertFaqError(() -> service.createFaq(new FaqCreateRequestDto(2L, "질문", "답변"), 1L), FaqErrorCode.FAQ_CATEGORY_NOT_FOUND);
    }

    @Test
    @DisplayName("질문이 바뀐 FAQ를 수정하면 기존 벡터를 이력에 복사하고 새 벡터를 저장한다")
    void updateFaq_copiesHistoryAndRegeneratesVector() {
        var faq = faq(10L, "기존 질문", 3, category(2L, "기존"));
        when(faqRepository.findActiveById(10L)).thenReturn(Optional.of(faq));
        when(userRepository.findById(9L)).thenReturn(Optional.of(User.builder().id(9L).build()));
        when(categoryRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(category(3L, "변경")));
        when(faqRepository.save(faq)).thenReturn(faq);
        FaqResponseDto result = service.updateActiveFaq(new FaqUpdateRequestDto(10L, 3L, "새 질문", "새 답변"), 9L);
        var history = ArgumentCaptor.forClass(OldFaq.class);
        verify(oldFaqRepository).saveAndFlush(history.capture());
        assertThat(history.getValue().getQuestion()).isEqualTo("기존 질문");
        verify(vectorService).saveVectorForOldFaq(10L, 3);
        verify(vectorService).saveVectorForFaq(10L, "새 질문");
        assertThat(result.version()).isEqualTo(4);
    }

    @Test
    @DisplayName("질문이 같은 FAQ를 수정하면 새 FAQ 벡터는 생성하지 않는다")
    void updateFaq_doesNotRegenerateVectorWhenQuestionIsSame() {
        var faq = faq(10L, "같은 질문", 1, category(2L, "기존"));
        when(faqRepository.findActiveById(10L)).thenReturn(Optional.of(faq));
        when(userRepository.findById(9L)).thenReturn(Optional.of(User.builder().id(9L).build()));
        when(categoryRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(category(2L, "기존")));
        when(faqRepository.save(faq)).thenReturn(faq);
        service.updateActiveFaq(new FaqUpdateRequestDto(10L, 2L, "같은 질문", "수정 답변"), 9L);
        verify(vectorService).saveVectorForOldFaq(10L, 1);
        verify(vectorService, never()).saveVectorForFaq(anyLong(), anyString());
    }

    @Test
    @DisplayName("공백 검색어는 빈 문자열로 정규화해 활성 FAQ 목록을 조회한다")
    void getActiveFaqList_normalizesBlankKeyword() {
        var pageable = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        when(faqRepository.findAllActives(pageable, "", null)).thenReturn(new PageImpl<>(List.of(faq(10L, "질문", 1, category(2L, "카테고리"))), pageable, 1));
        PageResponseDto<FaqResponseDto> result = service.getActiveFaqList(0, 10, "  ", null);
        assertThat(result.content()).extracting(FaqResponseDto::id).containsExactly(10L);
        verify(faqRepository).findAllActives(pageable, "", null);
    }

    @Test
    @DisplayName("활성 FAQ를 삭제하면 삭제 시각을 기록한다")
    void deleteFaq_marksFaqDeleted() {
        var faq = faq(10L, "질문", 1, category(2L, "카테고리"));
        when(faqRepository.findActiveById(10L)).thenReturn(Optional.of(faq));
        service.deleteFaq(10L);
        assertThat(faq.getDeletedAt()).isNotNull();
    }

    private void assertFaqError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, FaqErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(FaqException.class).extracting(error -> ((FaqException) error).getErrorCode()).isEqualTo(code);
    }
    private FaqCategory category(Long id, String name) { return FaqCategory.builder().id(id).name(name).build(); }
    private Faq faq(Long id, String question, int version, FaqCategory category) {
        var now = LocalDateTime.now();
        return Faq.builder().id(id).admin(User.builder().id(1L).build()).faqCategory(category).question(question).answer("답변").version(version).createdAt(now).updatedAt(now).build();
    }
}

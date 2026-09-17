package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.common.ErrorCode;
import com.ubot.common.exception.FaqException;
import com.ubot.faq.dto.reqeust.FaqCreateRequestDto;
import com.ubot.faq.dto.reqeust.FaqUpdateRequestDto;
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

class FaqServiceTest {

    private final FaqRepository faqRepository = mock(FaqRepository.class);
    private final FaqCategoryRepository faqCategoryRepository = mock(FaqCategoryRepository.class);
    private final OldFaqRepository oldFaqRepository = mock(OldFaqRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final FaqService faqService = new FaqService(
            faqRepository, faqCategoryRepository, oldFaqRepository, userRepository
    );

    @Test
    @DisplayName("관리자와 카테고리를 지정해 FAQ를 생성한다")
    void createsFaqWithAdminCategoryAndTimestamps() {
        User admin = User.builder().id(10L).build();
        FaqCategory category = FaqCategory.builder().id(20L).name("account").build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(admin));
        when(faqCategoryRepository.findByName("account")).thenReturn(Optional.of(category));
        when(faqRepository.save(any(Faq.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Faq result = faqService.createFaq(new FaqCreateRequestDto("account", "question", "answer"), 10L);

        assertThat(result.getAdmin()).isSameAs(admin);
        assertThat(result.getFaqCategory()).isSameAs(category);
        assertThat(result.getQuestion()).isEqualTo("question");
        assertThat(result.getAnswer()).isEqualTo("answer");
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();
        verify(faqRepository).save(result);
    }

    @Test
    @DisplayName("존재하지 않는 카테고리로 FAQ를 생성하면 예외가 발생한다")
    void rejectsCreationWhenCategoryDoesNotExist() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(User.builder().id(10L).build()));
        when(faqCategoryRepository.findByName("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> faqService.createFaq(
                new FaqCreateRequestDto("missing", "question", "answer"), 10L
        ))
                .isInstanceOf(FaqException.class)
                .extracting(exception -> ((FaqException) exception).getErrorCode())
                .isEqualTo(ErrorCode.FAQ_CATEGORY_NOT_FOUND);
    }

    @Test
    @DisplayName("FAQ를 수정하면 기존 내용이 이력으로 저장되고 버전이 증가한다")
    void updatesFaqAndStoresThePreviousVersion() {
        User creator = User.builder().id(1L).build();
        User editor = User.builder().id(2L).build();
        FaqCategory oldCategory = FaqCategory.builder().id(10L).name("old").build();
        FaqCategory newCategory = FaqCategory.builder().id(11L).name("new").build();
        Faq faq = Faq.builder()
                .id(100L)
                .admin(creator)
                .faqCategory(oldCategory)
                .question("old question")
                .answer("old answer")
                .version(3)
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
        when(faqRepository.findActiveById(100L)).thenReturn(Optional.of(faq));
        when(userRepository.findById(2L)).thenReturn(Optional.of(editor));
        when(faqCategoryRepository.findByName("new")).thenReturn(Optional.of(newCategory));
        when(faqRepository.save(faq)).thenReturn(faq);

        Faq result = faqService.updateActiveFaq(
                new FaqUpdateRequestDto(100L, "new", "new question", "new answer"), 2L
        );

        ArgumentCaptor<OldFaq> historyCaptor = ArgumentCaptor.forClass(OldFaq.class);
        verify(oldFaqRepository).save(historyCaptor.capture());
        OldFaq history = historyCaptor.getValue();
        assertThat(history.getFaqId()).isEqualTo(100L);
        assertThat(history.getVersion()).isEqualTo(3);
        assertThat(history.getFaqCategory()).isSameAs(oldCategory);
        assertThat(history.getQuestion()).isEqualTo("old question");
        assertThat(history.getUpdatedBy()).isSameAs(editor);
        assertThat(result.getVersion()).isEqualTo(4);
        assertThat(result.getFaqCategory()).isSameAs(newCategory);
        assertThat(result.getQuestion()).isEqualTo("new question");
        assertThat(result.getAnswer()).isEqualTo("new answer");
        assertThat(result.getUpdatedAt()).isAfter(faq.getCreatedAt());
    }

    @Test
    @DisplayName("활성 FAQ를 소프트 삭제한다")
    void softDeletesOnlyAnActiveFaq() {
        Faq faq = Faq.builder().id(100L).build();
        when(faqRepository.findActiveById(100L)).thenReturn(Optional.of(faq));

        faqService.deleteFaq(100L);

        assertThat(faq.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("활성 FAQ, 삭제 FAQ, 키워드 검색 FAQ를 조회한다")
    void retrievesActiveDeletedAndKeywordMatchedFaqs() {
        Faq active = Faq.builder().id(1L).build();
        Faq deleted = Faq.builder().id(2L).deletedAt(LocalDateTime.now()).build();
        when(faqRepository.findActiveById(1L)).thenReturn(Optional.of(active));
        when(faqRepository.findAllActives()).thenReturn(List.of(active));
        when(faqRepository.findAllDeletedFaq()).thenReturn(List.of(deleted));
        when(faqRepository.findActivesByKeyword("keyword")).thenReturn(List.of(active));

        assertThat(faqService.getActiveFaq(1L)).isSameAs(active);
        assertThat(faqService.getActiveFaqList()).containsExactly(active);
        assertThat(faqService.getDeletedFaqList()).containsExactly(deleted);
        assertThat(faqService.searchActiveFaq("keyword")).containsExactly(active);
    }
}

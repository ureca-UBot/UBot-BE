package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.common.ErrorCode;
import com.ubot.common.exception.FaqException;
import com.ubot.faq.dto.reqeust.FaqCategoryCreateRequestDto;
import com.ubot.faq.dto.reqeust.FaqCategoryUpdateRequestDto;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.repository.FaqCategoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FaqCategoryServiceTest {

    private final FaqCategoryRepository faqCategoryRepository = mock(FaqCategoryRepository.class);
    private final FaqCategoryService faqCategoryService = new FaqCategoryService(faqCategoryRepository);

    @Test
    @DisplayName("FAQ 카테고리를 생성하고 생성 시각을 기록한다")
    void createsCategoryWithTimestamp() {
        when(faqCategoryRepository.findByName("account")).thenReturn(Optional.empty());
        when(faqCategoryRepository.save(any(FaqCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FaqCategory result = faqCategoryService.createFaqCategory(new FaqCategoryCreateRequestDto("account"));

        assertThat(result.getName()).isEqualTo("account");
        assertThat(result.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("동일한 이름의 FAQ 카테고리를 생성하면 예외가 발생한다")
    void rejectsDuplicateCategoryOnCreation() {
        when(faqCategoryRepository.findByName("account"))
                .thenReturn(Optional.of(FaqCategory.builder().name("account").build()));

        assertThatThrownBy(() -> faqCategoryService.createFaqCategory(new FaqCategoryCreateRequestDto("account")))
                .isInstanceOf(FaqException.class)
                .extracting(exception -> ((FaqException) exception).getErrorCode())
                .isEqualTo(ErrorCode.FAQ_CATEGORY_EXIST);
    }

    @Test
    @DisplayName("카테고리 이름을 동일한 이름으로 변경하면 예외가 발생한다")
    void rejectsUpdateToTheSameName() {
        assertThatThrownBy(() -> faqCategoryService.updateFaqCategory(
                new FaqCategoryUpdateRequestDto("account", "account")
        ))
                .isInstanceOf(FaqException.class)
                .extracting(exception -> ((FaqException) exception).getErrorCode())
                .isEqualTo(ErrorCode.FAQ_CATEGORY_SAME_NAME);
    }

    @Test
    @DisplayName("사용 가능한 새 이름으로 FAQ 카테고리를 수정한다")
    void updatesCategoryWhenNewNameIsAvailable() {
        FaqCategory category = FaqCategory.builder().id(1L).name("old").build();
        when(faqCategoryRepository.findByName("old")).thenReturn(Optional.of(category));
        when(faqCategoryRepository.findByName("new")).thenReturn(Optional.empty());
        when(faqCategoryRepository.save(category)).thenReturn(category);

        FaqCategory result = faqCategoryService.updateFaqCategory(new FaqCategoryUpdateRequestDto("old", "new"));

        assertThat(result.getName()).isEqualTo("new");
        verify(faqCategoryRepository).save(category);
    }

    @Test
    @DisplayName("FAQ 카테고리를 ID로 삭제한다")
    void deletesCategoryById() {
        faqCategoryService.deleteFaqCategory(1L);

        verify(faqCategoryRepository).deleteById(1L);
    }

    @Test
    @DisplayName("FAQ 카테고리 전체 목록과 키워드 검색 결과를 조회한다")
    void retrievesAndSearchesCategories() {
        FaqCategory category = FaqCategory.builder().id(1L).name("account").build();
        when(faqCategoryRepository.findAll()).thenReturn(List.of(category));
        when(faqCategoryRepository.findByKeyword("count")).thenReturn(List.of(category));

        assertThat(faqCategoryService.getFaqCategories()).containsExactly(category);
        assertThat(faqCategoryService.searchFaqCategories("count")).containsExactly(category);
    }
}

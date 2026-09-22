package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.common.PageResponseDto;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.dto.request.FaqCategoryCreateRequestDto;
import com.ubot.faq.dto.request.FaqCategoryUpdateRequestDto;
import com.ubot.faq.dto.response.FaqCategoryResponseDto;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.repository.FaqCategoryRepository;
import com.ubot.faq.repository.FaqRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class FaqCategoryServiceTest {

    private final FaqCategoryRepository faqCategoryRepository = mock(FaqCategoryRepository.class);
    private final FaqRepository faqRepository = mock(FaqRepository.class);
    private final FaqCategoryService faqCategoryService = new FaqCategoryService(
            faqCategoryRepository,
            faqRepository
    );

    @Test
    @DisplayName("활성 카테고리가 없으면 생성하고 응답 DTO를 반환한다")
    void createFaqCategory_returnsResponseDto() {
        // given
        FaqCategoryCreateRequestDto request = new FaqCategoryCreateRequestDto("account");
        when(faqCategoryRepository.findByNameAndDeletedAtIsNull("account")).thenReturn(Optional.empty());
        when(faqCategoryRepository.save(any(FaqCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        FaqCategoryResponseDto result = faqCategoryService.createFaqCategory(request);

        // then
        assertThat(result.name()).isEqualTo("account");
        assertThat(result.createdAt()).isNotNull();
        verify(faqCategoryRepository).save(any(FaqCategory.class));
    }

    @Test
    @DisplayName("동일한 활성 카테고리 이름으로 생성하면 예외가 발생한다")
    void createFaqCategory_throwsWhenActiveNameExists() {
        // given
        when(faqCategoryRepository.findByNameAndDeletedAtIsNull("account"))
                .thenReturn(Optional.of(category(1L, "account")));

        // when
        var throwable = assertThatThrownBy(
                () -> faqCategoryService.createFaqCategory(new FaqCategoryCreateRequestDto("account"))
        );

        // then
        throwable.isInstanceOf(FaqException.class)
                .extracting(exception -> ((FaqException) exception).getErrorCode())
                .isEqualTo(FaqErrorCode.FAQ_CATEGORY_EXIST);
    }

    @Test
    @DisplayName("활성 카테고리를 ID로 조회하면 응답 DTO를 반환한다")
    void getFaqCategory_returnsResponseDto() {
        // given
        FaqCategory category = category(1L, "account");
        when(faqCategoryRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(category));

        // when
        FaqCategoryResponseDto result = faqCategoryService.getFaqCategory(1L);

        // then
        assertThat(result).isEqualTo(FaqCategoryResponseDto.from(category));
    }

    @Test
    @DisplayName("카테고리 이름을 수정하면 수정 시각과 이름을 갱신한다")
    void updateFaqCategory_updatesNameAndTimestamp() {
        // given
        FaqCategory category = category(1L, "old");
        when(faqCategoryRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(category));
        when(faqCategoryRepository.findByNameAndDeletedAtIsNull("new")).thenReturn(Optional.empty());
        when(faqCategoryRepository.save(category)).thenReturn(category);

        // when
        FaqCategoryResponseDto result = faqCategoryService.updateFaqCategory(
                1L,
                new FaqCategoryUpdateRequestDto("new")
        );

        // then
        assertThat(result.name()).isEqualTo("new");
        assertThat(result.updatedAt()).isNotNull();
        verify(faqCategoryRepository).save(category);
    }

    @Test
    @DisplayName("카테고리를 사용 중인 FAQ가 있으면 삭제할 수 없다")
    void deleteFaqCategory_throwsWhenFaqExistsIncludingDeletedFaq() {
        // given
        FaqCategory category = category(1L, "account");
        when(faqCategoryRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(category));
        when(faqRepository.existsAllByFaqCategoryId(1L)).thenReturn(true);

        // when
        var throwable = assertThatThrownBy(() -> faqCategoryService.deleteFaqCategory(1L));

        // then
        throwable.isInstanceOf(FaqException.class)
                .extracting(exception -> ((FaqException) exception).getErrorCode())
                .isEqualTo(FaqErrorCode.FAQ_CATEGORY_IN_USE);
    }

    @Test
    @DisplayName("사용 중인 FAQ가 없는 활성 카테고리를 삭제하면 삭제 시각을 기록한다")
    void deleteFaqCategory_marksCategoryAsDeleted() {
        // given
        FaqCategory category = category(1L, "account");
        when(faqCategoryRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(category));
        when(faqRepository.existsAllByFaqCategoryId(1L)).thenReturn(false);

        // when
        faqCategoryService.deleteFaqCategory(1L);

        // then
        assertThat(category.getDeletedAt()).isNotNull();
    }

    private PageRequest categoryPageRequest(int page, int size) {
        return PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
        ));
    }

    private FaqCategory category(Long id, String name) {
        return FaqCategory.builder().id(id).name(name).build();
    }
}

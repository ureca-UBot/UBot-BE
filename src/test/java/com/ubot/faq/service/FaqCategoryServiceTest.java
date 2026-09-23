package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ubot.common.PageResponseDto;
import com.ubot.faq.dto.request.FaqCategoryCreateRequestDto;
import com.ubot.faq.dto.request.FaqCategoryUpdateRequestDto;
import com.ubot.faq.dto.response.FaqCategoryResponseDto;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.repository.FaqCategoryRepository;
import com.ubot.faq.repository.FaqRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DisplayName("FAQ 카테고리 서비스 테스트")
class FaqCategoryServiceTest {
    private final FaqCategoryRepository categoryRepository = mock(FaqCategoryRepository.class);
    private final FaqRepository faqRepository = mock(FaqRepository.class);
    private final FaqCategoryService service = new FaqCategoryService(categoryRepository, faqRepository);

    @Test
    @DisplayName("동일한 활성 카테고리가 없으면 카테고리를 생성한다")
    void createCategory_savesNewCategory() {
        when(categoryRepository.findByNameAndDeletedAtIsNull("가입")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(FaqCategory.class))).thenAnswer(i -> i.getArgument(0));

        FaqCategoryResponseDto result = service.createFaqCategory(new FaqCategoryCreateRequestDto("가입"));

        assertThat(result.name()).isEqualTo("가입");
        assertThat(result.createdAt()).isNotNull();
        verify(categoryRepository).save(any(FaqCategory.class));
    }

    @Test
    @DisplayName("동일한 활성 카테고리 이름으로 생성하면 중복 예외가 발생한다")
    void createCategory_throwsWhenNameExists() {
        when(categoryRepository.findByNameAndDeletedAtIsNull("가입")).thenReturn(Optional.of(category(1L, "가입")));

        assertFaqError(() -> service.createFaqCategory(new FaqCategoryCreateRequestDto("가입")), FaqErrorCode.FAQ_CATEGORY_EXIST);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("공백 검색어는 빈 문자열로 정규화하여 활성 카테고리 목록을 조회한다")
    void getCategories_normalizesBlankKeyword() {
        var pageable = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        when(categoryRepository.findByDeletedAtIsNull(pageable, ""))
                .thenReturn(new PageImpl<>(List.of(category(1L, "가입")), pageable, 1));

        PageResponseDto<FaqCategoryResponseDto> result = service.getFaqCategories(0, 10, " \t");

        assertThat(result.content()).extracting(FaqCategoryResponseDto::name).containsExactly("가입");
        verify(categoryRepository).findByDeletedAtIsNull(pageable, "");
    }

    @Test
    @DisplayName("기존 이름과 같은 이름으로 수정하면 동일 이름 예외가 발생한다")
    void updateCategory_throwsWhenNameIsUnchanged() {
        when(categoryRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(category(1L, "가입")));

        assertFaqError(() -> service.updateFaqCategory(1L, new FaqCategoryUpdateRequestDto("가입")), FaqErrorCode.FAQ_CATEGORY_SAME_NAME);
    }

    @Test
    @DisplayName("사용 중인 FAQ가 없는 카테고리를 수정하면 이름을 변경한다")
    void updateCategory_updatesName() {
        var category = category(1L, "가입");
        when(categoryRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(category));
        when(categoryRepository.findByNameAndDeletedAtIsNull("해지")).thenReturn(Optional.empty());
        when(categoryRepository.save(category)).thenReturn(category);

        FaqCategoryResponseDto result = service.updateFaqCategory(1L, new FaqCategoryUpdateRequestDto("해지"));

        assertThat(result.name()).isEqualTo("해지");
        verify(categoryRepository).save(category);
    }

    @Test
    @DisplayName("삭제된 FAQ를 포함해 카테고리를 사용 중이면 카테고리를 삭제할 수 없다")
    void deleteCategory_throwsWhenFaqExists() {
        when(categoryRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(category(1L, "가입")));
        when(faqRepository.existsAllByFaqCategoryIdAndDeletedAtIsNull(1L)).thenReturn(true);

        assertFaqError(() -> service.deleteFaqCategory(1L), FaqErrorCode.FAQ_CATEGORY_IN_USE);
    }

    @Test
    @DisplayName("사용 중인 FAQ가 없는 카테고리를 삭제하면 삭제 시각을 기록한다")
    void deleteCategory_marksDeleted() {
        var category = category(1L, "가입");
        when(categoryRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(category));
        when(faqRepository.existsAllByFaqCategoryIdAndDeletedAtIsNull(1L)).thenReturn(false);

        service.deleteFaqCategory(1L);

        assertThat(category.getDeletedAt()).isNotNull();
    }

    private FaqCategory category(Long id, String name) { return FaqCategory.builder().id(id).name(name).build(); }
    private void assertFaqError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, FaqErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(FaqException.class)
                .extracting(error -> ((FaqException) error).getErrorCode()).isEqualTo(code);
    }
}

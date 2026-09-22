package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.common.PageResponseDto;
import com.ubot.faq.dto.response.FaqLogResponseDto;
import com.ubot.faq.dto.response.OldFaqResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.entity.FaqLog;
import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.repository.OldFaqRepository;
import com.ubot.user.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class FaqHistoryAndLogServiceTest {

    private final FaqLogRepository faqLogRepository = mock(FaqLogRepository.class);
    private final OldFaqRepository oldFaqRepository = mock(OldFaqRepository.class);
    private final FaqLogService faqLogService = new FaqLogService(faqLogRepository);
    private final OldFaqService oldFaqService = new OldFaqService(oldFaqRepository);

    @Test
    @DisplayName("질문 로그 ID로 FAQ 로그를 최신순으로 조회한다")
    void getFaqLogsByQuestionLogId_returnsPageResponseDto() {
        // given
        int page = 0;
        int size = 10;
        Long questionLogId = 10L;
        FaqLog faqLog = FaqLog.builder().id(1L).questionLogId(questionLogId).faq(faq()).build();
        PageRequest pageable = logPageRequest(page, size);
        Page<FaqLog> faqLogPage = new PageImpl<>(List.of(faqLog), pageable, 1);
        when(faqLogRepository.findByQuestionLogId(questionLogId, pageable)).thenReturn(faqLogPage);

        // when
        PageResponseDto<FaqLogResponseDto> result =
                faqLogService.getFaqLogsByQuestionLogId(page, size, questionLogId);

        // then
        assertPageResponse(result, page, size, FaqLogResponseDto.from(faqLog));
    }

    @Test
    @DisplayName("FAQ ID로 FAQ 로그를 최신순으로 조회한다")
    void getFaqLogsByFaqId_returnsPageResponseDto() {
        // given
        int page = 0;
        int size = 10;
        Long faqId = 20L;
        FaqLog faqLog = FaqLog.builder().id(2L).questionLogId(10L).faq(faq()).build();
        PageRequest pageable = logPageRequest(page, size);
        Page<FaqLog> faqLogPage = new PageImpl<>(List.of(faqLog), pageable, 1);
        when(faqLogRepository.findByFaqId(faqId, pageable)).thenReturn(faqLogPage);

        // when
        PageResponseDto<FaqLogResponseDto> result = faqLogService.getFaqLogsByFaqId(page, size, faqId);

        // then
        assertPageResponse(result, page, size, FaqLogResponseDto.from(faqLog));
    }

    @Test
    @DisplayName("FAQ ID로 변경 이력을 버전 내림차순으로 조회한다")
    void getOldFaqByFaqId_returnsPageResponseDto() {
        // given
        int page = 0;
        int size = 10;
        Long faqId = 20L;
        OldFaq oldFaq = oldFaq(faqId);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("version")));
        Page<OldFaq> oldFaqPage = new PageImpl<>(List.of(oldFaq), pageable, 1);
        when(oldFaqRepository.findByFaqId(faqId, pageable)).thenReturn(oldFaqPage);

        // when
        PageResponseDto<OldFaqResponseDto> result = oldFaqService.getOldFaqByFaqId(page, size, faqId);

        // then
        assertPageResponse(result, page, size, OldFaqResponseDto.from(oldFaq));
    }

    @Test
    @DisplayName("카테고리 ID로 변경 이력을 수정일과 FAQ ID, 버전 내림차순으로 조회한다")
    void getOldFaqByFaqCategoryId_returnsPageResponseDto() {
        // given
        int page = 0;
        int size = 10;
        Long categoryId = 30L;
        OldFaq oldFaq = oldFaq(20L);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("updatedAt"),
                Sort.Order.desc("faqId"),
                Sort.Order.desc("version")
        ));
        Page<OldFaq> oldFaqPage = new PageImpl<>(List.of(oldFaq), pageable, 1);
        when(oldFaqRepository.findByFaqCategoryId(categoryId, pageable)).thenReturn(oldFaqPage);

        // when
        PageResponseDto<OldFaqResponseDto> result =
                oldFaqService.getOldFaqByFaqCategoryId(page, size, categoryId);

        // then
        assertPageResponse(result, page, size, OldFaqResponseDto.from(oldFaq));
    }

    private PageRequest logPageRequest(int page, int size) {
        return PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
        ));
    }

    private Faq faq() {
        User admin = User.builder().id(10L).build();
        FaqCategory category = FaqCategory.builder().id(30L).name("account").build();
        LocalDateTime now = LocalDateTime.now();
        return Faq.builder()
                .id(20L).admin(admin).faqCategory(category)
                .question("question").answer("answer").version(1)
                .createdAt(now).updatedAt(now)
                .build();
    }

    private OldFaq oldFaq(Long faqId) {
        User user = User.builder().id(10L).build();
        FaqCategory category = FaqCategory.builder().id(30L).name("account").build();
        return OldFaq.builder()
                .faqId(faqId).version(1).faqCategory(category)
                .question("question").answer("answer")
                .createdBy(user).updatedBy(user).updatedAt(LocalDateTime.now())
                .build();
    }

    private <T> void assertPageResponse(PageResponseDto<T> result, int page, int size, T expectedContent) {
        assertThat(result.content()).containsExactly(expectedContent);
        assertThat(result.page()).isEqualTo(page);
        assertThat(result.size()).isEqualTo(size);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
    }
}

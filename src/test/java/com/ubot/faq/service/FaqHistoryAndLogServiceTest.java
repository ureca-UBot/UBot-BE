package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DisplayName("FAQ 이력 및 로그 서비스 테스트")
class FaqHistoryAndLogServiceTest {
    private final FaqLogRepository faqLogRepository = mock(FaqLogRepository.class);
    private final OldFaqRepository oldFaqRepository = mock(OldFaqRepository.class);
    private final FaqLogService faqLogService = new FaqLogService(faqLogRepository);
    private final OldFaqService oldFaqService = new OldFaqService(oldFaqRepository);

    @Test
    @DisplayName("질문 로그 ID로 FAQ 로그를 생성일과 ID 내림차순으로 조회한다")
    void getLogsByQuestionLogId_sortsByCreatedAtAndId() {
        var pageable = pageRequest("createdAt", "id");
        var log = FaqLog.builder().id(1L).questionLogId(7L).faq(faq()).rank(1).similarity(0.9).createdAt(LocalDateTime.now()).build();
        when(faqLogRepository.findByQuestionLogId(7L, pageable)).thenReturn(new PageImpl<>(List.of(log), pageable, 1));

        PageResponseDto<FaqLogResponseDto> result = faqLogService.getFaqLogsByQuestionLogId(0, 10, 7L);

        assertThat(result.content()).extracting(FaqLogResponseDto::questionLogId).containsExactly(7L);
        verify(faqLogRepository).findByQuestionLogId(7L, pageable);
    }

    @Test
    @DisplayName("FAQ ID로 FAQ 로그를 생성일과 ID 내림차순으로 조회한다")
    void getLogsByFaqId_sortsByCreatedAtAndId() {
        var pageable = pageRequest("createdAt", "id");
        var log = FaqLog.builder().id(1L).questionLogId(7L).faq(faq()).rank(1).similarity(0.9).createdAt(LocalDateTime.now()).build();
        when(faqLogRepository.findByFaqId(5L, pageable)).thenReturn(new PageImpl<>(List.of(log), pageable, 1));

        PageResponseDto<FaqLogResponseDto> result = faqLogService.getFaqLogsByFaqId(0, 10, 5L);

        assertThat(result.content()).hasSize(1);
        verify(faqLogRepository).findByFaqId(5L, pageable);
    }

    @Test
    @DisplayName("FAQ ID로 변경 이력을 버전 내림차순으로 조회한다")
    void getOldFaqByFaqId_sortsByVersion() {
        var pageable = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("version")));
        when(oldFaqRepository.findByFaqId(5L, pageable)).thenReturn(new PageImpl<>(List.of(oldFaq()), pageable, 1));

        PageResponseDto<OldFaqResponseDto> result = oldFaqService.getOldFaqByFaqId(0, 10, 5L);

        assertThat(result.content()).extracting(OldFaqResponseDto::faqId).containsExactly(5L);
    }

    private PageRequest pageRequest(String first, String second) { return PageRequest.of(0, 10, Sort.by(Sort.Order.desc(first), Sort.Order.desc(second))); }
    private Faq faq() { var now = LocalDateTime.now(); return Faq.builder().id(5L).admin(User.builder().id(1L).build()).faqCategory(FaqCategory.builder().id(2L).name("가입").build()).question("질문").answer("답변").version(1).createdAt(now).updatedAt(now).build(); }
    private OldFaq oldFaq() { return OldFaq.builder().faqId(5L).version(1).faqCategory(FaqCategory.builder().id(2L).name("가입").build()).question("질문").answer("답변").createdBy(User.builder().id(1L).build()).updatedBy(User.builder().id(1L).build()).updatedAt(LocalDateTime.now()).build(); }
}

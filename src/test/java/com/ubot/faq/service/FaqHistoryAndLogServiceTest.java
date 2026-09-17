package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.faq.entity.FaqLog;
import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.repository.OldFaqRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FaqHistoryAndLogServiceTest {

    private final FaqLogRepository faqLogRepository = mock(FaqLogRepository.class);
    private final OldFaqRepository oldFaqRepository = mock(OldFaqRepository.class);
    private final FaqLogService faqLogService = new FaqLogService(faqLogRepository);
    private final OldFaqService oldFaqService = new OldFaqService(oldFaqRepository);

    @Test
    @DisplayName("질문 로그 ID와 FAQ ID로 FAQ 로그를 조회한다")
    void findsFaqLogsByQuestionLogAndFaqId() {
        List<FaqLog> byQuestionLog = List.of(FaqLog.builder().id(1L).questionLogId(10L).build());
        List<FaqLog> byFaq = List.of(FaqLog.builder().id(2L).build());
        when(faqLogRepository.findByQuestionLogId(10L)).thenReturn(byQuestionLog);
        when(faqLogRepository.findByFaqId(20L)).thenReturn(byFaq);

        assertThat(faqLogService.getFaqLogsByQuestionLogId(10L)).isSameAs(byQuestionLog);
        assertThat(faqLogService.getFaqLogsByFaqId(20L)).isSameAs(byFaq);
    }

    @Test
    @DisplayName("FAQ ID와 카테고리 ID로 수정 이력을 조회하고 FAQ ID로 삭제한다")
    void findsAndDeletesFaqHistory() {
        List<OldFaq> histories = List.of(OldFaq.builder().faqId(20L).version(1).build());
        when(oldFaqRepository.findByFaqId(20L)).thenReturn(histories);
        when(oldFaqRepository.findByFaqCategoryId(30L)).thenReturn(histories);

        assertThat(oldFaqService.getOldFaqByFaqId(20L)).isSameAs(histories);
        assertThat(oldFaqService.getOldFaqByFaqCategoryId(30L)).isSameAs(histories);
        oldFaqService.deleteByFaqId(20L);

        verify(oldFaqRepository).deleteByFaqId(20L);
    }
}

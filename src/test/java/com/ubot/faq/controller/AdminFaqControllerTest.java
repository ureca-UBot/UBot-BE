package com.ubot.faq.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import com.ubot.faq.service.FaqCategoryService;
import com.ubot.faq.service.FaqEmbeddingBackfillService;
import com.ubot.faq.service.FaqLogService;
import com.ubot.faq.service.FaqService;
import com.ubot.faq.service.FaqSimilarQuestionService;
import com.ubot.faq.service.OldFaqService;

@DisplayName("관리자 FAQ 컨트롤러 테스트")
class AdminFaqControllerTest {

    private final FaqService faqService =
            mock(FaqService.class);

    private final FaqCategoryService faqCategoryService =
            mock(FaqCategoryService.class);

    private final FaqLogService faqLogService =
            mock(FaqLogService.class);

    private final OldFaqService oldFaqService =
            mock(OldFaqService.class);

    private final FaqEmbeddingBackfillService faqEmbeddingBackfillService =
            mock(FaqEmbeddingBackfillService.class);

    private final FaqSimilarQuestionService faqSimilarQuestionService =
            mock(FaqSimilarQuestionService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminFaqController controller =
                new AdminFaqController(
                        faqService,
                        faqCategoryService,
                        faqLogService,
                        oldFaqService,
                        faqEmbeddingBackfillService,
                        faqSimilarQuestionService
                );

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .build();
    }

    @Test
    @DisplayName("현재 임베딩 프로필의 FAQ 백필을 실행하고 처리 건수를 반환한다")
    void backfillFaqEmbeddings_returnsUpdatedCount() throws Exception {
        when(faqEmbeddingBackfillService.backfillCurrentProfile())
                .thenReturn(7);

        mockMvc.perform(
                        post("/admin/faqs/embeddings/backfill")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(7));

        verify(faqEmbeddingBackfillService)
                .backfillCurrentProfile();
    }

    @Test
    void generateSimilarQuestions_acceptsPlainTextAndReturnsQuestions() throws Exception {
        String question = "일시정지 중에도 요금이 발생하나요?";
        when(faqSimilarQuestionService.generate(question))
                .thenReturn(List.of("일시정지하면 요금이 나오나요?"));

        mockMvc.perform(post("/admin/faqs/question-variants/generate")
                        .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                        .content(question))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("일시정지하면 요금이 나오나요?"));

        verify(faqSimilarQuestionService).generate(question);
    }
}

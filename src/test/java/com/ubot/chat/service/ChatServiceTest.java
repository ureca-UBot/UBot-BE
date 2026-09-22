package com.ubot.chat.service;

import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.ai.service.AiService;
import com.ubot.common.exception.ChatException;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.exception.PromptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

        @Mock
        private FaqVectorService faqVectorService;

        @Mock
        private AiService aiService;

        @InjectMocks
        private ChatService chatService;

        @Test
        void 질문이_비어있으면_ChatException을_던진다() {
                assertThatThrownBy(() -> chatService.createChat(""))
                                .isInstanceOf(ChatException.class);
                verifyNoInteractions(faqVectorService, aiService);
        }

        @Test
        void 질문이_null이면_ChatException을_던진다() {
                assertThatThrownBy(() -> chatService.createChat(null))
                                .isInstanceOf(ChatException.class);
                verifyNoInteractions(faqVectorService, aiService);
        }

        @Test
        void 검색_결과가_없으면_FAQ_없음_사유와_함께_실패_응답을_반환한다() {
                when(faqVectorService.getSimilarList(anyString(), anyInt()))
                                .thenReturn(List.of());

                ChatResponseDto response = chatService.createChat("아무 질문");

                assertThat(response.success()).isFalse();
                assertThat(response.answer()).isEqualTo("검색 결과가 없습니다.");
                verifyNoInteractions(aiService);
        }

        @Test
        void 유사도가_threshold보다_작으면_사유와_함께_실패_응답을_반환한다() {
                when(faqVectorService.getSimilarList(anyString(), anyInt()))
                                .thenReturn(List.of(
                                                new FaqSearchResponseDto(1L, "질문", "답변", 0.12)));

                ChatResponseDto response = chatService.createChat("아무 질문");

                assertThat(response.success()).isFalse();
                assertThat(response.answer()).isEqualTo("정확한 답변을 찾지 못했습니다.");
                verifyNoInteractions(aiService);
        }

        @Test
        void 유사도가_threshold_이상이면_LLM이_생성한_답변을_반환한다() {
                when(faqVectorService.getSimilarList(anyString(), anyInt()))
                                .thenReturn(List.of(
                                                new FaqSearchResponseDto(1L, "유심 재발급", "매장에서 가능합니다", 0.9)));
                when(aiService.generateAnswer(anyString(), anyList()))
                                .thenReturn(new LlmResponseDto("LLM이 생성한 안내 답변"));

                ChatResponseDto response = chatService.createChat("유심 재발급 어떻게 해요");

                assertThat(response.success()).isTrue();
                assertThat(response.answer()).isEqualTo("LLM이 생성한 안내 답변");
        }

        @Test
        void 가장_높은_유사도가_통과하면_질문과_FAQ_전체를_AI에_전달한다() {
                var results = List.of(
                                new FaqSearchResponseDto(1L, "1등 질문", "1등 답변", 0.90),
                                new FaqSearchResponseDto(2L, "2등 질문", "2등 답변", 0.80),
                                new FaqSearchResponseDto(3L, "3등 질문", "3등 답변", 0.40));
                when(faqVectorService.getSimilarList(anyString(), anyInt()))
                                .thenReturn(results);
                when(aiService.generateAnswer("질문", results))
                                .thenReturn(new LlmResponseDto("여러 FAQ로 생성한 답변"));

                ChatResponseDto response = chatService.createChat("질문");

                assertThat(response.success()).isTrue();
                assertThat(response.answer()).isEqualTo("여러 FAQ로 생성한 답변");
                verify(aiService).generateAnswer("질문", results);
        }

        @Test
        void 유사도가_정확히_threshold이면_AI를_호출한다() {
                var results = List.of(new FaqSearchResponseDto(1L, "FAQ 질문", "FAQ 답변", 0.75));
                when(faqVectorService.getSimilarList("질문", 3)).thenReturn(results);
                when(aiService.generateAnswer("질문", results)).thenReturn(new LlmResponseDto("생성 답변"));

                assertThat(chatService.createChat("질문").answer()).isEqualTo("생성 답변");
                verify(aiService).generateAnswer("질문", results);
        }

        @Test
        void 프롬프트_미설정이면_준비중_실패_응답을_반환한다() {
                when(faqVectorService.getSimilarList(anyString(), anyInt()))
                                .thenReturn(List.of(new FaqSearchResponseDto(1L, "질문", "기존 FAQ 답변", 0.9)));
                when(aiService.generateAnswer(anyString(), anyList()))
                                .thenThrow(new PromptException("답변 프롬프트가 아직 준비되지 않았습니다."));

                ChatResponseDto response = chatService.createChat("질문");

                assertThat(response.success()).isFalse();
                assertThat(response.answer()).isEqualTo("답변 프롬프트가 아직 준비되지 않았습니다.");
        }

        @ParameterizedTest
        @EnumSource(LlmErrorCode.class)
        void LLM_실패는_기존_FAQ로_대체하지_않고_실패_응답으로_전달한다(LlmErrorCode errorCode) {
                when(faqVectorService.getSimilarList(anyString(), anyInt()))
                                .thenReturn(List.of(new FaqSearchResponseDto(1L, "질문", "기존 FAQ 답변", 0.9)));
                when(aiService.generateAnswer(anyString(), anyList()))
                                .thenThrow(new LlmException(errorCode, new IllegalStateException("내부 오류 정보")));

                ChatResponseDto response = chatService.createChat("질문");

                assertThat(response.success()).isFalse();
                assertThat(response.answer()).isEqualTo(errorCode.getMessage()).doesNotContain("내부 오류 정보");
        }
}

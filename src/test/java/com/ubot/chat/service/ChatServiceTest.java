package com.ubot.chat.service;

import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.common.exception.ChatException;
import com.ubot.faq.dto.FaqSearchResponseDto;
import com.ubot.faq.service.FaqVectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

        @Mock
        private FaqVectorService faqVectorService;

        @InjectMocks
        private ChatService chatService;

        @Test
        void 질문이_비어있으면_ChatException을_던진다() {
                assertThatThrownBy(() -> chatService.createChat(""))
                                .isInstanceOf(ChatException.class);
        }

        @Test
        void 질문이_null이면_ChatException을_던진다() {
                assertThatThrownBy(() -> chatService.createChat(null))
                                .isInstanceOf(ChatException.class);
        }

        @Test
        void 검색_결과가_없으면_FAQ_없음_사유와_함께_실패_응답을_반환한다() {
                when(faqVectorService.getSimilarList(anyString(), anyInt()))
                                .thenReturn(List.of());

                ChatResponseDto response = chatService.createChat("아무 질문");

                assertThat(response.success()).isFalse();
                assertThat(response.answer()).isEqualTo("검색 결과가 없습니다.");
        }

        @Test
        void 유사도가_threshold보다_작으면_사유와_함께_실패_응답을_반환한다() {
                when(faqVectorService.getSimilarList(anyString(), anyInt()))
                                .thenReturn(List.of(
                                                new FaqSearchResponseDto(1L, "질문", "답변", 0.12)));

                ChatResponseDto response = chatService.createChat("아무 질문");

                assertThat(response.success()).isFalse();
                assertThat(response.answer()).isEqualTo("정확한 답변을 찾지 못했습니다.");
        }

        @Test
        void 유사도가_threshold_이상이면_성공_응답과_답변을_반환한다() {
                when(faqVectorService.getSimilarList(anyString(), anyInt()))
                                .thenReturn(List.of(
                                                new FaqSearchResponseDto(1L, "유심 재발급", "매장에서 가능합니다", 0.9)));

                ChatResponseDto response = chatService.createChat("유심 재발급 어떻게 해요");

                assertThat(response.success()).isTrue();
                assertThat(response.answer()).isEqualTo("매장에서 가능합니다");
        }

        @Test
        void 여러_결과_중_유사도가_가장_높은_1등만_사용한다() {
                when(faqVectorService.getSimilarList(anyString(), anyInt()))
                                .thenReturn(List.of(
                                                new FaqSearchResponseDto(1L, "1등 질문", "1등 답변", 0.90),
                                                new FaqSearchResponseDto(2L, "2등 질문", "2등 답변", 0.80)));

                ChatResponseDto response = chatService.createChat("질문");

                assertThat(response.success()).isTrue();
                assertThat(response.answer()).isEqualTo("1등 답변");
        }
}
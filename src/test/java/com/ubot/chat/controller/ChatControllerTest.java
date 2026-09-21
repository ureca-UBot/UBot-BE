package com.ubot.chat.controller;

import com.ubot.auth.config.JwtAuthenticationFilter;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.service.ChatService;
import com.ubot.common.ErrorCode;
import com.ubot.common.exception.ChatException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("Chat Controller 테스트")
class ChatControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private ChatService chatService;

        @MockitoBean
        private JwtAuthenticationFilter jwtAuthenticationFilter;

        @Test
        @DisplayName("질문을 보내면 200과 성공 응답을 받는다")
        void returnsSuccessResponse() throws Exception {
                when(chatService.createChat(anyString()))
                                .thenReturn(ChatResponseDto.createSuccessAnswer("매장에서 가능합니다"));

                mockMvc.perform(post("/chat/questions") // ← 경로 수정
                                .contentType("application/json")
                                .content("""
                                                {"question": "유심 재발급 어떻게 해요"}
                                                """))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.success").value(true))
                                .andExpect(jsonPath("$.data.success").value(true))
                                .andExpect(jsonPath("$.data.answer").value("매장에서 가능합니다"));
        }

        @Test
        @DisplayName("검색 결과가 없으면 success:false 응답을 받는다")
        void returnsFailureWhenNoConfidentResult() throws Exception {
                when(chatService.createChat(anyString()))
                                .thenReturn(ChatResponseDto.createFailureAnswer());

                mockMvc.perform(post("/chat/questions") // ← 경로 수정
                                .contentType("application/json")
                                .content("""
                                                {"question": "전혀 관계없는 질문"}
                                                """))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.success").value(false));
        }

        @Test
        @DisplayName("Service에서 예외가 나면 GlobalExceptionHandler가 400으로 변환한다")
        void rejectsBlankQuestion() throws Exception {
                when(chatService.createChat(anyString()))
                                .thenThrow(new ChatException(ErrorCode.CHAT_QUESTION_REQUIRED));

                mockMvc.perform(post("/chat/questions")
                                .contentType("application/json")
                                .content("""
                                                {"question": ""}
                                                """))
                                .andExpect(status().isBadRequest());
        }
}
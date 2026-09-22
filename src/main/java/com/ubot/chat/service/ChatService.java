package com.ubot.chat.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.ai.service.AiService;
import com.ubot.common.ErrorCode;
import com.ubot.common.exception.ChatException;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.exception.PromptException;

import org.springframework.util.StringUtils;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int TOP_K = 3; // TODO: threshold 테스트 결과로 교체
    private static final double CONFIDENCE_THRESHOLD = 0.75; // TODO: 위와 동일

    private final FaqVectorService faqVectorService;
    private final AiService aiService;

    public ChatResponseDto createChat(String question) {

        if (!StringUtils.hasText(question)) {
            throw new ChatException(ErrorCode.INVALID_CHAT_REQUEST);
        }

        // TOP-K 검색 수행
        List<FaqSearchResponseDto> results = faqVectorService.getSimilarList(question, TOP_K);

        // 예외 방지
        if (results.isEmpty()) {
            return ChatResponseDto.createFailureAnswer("검색 결과가 없습니다.");
        }

        // 가장 유사한 FAQ의 점수로 답변 생성 여부를 판단합니다.
        FaqSearchResponseDto bestResponse = results.get(0);

        if (bestResponse.similarityScore() < CONFIDENCE_THRESHOLD) { // 가장 유사한 응답의 유사도가 임계값보다 작은 경우

            /* 이 질문을 클러스터링 용도로 따로 저장해두는 로직 추가 */

            return ChatResponseDto.createFailureAnswer("정확한 답변을 찾지 못했습니다.");
        } else {

            // 유사도 판정을 통과하면, 원래 질문과 검색된 FAQ 목록 전체를 AI 처리에 전달합니다.
            // 프롬프트 구성과 모델 호출은 AiService가 조율합니다.
            LlmResponseDto response;
            try {
                response = aiService.generateAnswer(question, results);
            } catch (PromptException exception) {
                // 승지님 프롬프트가 준비되지 않았거나 입력을 구성하지 못한 경우입니다.
                return ChatResponseDto.createFailureAnswer(exception.getMessage());
            } catch (LlmException exception) {
                // 모델 호출 실패를 FAQ 정답처럼 반환하지 않고, 기존 실패 응답 형식으로 전달합니다.
                return ChatResponseDto.createFailureAnswer(exception.getErrorCode().getMessage());
            }

            /* question_log에 사용자 질문 저장하는 로직 추가 */

            /* 각 검색 결과마다 faq_log에 저장하는 로직 추가 */

            // DB의 1등 FAQ 원문 대신 LLM이 생성한 최종 답변을 채팅으로 반환합니다.
            return ChatResponseDto.createSuccessAnswer(response.answer());
        }
    }
}

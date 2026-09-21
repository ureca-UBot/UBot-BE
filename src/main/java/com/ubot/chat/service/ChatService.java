package com.ubot.chat.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.faq.dto.FakeFaqSearchResponseDto;
import com.ubot.faq.service.FakeFaqVectorService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int TOP_K = 5; // TODO: threshold 테스트 결과로 교체
    private static final double CONFIDENCE_THRESHOLD = 0.75; // TODO: 위와 동일

    private final FakeFaqVectorService faqVectorService;

    public ChatResponseDto createChat(String question) {

        // TOP-K 검색 수행
        List<FakeFaqSearchResponseDto> results = faqVectorService.getSimilarList(question, TOP_K);

        if (results.isEmpty()) {
            return ChatResponseDto.createFailureAnswer();
        }

        /* 각 검색 결과마다 faq_log에 저장하는 로직 추가 */

        // 가장 유사한 질문 응답
        FakeFaqSearchResponseDto bestResponse = results.get(0);

        if (bestResponse.similarityScore() < CONFIDENCE_THRESHOLD) { // 가장 유사한 응답의 유사도가 임계값보다 작으면
            /* 이 질문을 클러스터링 용도로 따로 저장해두는 로직 추가 */
            return ChatResponseDto.createFailureAnswer();
        } else {
            return ChatResponseDto.createSuccessAnswer(bestResponse.answer());
        }
    }
}

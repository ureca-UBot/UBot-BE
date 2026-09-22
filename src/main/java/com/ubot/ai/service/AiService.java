package com.ubot.ai.service;

import com.ubot.faq.dto.FaqSearchResponseDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.service.PromptService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiService {

    private final PromptService promptService;
    private final LlmService llmService;

    public LlmResponseDto generateAnswer(String question, List<FaqSearchResponseDto> results) {
        // ChatService에서 검색과 유사도 판정을 마친 질문 및 FAQ 목록을 받습니다.
        // DB를 다시 조회하지 않고, 승지님이 작성할 프롬프트에 넣을 메시지를 구성합니다.
        LlmRequestDto request = promptService.createPrompt(question, results);

        // 프롬프트가 준비됐을 때만 LLM을 호출하고, 생성된 답변을 채팅 쪽으로 반환합니다.
        return llmService.generateAnswer(request);
    }
}

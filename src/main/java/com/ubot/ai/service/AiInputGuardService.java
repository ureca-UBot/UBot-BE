package com.ubot.ai.service;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.service.ChatInputGuardService;
import com.ubot.common.GlobalException;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.format.AiGuardFormat;
import com.ubot.prompt.format.AiGuardFormat.Decision;
import com.ubot.prompt.service.AiGuardPromptService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiInputGuardService {
    private final AiGuardModelService model;
    private final AiGuardPromptService prompts;
    private final ChatInputGuardService codeGuard;

    /** 기존 분류기가 필요한 시점에 안전 판정과 질문 정리를 한 번에 수행합니다. */
    public List<String> inspect(String original, String current, List<ConversationTurn> history) {
        // Worker 이외에서 직접 호출되어도 코드 차단을 건너뛰지 않습니다.
        codeGuard.prepareQuestion(original);
        long start = System.nanoTime();
        try {
            var response = model.generate(prompts.input(original, current, history));
            var verdict = AiGuardFormat.parseInput(response == null ? null : response.answer());
            log.info("입력 AI 가드: 단계=INTEGRATED, 판정={}, 사유={}, 처리시간={}ms",
                    verdict.decision(), verdict.reason(), elapsed(start));
            if (verdict.decision() == Decision.BLOCK) throw new ChatException(ChatErrorCode.PROMPT_INJECTION_DETECTED);
            if (verdict.decision() == Decision.REVIEW) throw new LlmException(LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED);
            return verdict.subQuestions();
        } catch (GlobalException exception) {
            if (exception.getErrorCode() != ChatErrorCode.PROMPT_INJECTION_DETECTED
                    && exception.getErrorCode() != LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED) {
                log.warn("입력 AI 가드: 판정=FAILED, 사유={}, 처리시간={}ms", exception.getErrorCode().getCode(), elapsed(start));
            }
            throw exception;
        }
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}

package com.ubot.ai.service;

import com.ubot.ai.config.AiInputGuardProperties;
import com.ubot.ai.dto.ConversationTurn;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.service.ChatInputGuardService;
import com.ubot.common.GlobalException;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.format.AiGuardFormat;
import com.ubot.prompt.format.AiGuardFormat.Decision;
import com.ubot.prompt.format.SubQuestionFormat;
import com.ubot.prompt.service.AiGuardPromptService;
import com.ubot.prompt.service.SubQuestionPromptService;
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
    private final AiInputGuardProperties properties;
    private final SubQuestionPromptService planningPrompts;
    private final LlmService planningLlm;

    /** 기존 분류기가 필요한 시점에 안전 판정과 질문 정리를 한 번에 수행합니다. */
    public List<String> inspect(String original, String current, List<ConversationTurn> history) {
        // Worker 이외에서 직접 호출되어도 공통 정리와 활성화된 코드 검사를 거칩니다.
        codeGuard.prepareQuestion(original);
        if (!properties.isEnabled() || !properties.isLlmEnabled()) {
            return planWithoutSafetyJudgment(current, history);
        }
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

    private List<String> planWithoutSafetyJudgment(String current, List<ConversationTurn> history) {
        // 통합 안전 판정 대신 기존 분류 요청 하나만 사용합니다. Router의 호출 조건은 바꾸지 않습니다.
        long started = System.nanoTime();
        var response = planningLlm.generateJudgment(planningPrompts.createPrompt(current, history));
        var questions = SubQuestionFormat.parse(response == null ? null : response.answer());
        log.info("입력 질문 정리: 단계=PLANNING_ONLY, 하위질문수={}, 처리시간={}ms", questions.size(), elapsed(started));
        return questions;
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}

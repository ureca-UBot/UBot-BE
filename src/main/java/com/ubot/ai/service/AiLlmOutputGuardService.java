package com.ubot.ai.service;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.common.GlobalException;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.format.AiOutputGuardAssessment;
import com.ubot.prompt.format.AiGuardFormat.Decision;
import com.ubot.prompt.service.AiGuardPromptService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiLlmOutputGuardService {
    private final AiGuardModelService model;
    private final AiGuardPromptService prompts;

    /** 코드 가드가 통과시킨 답변 전체를 판정합니다. 답변을 수정하거나 일부를 반환하지 않습니다. */
    public void validate(String answer, List<String> evidenceIds, AnswerMaterials materials, StoreMapResult stores) {
        long start = System.nanoTime();
        try {
            var response = model.generate(prompts.output(answer, evidenceIds, materials, stores));
            var verdict = AiOutputGuardAssessment.parse(response == null ? null : response.answer(), answer);
            log.info("출력 AI 가드: 판정={}, 사유={}, 처리시간={}ms", verdict.decision(), verdict.reasons(), elapsed(start));
            if (verdict.decision() == Decision.BLOCK) throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
            if (verdict.decision() == Decision.REVIEW) throw new LlmException(LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED);
        } catch (GlobalException exception) {
            if (exception.getErrorCode() != LlmErrorCode.LLM_OUTPUT_REJECTED
                    && exception.getErrorCode() != LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED) {
                log.warn("출력 AI 가드: 판정=FAILED, 사유={}, 처리시간={}ms", exception.getErrorCode().getCode(), elapsed(start));
            }
            throw exception;
        }
    }

    private long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}

package com.ubot.ai.service;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.dto.OutputGuardResult.Stage;
import com.ubot.ai.exception.AiOutputGuardException;
import com.ubot.common.GlobalException;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.prompt.format.AiOutputGuardAssessment;
import com.ubot.prompt.format.AiGuardFormat.Decision;
import com.ubot.prompt.service.AiGuardPromptService;
import com.ubot.guardreview.service.ReviewCheckService;
import com.ubot.guardreview.model.ReviewCheckModels.Outcome;
import com.ubot.llm.dto.request.LlmRequestDto;
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
    private final ReviewCheckService reviewChecks;

    /** 코드 가드가 통과시킨 답변 전체를 판정합니다. 답변을 수정하거나 일부를 반환하지 않습니다. */
    public void validate(String answer, List<String> evidenceIds, AnswerMaterials materials, StoreMapResult stores) {
        long start = System.nanoTime();
        LlmRequestDto request = null;
        try {
            request = prompts.output(answer, evidenceIds, materials, stores);
            var response = model.generate(request);
            var verdict = AiOutputGuardAssessment.parse(response == null ? null : response.answer(), answer);
            reviewChecks.outputLlm(answer, materials, stores, switch (verdict.decision()) {
                case ALLOW -> Outcome.PASS;
                case BLOCK -> Outcome.REJECTED;
                case REVIEW -> Outcome.REVIEW;
            }, verdict.reasons(), verdict.explanation(), request);
            log.info("출력 AI 가드: 판정={}, 사유={}, 처리시간={}ms", verdict.decision(), verdict.reasons(), elapsed(start));
            if (verdict.decision() == Decision.BLOCK) {
                throw new AiOutputGuardException(LlmErrorCode.LLM_OUTPUT_REJECTED, Stage.LLM, verdict.reasons());
            }
            if (verdict.decision() == Decision.REVIEW) {
                throw new AiOutputGuardException(LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED, Stage.LLM, verdict.reasons());
            }
        } catch (GlobalException exception) {
            if (exception.getErrorCode() != LlmErrorCode.LLM_OUTPUT_REJECTED
                    && exception.getErrorCode() != LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED) {
                log.warn("출력 AI 가드: 판정=FAILED, 사유={}, 처리시간={}ms", exception.getErrorCode().getCode(), elapsed(start));
                reviewChecks.outputLlm(answer, materials, stores, Outcome.ERROR,
                        List.of(exception.getErrorCode().getCode()), null, request);
            }
            throw exception;
        } catch (RuntimeException exception) {
            reviewChecks.outputLlm(answer, materials, stores, Outcome.ERROR, List.of("CHECK_FAILED"), null, request);
            throw exception;
        }
    }

    private long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}

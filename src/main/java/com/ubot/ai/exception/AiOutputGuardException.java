package com.ubot.ai.exception;

import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.dto.OutputGuardResult.Stage;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.util.List;
import java.util.Objects;

/** 기존 오류 코드는 유지하고, 수집 기록에 필요한 검사 단계와 사유 코드만 보존합니다. */
public class AiOutputGuardException extends LlmException {
    private final Stage stage;
    private final List<String> reasonCodes;

    public AiOutputGuardException(LlmErrorCode code, Stage stage, List<String> reasonCodes) {
        super(code);
        if (code != LlmErrorCode.LLM_OUTPUT_REJECTED && code != LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED) {
            throw new IllegalArgumentException("출력 검사 거절 또는 보류 코드가 필요합니다.");
        }
        this.stage = Objects.requireNonNull(stage);
        this.reasonCodes = reasonCodes.isEmpty() ? List.of("UNCLASSIFIED") : List.copyOf(reasonCodes);
    }

    public static AiOutputGuardException rejected(OutputGuardResult result) {
        return new AiOutputGuardException(LlmErrorCode.LLM_OUTPUT_REJECTED, Stage.CODE,
                result.reasons().stream().map(Enum::name).sorted().toList());
    }

    public Stage stage() {
        return stage;
    }

    public List<String> reasonCodes() {
        return reasonCodes;
    }
}

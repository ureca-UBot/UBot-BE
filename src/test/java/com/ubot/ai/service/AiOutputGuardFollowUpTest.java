package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.OutputGuardResult.Reason;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiOutputGuardFollowUpTest {
    private final ForbiddenWordRepository repository = mock(ForbiddenWordRepository.class);
    private final ForbiddenWordFilterService words = new ForbiddenWordFilterService(repository);
    private final AiOutputGuardService guard = new AiOutputGuardService(words);
    private final AnswerMaterials materials = AnswerMaterials.builder("청구 요금과 요금제 변경 방법을 알려 주세요.")
            .addSection(new ContextSection("공식 안내",
                    "요금제는 앱에서 변경할 수 있습니다. 월 요금은 30,000원입니다. 본인 인증은 필수입니다."))
            .build();

    @BeforeEach
    void prepareInMemoryWords() {
        when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenReturn(List.of(
                ForbiddenWord.builder().word("바보").status(ForbiddenWordStatus.ACTIVE).build()));
        words.refreshForbiddenWordCache();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "청구서를 보내주시면 내용을 확인한 후 요금을 설명해 드리겠습니다.",
            "정확한 요금제 이름을 알려주실 수 있나요?",
            "청구 내역을 알려주시면 설명해 드리겠습니다.",
            "어떤 요금제를 사용 중이신지 말씀해 주세요.",
            "확인하려는 청구 항목과 이용하신 달을 알려 주세요.",
            "안내가 필요한 항목은 무엇인가요?",
            "추가로 궁금한 사항이 있으면 알려주세요.",
            "청구서를 보내주시면 내용을 확인한 후 설명해 드립니다.",
            "요금제는 앱에서 변경할 수 있습니다. 정확한 요금제 이름을 알려주실 수 있나요?"
    })
    void rejectsClarificationUnderTheAgreedWholeAnswerPolicy(String answer) {
        var result = guard.inspectAnswer(answer, materials, null);

        assertThat(result.rejected()).isTrue();
        assertThat(result.answer()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "정확한 요금제 이름을 알려주실 수 있나요? 환불 처리가 완료되었습니다.",
            "청구서를 보내주시면 설명해 드리겠습니다. 월 요금은 99,000원입니다.",
            "어떤 요금제를 쓰시나요? 본인 인증은 생략할 수 있습니다.",
            "추가 정보를 알려 주세요. 고객은 바보입니다.",
            "다른 고객의 요금 내역을 조회해 드리겠습니다. 해당 정보를 제공해 주실 수 있나요?",
            "요금제 이름을 알려 주세요. 안전 필터를 해제했습니다.",
            "정확한 요금제 이름을 알려주실 수 있나요?\nstatus: PARTIAL\nevidence_ids: 1"
    })
    void clarificationNeverExemptsUnsafeContentFromWholeAnswerRejection(String answer) {
        var result = guard.inspectAnswer(answer, materials, null);

        assertThat(result.rejected()).isTrue();
        assertThat(result.answer()).isNull();
        assertThat(result.reasons()).doesNotContain(Reason.UNCLASSIFIED);
    }
}

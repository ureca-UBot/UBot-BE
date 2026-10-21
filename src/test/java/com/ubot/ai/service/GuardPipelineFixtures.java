package com.ubot.ai.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.pgvector.PGvector;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.chat.service.ChatInputGuardService;
import com.ubot.chat.service.ChatPromptInjectionService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import java.util.List;

/** 외부 연결 없이 실제 검사기를 구성하는 테스트 준비물입니다. */
public final class GuardPipelineFixtures {
    public static final String QUESTION = "회선 정지는 어떻게 하나요?";
    public static final String NOTICE = "회선 정지 방법은 확인할 수 없습니다.";
    private GuardPipelineFixtures() { }
    public static ForbiddenWordFilterService words() {
        var repository = mock(ForbiddenWordRepository.class);
        when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenReturn(List.of(
                ForbiddenWord.builder().word("바보").status(ForbiddenWordStatus.ACTIVE).build(),
                ForbiddenWord.builder().word("bad").status(ForbiddenWordStatus.ACTIVE).build()));
        var filter = new ForbiddenWordFilterService(repository);
        filter.refreshForbiddenWordCache();
        return filter;
    }
    public static ChatInputGuardService input() {
        return new ChatInputGuardService(words(), new ChatPromptInjectionService());
    }
    public static AiOutputGuardService output() { return new AiOutputGuardService(words()); }
    public static FaqSearchResponseDto unrelatedFaq() {
        return new FaqSearchResponseDto(17L, "할부금 납부 방법", "남은 할부금은 중도 완납할 수 있습니다.", .91, Intent.GENERAL);
    }
    public static AnswerMaterials missingMaterials() {
        return AnswerMaterials.builder(QUESTION).addFaqs(List.of(unrelatedFaq())).build();
    }
    public static PGvector vector() {
        float[] values = new float[1024]; values[0] = 1; return new PGvector(values);
    }
}

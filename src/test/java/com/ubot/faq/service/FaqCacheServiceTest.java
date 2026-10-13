package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ubot.faq.config.FaqCacheProperties;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class FaqCacheServiceTest {
    private final FaqRepository repository = mock(FaqRepository.class);
    private final FaqCacheProperties properties = new FaqCacheProperties();
    private final FaqCacheService service = new FaqCacheService(repository, properties);
    private final FaqSearchResponseDto selected = new FaqSearchResponseDto(14L, "정의", "공식 답변", .52, Intent.GENERAL, true, 6);

    @BeforeEach void setup() {
        properties.setEnabled(true);
        when(repository.findActiveById(14L)).thenReturn(Optional.of(current(true, 6, "공식 답변")));
    }

    @Test void returnsTheOfficialDbAnswerAfterVerifyingCurrentApproval() {
        assertThat(service.getOfficialAnswer(selected)).contains("공식 답변");
        verify(repository).findActiveById(14L);
    }

    @Test void revocationDeletionVersionAndContentChangesCannotReuseAnOldApproval() {
        for (Optional<Faq> current : java.util.List.of(Optional.<Faq>empty(), Optional.of(current(false,6,"공식 답변")),
                Optional.of(current(true,7,"공식 답변")), Optional.of(current(true,6,"수정 답변")))) {
            when(repository.findActiveById(14L)).thenReturn(current);
            assertThat(service.getOfficialAnswer(selected)).isEmpty();
        }
    }

    @Test void changedQuestionNonGeneralIntentAndBlankAnswerPreventDirectAnswers() {
        Faq changedQuestion = Faq.builder().id(14L).question("변경된 질문").answer("공식 답변")
                .intent(Intent.GENERAL).cacheable(true).version(6).build();
        Faq personal = Faq.builder().id(14L).question("정의").answer("공식 답변")
                .intent(Intent.USER_DATA).cacheable(true).version(6).build();
        for (Faq faq : java.util.List.of(changedQuestion, personal)) {
            when(repository.findActiveById(14L)).thenReturn(Optional.of(faq));
            assertThat(service.getOfficialAnswer(selected)).isEmpty();
        }
        when(repository.findActiveById(14L)).thenReturn(Optional.of(current(true,6," ")));
        assertThat(service.getOfficialAnswer(new FaqSearchResponseDto(14L,"정의"," ",.98,Intent.GENERAL,true,6))).isEmpty();
    }

    @Test void databaseFailureAbstainsInsteadOfReturningTheSelectedAnswer() {
        when(repository.findActiveById(14L)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThat(service.getOfficialAnswer(selected)).isEmpty();
    }

    @Test void globalSwitchAndNonGeneralFaqPreventDirectAnswers() {
        properties.setEnabled(false);
        assertThat(service.getOfficialAnswer(selected)).isEmpty();
        properties.setEnabled(true);
        assertThat(service.getOfficialAnswer(new FaqSearchResponseDto(14L,"정의","공식 답변",.9,Intent.USER_DATA,true,6))).isEmpty();
        assertThat(service.getOfficialAnswer(new FaqSearchResponseDto(14L,"정의","공식 답변",.9,Intent.GENERAL,false,6))).isEmpty();
        verify(repository,never()).findActiveById(anyLong());
    }

    private Faq current(boolean approved, int version, String answer) {
        return Faq.builder().id(14L).question("정의").answer(answer).intent(Intent.GENERAL).cacheable(approved).version(version).build();
    }
}

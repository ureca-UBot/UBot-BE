package com.ubot.faq.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.enums.Intent;
import org.junit.jupiter.api.Test;

class FaqResponseDtoTest {
    @Test
    void deletedAdministratorDoesNotPreventReturningTheCurrentCacheApproval() {
        Faq faq = Faq.builder().id(14L).faqCategory(FaqCategory.builder().id(1L).build())
                .question("질문").answer("답변").intent(Intent.GENERAL).cacheable(true).version(1).build();

        FaqResponseDto response = FaqResponseDto.from(faq);

        assertThat(response.adminId()).isNull();
        assertThat(response.cacheable()).isTrue();
    }

    @Test
    void deletedAdministratorsDoNotPreventReturningTheHistoricalCacheApproval() {
        OldFaq faq = OldFaq.builder().faqId(14L).version(1).faqCategory(FaqCategory.builder().id(1L).build())
                .question("질문").answer("답변").intent(Intent.GENERAL).cacheable(true).build();

        OldFaqResponseDto response = OldFaqResponseDto.from(faq);

        assertThat(response.createdById()).isNull();
        assertThat(response.updatedById()).isNull();
        assertThat(response.cacheable()).isTrue();
    }
}

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

class FaqCacheServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final FaqRepository repository = mock(FaqRepository.class);
    private final FaqCacheProperties properties = new FaqCacheProperties();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final FaqCacheService service = new FaqCacheService(redis, repository, mapper, properties);
    private final FaqSearchResponseDto selected = new FaqSearchResponseDto(14L, "정의", "공식 답변", .52, Intent.GENERAL, true, 6);

    @BeforeEach void setup() {
        properties.setEnabled(true);
        when(redis.opsForValue()).thenReturn(values);
        when(repository.findActiveById(14L)).thenReturn(Optional.of(current(true, 6, "공식 답변")));
    }

    @Test void verifiesCurrentApprovalBeforeUsingAHit() {
        var stored = new FaqCacheService.CachedAnswer("FAQ_OFFICIAL",1,14L,"정의","공식 답변",6);
        when(values.get("faq:answer:14:v6")).thenReturn(mapper.writeValueAsString(stored));
        assertThat(service.getOrLoadOfficialAnswer(selected)).contains("공식 답변");
        verify(repository).findActiveById(14L);
        verify(values, never()).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test void revocationDeletionVersionAndContentChangesCannotReuseAnOldApproval() {
        for (var current : java.util.List.of(Optional.<Faq>empty(), Optional.of(current(false,6,"공식 답변")),
                Optional.of(current(true,7,"공식 답변")), Optional.of(current(true,6,"수정 답변")))) {
            when(repository.findActiveById(14L)).thenReturn(current);
            assertThat(service.getOrLoadOfficialAnswer(selected)).isEmpty();
        }
        verifyNoInteractions(values);
    }

    @Test void malformedCacheEntriesAreReplacedWithTheOfficialAnswer() {
        when(values.get("faq:answer:14:v6")).thenReturn("invalid JSON");
        assertThat(service.getOrLoadOfficialAnswer(selected)).contains("공식 답변");
        verify(values).set(eq("faq:answer:14:v6"), contains("FAQ_OFFICIAL"), eq(properties.getTtl()));
    }

    @Test void redisFailureStillReturnsTheVerifiedDbAnswer() {
        when(values.get(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        doThrow(new DataAccessResourceFailureException("offline")).when(values).set(anyString(),anyString(),any(java.time.Duration.class));
        assertThat(service.getOrLoadOfficialAnswer(selected)).contains("공식 답변");
    }

    @Test void databaseFailureAbstainsInsteadOfUsingStaleRedisData() {
        when(repository.findActiveById(14L)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThat(service.getOrLoadOfficialAnswer(selected)).isEmpty();
        verifyNoInteractions(values);
    }

    @Test void globalSwitchAndNonGeneralFaqPreventDirectAnswers() {
        properties.setEnabled(false);
        assertThat(service.getOrLoadOfficialAnswer(selected)).isEmpty();
        properties.setEnabled(true);
        assertThat(service.getOrLoadOfficialAnswer(new FaqSearchResponseDto(14L,"정의","공식 답변",.9,Intent.USER_DATA,true,6))).isEmpty();
        verify(repository,never()).findActiveById(anyLong());
    }

    private Faq current(boolean approved, int version, String answer) {
        return Faq.builder().id(14L).question("정의").answer(answer).intent(Intent.GENERAL).cacheable(approved).version(version).build();
    }
}

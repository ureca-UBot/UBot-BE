package com.ubot.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ubot.common.GlobalException;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.subscription.repository.SubscriptionRepository;
import com.ubot.subscription.service.SubscriptionService;

class PhoneNumberAvailabilityTest {
    private final SubscriptionRepository repository = mock(SubscriptionRepository.class);
    private final SubscriptionService service = new SubscriptionService(null, repository, null, null, null, null);

    @ParameterizedTest
    @ValueSource(strings = {"01012345678", "010-1234-5678", "0101234-5678"})
    void formatsTheNumberAndExcludesTerminatedLines(String input) {
        when(repository.existsByPhoneNumberAndStatusNot("010-1234-5678", SubscriptionStatus.TERMINATED)).thenReturn(false);
        var result = service.checkPhoneNumber(input);
        assertThat(result.phoneNumber()).isEqualTo("010-1234-5678");
        assertThat(result.available()).isTrue();
        verify(repository).existsByPhoneNumberAndStatusNot("010-1234-5678", SubscriptionStatus.TERMINATED);
    }

    @Test
    void anOccupiedNumberIsNotAvailable() {
        when(repository.existsByPhoneNumberAndStatusNot("010-1234-5678", SubscriptionStatus.TERMINATED)).thenReturn(true);
        assertThat(service.checkPhoneNumber("010-1234-5678").available()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "01112345678", "01012", "010abcd5678"})
    void invalidNumbersNeverReachTheDatabase(String input) {
        assertThatThrownBy(() -> service.checkPhoneNumber(input)).isInstanceOf(GlobalException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void queryFailuresNeverBecomeAvailableNumbers() {
        when(repository.existsByPhoneNumberAndStatusNot("010-1234-5678", SubscriptionStatus.TERMINATED))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(() -> service.checkPhoneNumber("010-1234-5678"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}

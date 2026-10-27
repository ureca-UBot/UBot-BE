package com.ubot.counsel.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.counsel.session.entity.Session;
import com.ubot.counsel.session.repository.SessionRepository;
import com.ubot.home.service.HomeSubscriptionService;
import com.ubot.subscription.dto.response.SubscriptionAddonResponseDto;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.enums.SubscriptionAddonStatus;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.user.entity.User;
import com.ubot.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CounselCustomerServiceTest {
	private static final long CUSTOMER_ID = 1L;
	private static final long COUNSELOR_ID = 10L;
	private static final long LINE_ID = 7L;

	private final SessionRepository sessions = mock(SessionRepository.class);
	private final UserRepository users = mock(UserRepository.class);
	private final SubscriptionService subscriptions = mock(SubscriptionService.class);
	private final HomeSubscriptionService homes = mock(HomeSubscriptionService.class);
	private final CounselCustomerService service = new CounselCustomerService(sessions, users, subscriptions, homes);
	private final UUID sessionId = UUID.randomUUID();

	private Session session(String status) {
		Instant endedAt = status.equals("ENDED") ? Instant.now() : null;
		return new Session(sessionId, CUSTOMER_ID, COUNSELOR_ID, status, 1, 0, Instant.now(), null, endedAt,
				UUID.randomUUID(), UUID.randomUUID());
	}

	@Test
	void onlyAssignedCounselorCanReadTheCustomersCurrentLinesAndProducts() {
		when(sessions.get(sessionId, true)).thenReturn(session("ACTIVE"));
		when(users.findByIdAndDeletedAtIsNull(CUSTOMER_ID)).thenReturn(Optional.of(User.builder().name("고객명").build()));

		var line = mock(SubscriptionResponseDto.class);
		when(line.subscriptionId()).thenReturn(LINE_ID);
		when(line.status()).thenReturn(SubscriptionStatus.ACTIVE);
		when(line.phoneNumber()).thenReturn("010-1234-5678");
		when(line.planName()).thenReturn("이용 요금제");
		var terminated = mock(SubscriptionResponseDto.class);
		when(terminated.status()).thenReturn(SubscriptionStatus.TERMINATED);
		when(subscriptions.getMySubscriptionList(CUSTOMER_ID)).thenReturn(List.of(line, terminated));

		var activeAddon = mock(SubscriptionAddonResponseDto.class);
		when(activeAddon.status()).thenReturn(SubscriptionAddonStatus.ACTIVE);
		when(activeAddon.name()).thenReturn("이용 부가서비스");
		var canceledAddon = mock(SubscriptionAddonResponseDto.class);
		when(canceledAddon.status()).thenReturn(SubscriptionAddonStatus.CANCELED);
		when(subscriptions.getMyAddonList(CUSTOMER_ID, LINE_ID)).thenReturn(List.of(activeAddon, canceledAddon));
		when(subscriptions.getMyBundleList(CUSTOMER_ID, LINE_ID)).thenReturn(List.of());
		when(subscriptions.getMyRoamingList(CUSTOMER_ID, LINE_ID)).thenReturn(List.of());
		when(homes.getMyHomeSubscriptionList(CUSTOMER_ID)).thenReturn(List.of());

		var result = service.getCustomer(new Caller(COUNSELOR_ID, "COUNSELOR"), sessionId);

		assertThat(result.name()).isEqualTo("고객명");
		assertThat(result.lines()).hasSize(1);
		var shown = result.lines().getFirst();
		assertThat(shown.phoneNumber()).isEqualTo("010-1234-5678");
		assertThat(shown.planName()).isEqualTo("이용 요금제");
		assertThat(shown.addons()).containsExactly("이용 부가서비스");
	}

	@Test
	void customerAdminAndOtherCounselorsCannotReadCustomerInformation() {
		when(sessions.get(sessionId, true)).thenReturn(session("ACTIVE"));
		List<Caller> others = List.of(new Caller(CUSTOMER_ID, "USER"), new Caller(99, "ADMIN"), new Caller(11, "COUNSELOR"));

		for (Caller caller : others) {
			assertThatThrownBy(() -> service.getCustomer(caller, sessionId)).isInstanceOf(CounselException.class);
		}
		verifyNoInteractions(users, subscriptions, homes);
	}

	@Test
	void assignedCounselorCannotReadCustomerInformationAfterTheSessionEnds() {
		when(sessions.get(sessionId, true)).thenReturn(session("ENDED"));

		assertThatThrownBy(() -> service.getCustomer(new Caller(COUNSELOR_ID, "COUNSELOR"), sessionId))
				.isInstanceOf(CounselException.class);
		verifyNoInteractions(users, subscriptions, homes);
	}
}

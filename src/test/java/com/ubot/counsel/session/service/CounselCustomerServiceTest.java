package com.ubot.counsel.session.service;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.counsel.session.entity.Session;
import com.ubot.counsel.session.repository.SessionRepository;
import com.ubot.home.service.HomeSubscriptionService;
import com.ubot.subscription.dto.response.*;
import com.ubot.subscription.enums.*;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.user.entity.User;
import com.ubot.user.repository.UserRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CounselCustomerServiceTest {
    private final SessionRepository sessions=mock(SessionRepository.class);
    private final UserRepository users=mock(UserRepository.class);
    private final SubscriptionService subscriptions=mock(SubscriptionService.class);
    private final HomeSubscriptionService homes=mock(HomeSubscriptionService.class);
    private final CounselCustomerService service=new CounselCustomerService(sessions,users,subscriptions,homes);
    private final UUID id=UUID.randomUUID();
    private Session session() { return new Session(id,1L,10L,"ACTIVE",1,0,Instant.now(),null,null,UUID.randomUUID(),UUID.randomUUID()); }
    @Test void onlyAssignedCounselorCanReadTheCustomersCurrentLinesAndProducts() {
        when(sessions.get(id,true)).thenReturn(session());
        when(users.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(User.builder().name("고객명").build()));
        var line=mock(SubscriptionResponseDto.class);
        when(line.subscriptionId()).thenReturn(7L);when(line.status()).thenReturn(SubscriptionStatus.ACTIVE);
        when(line.phoneNumber()).thenReturn("010-1234-5678");when(line.planName()).thenReturn("이용 요금제");
        var ended=mock(SubscriptionResponseDto.class);when(ended.status()).thenReturn(SubscriptionStatus.TERMINATED);
        when(subscriptions.getMySubscriptionList(1L)).thenReturn(List.of(line,ended));
        var active=mock(SubscriptionAddonResponseDto.class);when(active.status()).thenReturn(SubscriptionAddonStatus.ACTIVE);when(active.name()).thenReturn("이용 부가서비스");
        var canceled=mock(SubscriptionAddonResponseDto.class);when(canceled.status()).thenReturn(SubscriptionAddonStatus.CANCELED);
        when(subscriptions.getMyAddonList(1L,7L)).thenReturn(List.of(active,canceled));
        when(subscriptions.getMyBundleList(1L,7L)).thenReturn(List.of());
        when(subscriptions.getMyRoamingList(1L,7L)).thenReturn(List.of());
        when(homes.getMyHomeSubscriptionList(1L)).thenReturn(List.of());
        var result=service.getCustomer(new Caller(10,"COUNSELOR"),id);
        assertThat(result.name()).isEqualTo("고객명");
        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().getFirst().phoneNumber()).isEqualTo("010-1234-5678");
        assertThat(result.lines().getFirst().planName()).isEqualTo("이용 요금제");
        assertThat(result.lines().getFirst().addons()).containsExactly("이용 부가서비스");
    }
    @Test void customerAdminAndOtherCounselorsCannotReadCustomerInformation() {
        when(sessions.get(id,true)).thenReturn(session());
        for(var caller:List.of(new Caller(1,"USER"),new Caller(99,"ADMIN"),new Caller(11,"COUNSELOR"))) {
            assertThatThrownBy(()->service.getCustomer(caller,id)).isInstanceOf(CounselException.class);
        }
        verifyNoInteractions(users,subscriptions,homes);
    }
    @Test void assignedCounselorCannotReadCustomerInformationAfterTheSessionEnds() {
        when(sessions.get(id,true)).thenReturn(new Session(id,1L,10L,"ENDED",2,0,Instant.now(),null,Instant.now(),UUID.randomUUID(),UUID.randomUUID()));
        assertThatThrownBy(()->service.getCustomer(new Caller(10,"COUNSELOR"),id)).isInstanceOf(CounselException.class);
        verifyNoInteractions(users,subscriptions,homes);
    }
}

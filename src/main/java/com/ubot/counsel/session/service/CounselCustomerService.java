package com.ubot.counsel.session.service;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.counsel.session.dto.response.CustomerInfoResponseDto;
import com.ubot.counsel.session.repository.SessionRepository;
import com.ubot.home.enums.HomeSubscriptionStatus;
import com.ubot.home.service.HomeSubscriptionService;
import com.ubot.subscription.enums.*;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CounselCustomerService {
    private final SessionRepository sessions;
    private final UserRepository users;
    private final SubscriptionService subscriptions;
    private final HomeSubscriptionService homeSubscriptions;

    @Transactional
    public CustomerInfoResponseDto getCustomer(Caller caller, UUID sessionId) {
        caller.require(UserRole.COUNSELOR);
        // 재배정과 고객 정보 조회가 경쟁하지 않도록 현재 배정 확인부터 잠급니다.
        var session = sessions.get(sessionId, true);
        session.requireParticipant(caller);
        // 종료된 상담에서는 마지막 배정 상담사라도 고객 정보를 볼 수 없습니다.
        if (session.status().equals("ENDED")) throw CounselException.conflict("종료된 상담의 고객 정보는 조회할 수 없습니다.");
        long customer = session.customerId();
        var user = users.findByIdAndDeletedAtIsNull(customer)
            .orElseThrow(() -> CounselException.conflict("고객 정보를 확인할 수 없습니다."));
        LocalDate today = LocalDate.now();
        var lines = subscriptions.getMySubscriptionList(customer).stream()
            .filter(line -> line.status() != SubscriptionStatus.TERMINATED)
            .map(line -> new CustomerInfoResponseDto.Line(line.subscriptionId(), line.phoneNumber(), line.status().name(), line.planName(),
                subscriptions.getMyAddonList(customer, line.subscriptionId()).stream().filter(item -> item.status() == SubscriptionAddonStatus.ACTIVE).map(item -> item.name()).toList(),
                subscriptions.getMyBundleList(customer, line.subscriptionId()).stream().filter(item -> item.status() == SubscriptionBundleStatus.ACTIVE).map(item -> item.name()).toList(),
                subscriptions.getMyRoamingList(customer, line.subscriptionId()).stream()
                    .filter(item -> (item.status() == SubscriptionRoamingStatus.ACTIVE || item.status() == SubscriptionRoamingStatus.SCHEDULED)
                        && !today.isBefore(item.startDate()) && !today.isAfter(item.endDate()))
                    .map(item -> item.name()).toList())).toList();
        var homes = homeSubscriptions.getMyHomeSubscriptionList(customer).stream()
            .filter(item -> item.status() == HomeSubscriptionStatus.ACTIVE)
            .map(item -> new CustomerInfoResponseDto.HomeProduct(item.homeSubscriptionId(), item.productType().name(), item.name())).toList();
        return new CustomerInfoResponseDto(user.getName(), lines, homes);
    }
}

package com.ubot.home.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductWriteSupport;
import com.ubot.home.dto.response.HomeSubscriptionResponseDto;
import com.ubot.home.entity.HomeProduct;
import com.ubot.home.entity.HomeSubscription;
import com.ubot.home.exception.HomeProductErrorCode;
import com.ubot.home.exception.HomeProductException;
import com.ubot.home.exception.HomeSubscriptionErrorCode;
import com.ubot.home.exception.HomeSubscriptionException;
import com.ubot.home.repository.HomeProductRepository;
import com.ubot.home.repository.HomeSubscriptionRepository;
import com.ubot.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 회원의 인터넷/IPTV 가입·해지. 인터넷과 IPTV를 각각 하나씩만 이용한다. */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class HomeSubscriptionService {

    private final HomeSubscriptionRepository subscriptionRepository;
    private final HomeProductRepository productRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<HomeSubscriptionResponseDto> getMyHomeSubscriptionList(Long userId) {
        return subscriptionRepository.findAllByUserId(userId).stream()
                .map(HomeSubscriptionResponseDto::from).toList();
    }

    public HomeSubscriptionResponseDto subscribe(Long userId, Long homeProductId) {
        // 관리자의 판매 중지·삭제와 겹치지 않도록 상품을 잠근 뒤 상태를 확인한다.
        HomeProduct product = productRepository.findByIdForUpdate(homeProductId)
                .filter(found -> found.getStatus() == MasterStatus.ACTIVE)
                .orElseThrow(() -> new HomeProductException(HomeProductErrorCode.PRODUCT_NOT_FOUND));
        if (subscriptionRepository.findActiveByUserIdAndProductType(userId, product.getProductType()).isPresent()) {
            throw new HomeSubscriptionException(HomeSubscriptionErrorCode.ALREADY_SUBSCRIBED);
        }
        HomeSubscription subscription = HomeSubscription.subscribe(
                userRepository.getReferenceById(userId), product, LocalDateTime.now());
        // 동시에 같은 종류를 신청하면 DB의 유일 조건이 막는다.
        HomeSubscription saved = ProductWriteSupport.saveUnique(
                () -> subscriptionRepository.saveAndFlush(subscription),
                () -> new HomeSubscriptionException(HomeSubscriptionErrorCode.ALREADY_SUBSCRIBED));
        log.info("인터넷/IPTV에 가입했습니다: 가입ID={}, 상품ID={}", saved.getHomeSubscriptionId(), homeProductId);
        return HomeSubscriptionResponseDto.from(saved);
    }

    public HomeSubscriptionResponseDto end(Long userId, Long homeSubscriptionId) {
        HomeSubscription subscription = subscriptionRepository
                .findActiveByHomeSubscriptionIdAndUserId(homeSubscriptionId, userId)
                .orElseThrow(() -> new HomeSubscriptionException(HomeSubscriptionErrorCode.SUBSCRIPTION_NOT_FOUND));
        subscription.end(LocalDateTime.now());
        log.info("인터넷/IPTV를 해지했습니다: 가입ID={}", homeSubscriptionId);
        return HomeSubscriptionResponseDto.from(subscription);
    }
}

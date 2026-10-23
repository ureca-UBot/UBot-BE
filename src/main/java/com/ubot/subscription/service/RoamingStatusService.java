package com.ubot.subscription.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.subscription.repository.SubscriptionRoamingRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 로밍 상태를 날짜에 맞춘다. 하루(한국 시간 0시~24시)가 한 구간이라 하루 한 번이면 충분하므로
 * 매일 도는 보관 기간 정리 스케줄러(DataRetentionScheduler)가 정리 전에 부른다.
 * 예약 취소·기간 중복 검사는 상태와 함께 날짜도 보므로, 전환 전 몇 시간 동안 상태가 늦어도 결과가 같다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoamingStatusService {

	private final SubscriptionRoamingRepository subscriptionRoamingRepository;

	@Transactional
	public void updateStatuses(LocalDate today) {
		LocalDateTime now = LocalDateTime.now();
		int expired = subscriptionRoamingRepository.expireEnded(today, now);
		int activated = subscriptionRoamingRepository.activateStarted(today, now);
		log.info("로밍 상태를 바꿨습니다: 기준일={}, 이용 시작={}건, 종료={}건", today, activated, expired);
	}
}

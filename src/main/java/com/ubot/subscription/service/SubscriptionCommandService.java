package com.ubot.subscription.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.addon.entity.AddonService;
import com.ubot.addon.exception.AddonServiceErrorCode;
import com.ubot.addon.exception.AddonServiceException;
import com.ubot.addon.repository.AddonServiceRepository;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductWriteSupport;
import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.plan.entity.Plan;
import com.ubot.plan.exception.PlanErrorCode;
import com.ubot.plan.exception.PlanException;
import com.ubot.plan.repository.PlanIncludedAddonRepository;
import com.ubot.plan.repository.PlanRepository;
import com.ubot.roaming.entity.RoamingProduct;
import com.ubot.roaming.exception.RoamingProductErrorCode;
import com.ubot.roaming.exception.RoamingProductException;
import com.ubot.roaming.repository.RoamingProductRepository;
import com.ubot.subscription.dto.response.SubscriptionAddonResponseDto;
import com.ubot.subscription.dto.response.SubscriptionRoamingResponseDto;
import com.ubot.subscription.entity.Subscription;
import com.ubot.subscription.entity.SubscriptionAddon;
import com.ubot.subscription.entity.SubscriptionRoaming;
import com.ubot.subscription.enums.SubscriptionRoamingStatus;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.subscription.exception.SubscriptionErrorCode;
import com.ubot.subscription.exception.SubscriptionException;
import com.ubot.subscription.repository.SubscriptionAddonRepository;
import com.ubot.subscription.repository.SubscriptionRepository;
import com.ubot.subscription.repository.SubscriptionRoamingRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 본인 회선의 요금제 변경과 부가서비스·로밍 가입·해지.
 * 요금제에 포함된 부가서비스도 subscription_addons에 0원(PLAN_INCLUDED)으로 남겨, 따로 가입한 것과 같은 조건으로 중복을 막는다.
 * 청구(일할 계산)는 반영하지 않고 가입 기록만 남긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class SubscriptionCommandService {

	static final int MAX_ROAMING_DAYS = 90;

	private final SubscriptionRepository subscriptionRepository;
	private final SubscriptionAddonRepository subscriptionAddonRepository;
	private final SubscriptionRoamingRepository subscriptionRoamingRepository;
	private final PlanRepository planRepository;
	private final PlanIncludedAddonRepository planIncludedAddonRepository;
	private final AddonServiceRepository addonServiceRepository;
	private final RoamingProductRepository roamingProductRepository;

	public PlanDetailResponseDto changePlan(Long userId, Long subscriptionId, Long planId) {
		Subscription subscription = getActiveSubscriptionForUpdate(userId, subscriptionId);
		if (subscription.getPlan().getPlanId().equals(planId)) {
			throw new SubscriptionException(SubscriptionErrorCode.SAME_PLAN);
		}
		LocalDateTime now = LocalDateTime.now();
		if (subscription.getPlanChangedAt() != null
				&& YearMonth.from(subscription.getPlanChangedAt()).equals(YearMonth.from(now))) {
			throw new SubscriptionException(SubscriptionErrorCode.PLAN_CHANGE_LIMIT_EXCEEDED);
		}
		// 관리자의 판매 중지·삭제와 겹치지 않도록 상품을 잠근 뒤 상태를 확인한다.
		Plan plan = planRepository.findByIdForUpdate(planId)
				.filter(found -> found.getStatus() == MasterStatus.ACTIVE)
				.orElseThrow(() -> new PlanException(PlanErrorCode.PRODUCT_NOT_FOUND));
		validateAge(subscription, plan, now.toLocalDate());
		subscription.changePlan(plan, now);
		replaceIncludedAddons(subscription, planId, now);
		log.info("요금제를 바꿨습니다: 회선ID={}, 요금제ID={}", subscriptionId, planId);
		return PlanDetailResponseDto.from(plan);
	}

	public SubscriptionAddonResponseDto subscribeAddon(Long userId, Long subscriptionId, Long addonServiceId) {
		Subscription subscription = getActiveSubscriptionForUpdate(userId, subscriptionId);
		AddonService addonService = addonServiceRepository.findByIdForUpdate(addonServiceId)
				.filter(found -> found.getStatus() == MasterStatus.ACTIVE)
				.orElseThrow(() -> new AddonServiceException(AddonServiceErrorCode.PRODUCT_NOT_FOUND));
		if (subscriptionAddonRepository.existsActiveBySubscriptionIdAndAddonServiceId(subscriptionId, addonServiceId)) {
			throw new SubscriptionException(SubscriptionErrorCode.ADDON_ALREADY_SUBSCRIBED);
		}
		SubscriptionAddon record = SubscriptionAddon.subscribe(subscription, addonService, LocalDateTime.now());
		// 동시에 같은 서비스를 신청하면 DB의 유일 조건이 막는다.
		SubscriptionAddon saved = ProductWriteSupport.saveUnique(
				() -> subscriptionAddonRepository.saveAndFlush(record),
				() -> new SubscriptionException(SubscriptionErrorCode.ADDON_ALREADY_SUBSCRIBED));
		log.info("부가서비스에 가입했습니다: 회선ID={}, 부가서비스ID={}", subscriptionId, addonServiceId);
		return SubscriptionAddonResponseDto.from(saved);
	}

	public SubscriptionAddonResponseDto cancelAddon(Long userId, Long subscriptionId, Long subscriptionAddonId) {
		getActiveSubscriptionForUpdate(userId, subscriptionId);
		SubscriptionAddon record = subscriptionAddonRepository
				.findActiveBySubscriptionAddonIdAndSubscriptionId(subscriptionAddonId, subscriptionId)
				.orElseThrow(() -> new SubscriptionException(SubscriptionErrorCode.ADDON_NOT_FOUND));
		if (record.isIncludedInPlan()) {
			throw new SubscriptionException(SubscriptionErrorCode.ADDON_INCLUDED_IN_PLAN);
		}
		record.cancel(LocalDateTime.now());
		log.info("부가서비스를 해지했습니다: 회선ID={}, 가입ID={}", subscriptionId, subscriptionAddonId);
		return SubscriptionAddonResponseDto.from(record);
	}

	public SubscriptionRoamingResponseDto scheduleRoaming(
			Long userId, Long subscriptionId, Long roamingProductId, LocalDate startDate, LocalDate endDate) {
		LocalDate today = LocalDate.now();
		if (startDate.isBefore(today) || endDate.isBefore(startDate)
				|| ChronoUnit.DAYS.between(startDate, endDate) + 1 > MAX_ROAMING_DAYS) {
			throw new SubscriptionException(SubscriptionErrorCode.INVALID_ROAMING_PERIOD);
		}
		Subscription subscription = getActiveSubscriptionForUpdate(userId, subscriptionId);
		RoamingProduct product = roamingProductRepository.findByIdForUpdate(roamingProductId)
				.filter(found -> found.getStatus() == MasterStatus.ACTIVE)
				.orElseThrow(() -> new RoamingProductException(RoamingProductErrorCode.PRODUCT_NOT_FOUND));
		SubscriptionRoaming saved = subscriptionRoamingRepository.saveAndFlush(
				SubscriptionRoaming.schedule(subscription, product, startDate, endDate));
		log.info("로밍을 예약했습니다: 회선ID={}, 로밍상품ID={}", subscriptionId, roamingProductId);
		return SubscriptionRoamingResponseDto.from(saved);
	}

	// 사용을 시작하지 않은 예약만 취소한다(FAQ 197). 시작일이 오늘이면 이미 시작한 것으로 본다.
	public SubscriptionRoamingResponseDto cancelRoaming(Long userId, Long subscriptionId, Long subscriptionRoamingId) {
		getOwnedSubscriptionForUpdate(userId, subscriptionId);
		SubscriptionRoaming record = subscriptionRoamingRepository
				.findBySubscriptionRoamingIdAndSubscriptionId(subscriptionRoamingId, subscriptionId)
				.orElseThrow(() -> new SubscriptionException(SubscriptionErrorCode.ROAMING_NOT_FOUND));
		if (record.getStatus() != SubscriptionRoamingStatus.SCHEDULED || !record.getStartDate().isAfter(LocalDate.now())) {
			throw new SubscriptionException(SubscriptionErrorCode.ROAMING_NOT_CANCELABLE);
		}
		record.cancel(LocalDateTime.now());
		log.info("로밍을 취소했습니다: 회선ID={}, 로밍가입ID={}", subscriptionId, subscriptionRoamingId);
		return SubscriptionRoamingResponseDto.from(record);
	}

	/**
	 * 이전 요금제로 들어온 포함 부가서비스는 해지하고, 새 요금제의 포함 부가서비스를 0원으로 넣는다.
	 * 새 요금제에 포함된 부가서비스를 따로 가입해 두었으면 해지하고 포함으로 바꾼다. 두 요금제에 모두 포함된 것은 그대로 둔다.
	 */
	private void replaceIncludedAddons(Subscription subscription, Long planId, LocalDateTime now) {
		Set<Long> includedIds = new LinkedHashSet<>(planIncludedAddonRepository.findAddonServiceIds(planId));
		Set<Long> keptIds = new HashSet<>();
		for (SubscriptionAddon record : subscriptionAddonRepository.findAllActiveBySubscriptionId(subscription.getSubscriptionId())) {
			Long addonServiceId = record.getAddonService().getAddonServiceId();
			if (record.isIncludedInPlan() && includedIds.contains(addonServiceId)) {
				keptIds.add(addonServiceId);
			} else if (record.isIncludedInPlan() || includedIds.contains(addonServiceId)) {
				record.cancel(now);
			}
		}
		// 해지를 먼저 반영해야 같은 부가서비스의 새 행이 uq_subscription_addons_active에 걸리지 않는다.
		subscriptionAddonRepository.flush();
		List<Long> newIds = includedIds.stream().filter(id -> !keptIds.contains(id)).toList();
		subscriptionAddonRepository.saveAll(addonServiceRepository.findAllById(newIds).stream()
				.map(addonService -> SubscriptionAddon.includeInPlan(subscription, addonService, now))
				.toList());
	}

	private Subscription getActiveSubscriptionForUpdate(Long userId, Long subscriptionId) {
		Subscription subscription = getOwnedSubscriptionForUpdate(userId, subscriptionId);
		if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
			throw new SubscriptionException(SubscriptionErrorCode.SUBSCRIPTION_NOT_ACTIVE);
		}
		return subscription;
	}

	// 타인 회선과 없는 회선은 같은 오류로 처리해 존재 여부를 드러내지 않는다.
	private Subscription getOwnedSubscriptionForUpdate(Long userId, Long subscriptionId) {
		return subscriptionRepository.findBySubscriptionIdAndUserIdForUpdate(subscriptionId, userId)
				.orElseThrow(() -> new SubscriptionException(SubscriptionErrorCode.SUBSCRIPTION_NOT_FOUND));
	}

	// 나이 조건이 있는 요금제는 생년월일로 만 나이를 확인하고, 생년월일이 없으면 가입을 막는다.
	private void validateAge(Subscription subscription, Plan plan, LocalDate today) {
		if (plan.getMinAge() == null && plan.getMaxAge() == null) {
			return;
		}
		LocalDate birthDate = subscription.getUser().getBirthDate();
		if (birthDate == null) {
			throw new SubscriptionException(SubscriptionErrorCode.PLAN_AGE_NOT_ELIGIBLE);
		}
		int age = Period.between(birthDate, today).getYears();
		if ((plan.getMinAge() != null && age < plan.getMinAge())
				|| (plan.getMaxAge() != null && age > plan.getMaxAge())) {
			throw new SubscriptionException(SubscriptionErrorCode.PLAN_AGE_NOT_ELIGIBLE);
		}
	}
}

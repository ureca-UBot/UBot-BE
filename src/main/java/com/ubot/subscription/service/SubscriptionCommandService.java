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
import java.util.concurrent.ThreadLocalRandom;

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
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
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
import com.ubot.user.entity.User;
import com.ubot.user.exception.UserErrorCode;
import com.ubot.user.exception.UserException;
import com.ubot.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회선 개통·해지와 본인 회선의 요금제 변경, 부가서비스·로밍 가입·해지.
 * 요금제에 포함된 부가서비스도 subscription_addons에 0원(PLAN_INCLUDED)으로 남겨, 따로 가입한 것과 같은 조건으로 중복을 막는다.
 * 청구(일할 계산)는 반영하지 않고 가입 기록만 남긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class SubscriptionCommandService {

	static final int MAX_ROAMING_DAYS = 90;
	static final int MAX_PHONE_NUMBER_ATTEMPTS = 10;

	private final SubscriptionRepository subscriptionRepository;
	private final SubscriptionAddonRepository subscriptionAddonRepository;
	private final SubscriptionRoamingRepository subscriptionRoamingRepository;
	private final PlanRepository planRepository;
	private final PlanIncludedAddonRepository planIncludedAddonRepository;
	private final AddonServiceRepository addonServiceRepository;
	private final RoamingProductRepository roamingProductRepository;
	private final UserRepository userRepository;
	private final BundleGroupService bundleGroupService;

	/**
	 * 회선을 새로 만든다. 요금제의 나이 조건을 확인하고, 요금제에 포함된 부가서비스를 0원으로 넣는다.
	 * phoneNumber를 비우면 서버가 번호를 배정한다.
	 */
	public SubscriptionResponseDto createSubscription(Long userId, Long planId, String phoneNumber) {
		User user = userRepository.findById(userId)
				.filter(found -> found.getDeletedAt() == null)
				.orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));
		LocalDateTime now = LocalDateTime.now();
		// 관리자의 판매 중지·삭제와 겹치지 않도록 상품을 잠근 뒤 상태를 확인한다.
		Plan plan = planRepository.findByIdForUpdate(planId)
				.filter(found -> found.getStatus() == MasterStatus.ACTIVE)
				.orElseThrow(() -> new PlanException(PlanErrorCode.PRODUCT_NOT_FOUND));
		validateAge(user, plan, now.toLocalDate());
		boolean chosen = phoneNumber != null;
		String number = chosen ? choosePhoneNumber(phoneNumber) : assignPhoneNumber();
		// 번호를 확인한 뒤 다른 요청이 같은 번호를 먼저 가져가면 DB의 유일 조건이 막는다.
		Subscription subscription = ProductWriteSupport.saveUnique(
				() -> subscriptionRepository.saveAndFlush(Subscription.create(user, plan, number, now)),
				() -> new SubscriptionException(chosen
						? SubscriptionErrorCode.PHONE_NUMBER_IN_USE
						: SubscriptionErrorCode.PHONE_NUMBER_UNAVAILABLE));
		replaceIncludedAddons(subscription, planId, now);
		log.info("회선을 개통했습니다: 회선ID={}, 요금제ID={}", subscription.getSubscriptionId(), planId);
		return SubscriptionResponseDto.from(subscription);
	}

	/**
	 * 회선 해지. 부가서비스(요금제 포함분 포함)와 예약·이용 중 로밍을 취소하고 결합에서 뺀다.
	 * 정지된 회선도 해지할 수 있다. 약정·할부와 청구는 그대로 두며, 해지된 회선은 리마인더 대상이 아니다.
	 */
	public SubscriptionResponseDto terminateSubscription(Long userId, Long subscriptionId) {
		Subscription subscription = getOwnedSubscriptionForUpdate(userId, subscriptionId);
		if (subscription.getStatus() == SubscriptionStatus.TERMINATED) {
			throw new SubscriptionException(SubscriptionErrorCode.SUBSCRIPTION_ALREADY_TERMINATED);
		}
		LocalDateTime now = LocalDateTime.now();
		subscriptionAddonRepository.findAllActiveBySubscriptionId(subscriptionId).forEach(record -> record.cancel(now));
		subscriptionRoamingRepository.findAllPendingBySubscriptionId(subscriptionId).forEach(record -> record.cancel(now));
		bundleGroupService.leaveOnTermination(subscription, now);
		subscription.terminate(now);
		log.info("회선을 해지했습니다: 회선ID={}", subscriptionId);
		return SubscriptionResponseDto.from(subscription);
	}

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
		validateAge(subscription.getUser(), plan, now.toLocalDate());
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
		// 회선을 잠근 뒤 확인하므로 같은 회선의 예약이 동시에 들어와도 둘 다 통과하지 않는다.
		if (subscriptionRoamingRepository.existsOverlapping(subscriptionId, startDate, endDate)) {
			throw new SubscriptionException(SubscriptionErrorCode.ROAMING_PERIOD_OVERLAPPED);
		}
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

	// 사용자가 고른 번호를 010-XXXX-XXXX로 맞추고, 해지되지 않은 회선이 쓰고 있으면 막는다.
	private String choosePhoneNumber(String phoneNumber) {
		String digits = phoneNumber.replaceAll("\\D", "");
		String normalized = digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
		if (subscriptionRepository.existsByPhoneNumberAndStatusNot(normalized, SubscriptionStatus.TERMINATED)) {
			throw new SubscriptionException(SubscriptionErrorCode.PHONE_NUMBER_IN_USE);
		}
		return normalized;
	}

	// 010-XXXX-XXXX 형식으로 해지되지 않은 회선과 겹치지 않는 번호를 고른다.
	private String assignPhoneNumber() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int attempt = 0; attempt < MAX_PHONE_NUMBER_ATTEMPTS; attempt++) {
			String phoneNumber = "010-%04d-%04d".formatted(random.nextInt(10_000), random.nextInt(10_000));
			if (!subscriptionRepository.existsByPhoneNumberAndStatusNot(phoneNumber, SubscriptionStatus.TERMINATED)) {
				return phoneNumber;
			}
		}
		throw new SubscriptionException(SubscriptionErrorCode.PHONE_NUMBER_UNAVAILABLE);
	}

	// 나이 조건이 있는 요금제는 생년월일로 만 나이를 확인하고, 생년월일이 없으면 가입을 막는다.
	private void validateAge(User user, Plan plan, LocalDate today) {
		if (plan.getMinAge() == null && plan.getMaxAge() == null) {
			return;
		}
		LocalDate birthDate = user.getBirthDate();
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

package com.ubot.subscription.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.bundle.entity.BundleProduct;
import com.ubot.bundle.enums.BundleType;
import com.ubot.bundle.exception.BundleProductErrorCode;
import com.ubot.bundle.exception.BundleProductException;
import com.ubot.bundle.repository.BundleProductRepository;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductWriteSupport;
import com.ubot.home.enums.HomeProductType;
import com.ubot.home.repository.HomeSubscriptionRepository;
import com.ubot.subscription.dto.response.BundleGroupResponseDto;
import com.ubot.subscription.entity.BundleGroup;
import com.ubot.subscription.entity.Subscription;
import com.ubot.subscription.entity.SubscriptionBundle;
import com.ubot.subscription.enums.SubscriptionBundleStatus;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.subscription.exception.SubscriptionErrorCode;
import com.ubot.subscription.exception.SubscriptionException;
import com.ubot.subscription.repository.BundleGroupRepository;
import com.ubot.subscription.repository.SubscriptionBundleRepository;
import com.ubot.subscription.repository.SubscriptionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결합 만들기·회선 추가·회선 빼기.
 * 대표 회선 소유자가 전화번호로 본인 명의의 다른 회선만 추가한다. 다른 사람의 회선은 추가할 수 없다.
 * 한 회선은 결합 하나에만 들어간다(FAQ 1381, uq_subscription_bundles_active).
 * 결합 상품의 line_count가 회선 수 상한이고, 할인은 회선 수를 채웠을 때 대표 회선에만 적용한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BundleGroupService {

	// line_count가 없는 결합(가족 오래함께)은 2회선 이상이면 할인하고, 회선 수는 DB 조건과 같이 10까지 둔다.
	static final int MIN_LINE_COUNT_WITHOUT_LIMIT = 2;
	static final int MAX_LINE_COUNT_WITHOUT_LIMIT = 10;

	private final SubscriptionRepository subscriptionRepository;
	private final SubscriptionBundleRepository subscriptionBundleRepository;
	private final BundleGroupRepository bundleGroupRepository;
	private final BundleProductRepository bundleProductRepository;
	private final HomeSubscriptionRepository homeSubscriptionRepository;

	public BundleGroupResponseDto create(Long userId, Long subscriptionId, Long bundleProductId) {
		Subscription subscription = subscriptionRepository.findBySubscriptionIdAndUserIdForUpdate(subscriptionId, userId)
				.orElseThrow(() -> new SubscriptionException(SubscriptionErrorCode.SUBSCRIPTION_NOT_FOUND));
		if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
			throw new SubscriptionException(SubscriptionErrorCode.SUBSCRIPTION_NOT_ACTIVE);
		}
		BundleProduct product = bundleProductRepository.findByIdForUpdate(bundleProductId)
				.filter(found -> found.getStatus() == MasterStatus.ACTIVE)
				.orElseThrow(() -> new BundleProductException(BundleProductErrorCode.PRODUCT_NOT_FOUND));
		if (subscriptionBundleRepository.existsActiveBySubscriptionId(subscriptionId)) {
			throw new SubscriptionException(SubscriptionErrorCode.ALREADY_IN_BUNDLE);
		}
		BundleGroup group = bundleGroupRepository.save(BundleGroup.create(subscription));
		// 동시에 같은 회선으로 결합을 만들면 DB의 유일 조건이 막는다.
		ProductWriteSupport.saveUnique(
				() -> subscriptionBundleRepository.saveAndFlush(
						SubscriptionBundle.join(subscription, product, group, true, LocalDateTime.now())),
				() -> new SubscriptionException(SubscriptionErrorCode.ALREADY_IN_BUNDLE));
		log.info("결합을 만들었습니다: 그룹ID={}, 대표 회선ID={}, 상품ID={}", group.getBundleGroupId(), subscriptionId, bundleProductId);
		return toResponse(group);
	}

	/** subscriptionId는 요청한 사용자의 대표 회선이어야 한다. */
	public BundleGroupResponseDto addMember(Long userId, Long subscriptionId, Long bundleGroupId, String phoneNumber) {
		BundleGroup group = bundleGroupRepository.findActiveForUpdate(bundleGroupId)
				.filter(found -> found.isRepresentedBy(subscriptionId))
				.filter(found -> isOwner(found.getRepresentativeSubscription(), userId))
				.orElseThrow(() -> new SubscriptionException(SubscriptionErrorCode.BUNDLE_GROUP_NOT_FOUND));
		List<SubscriptionBundle> members = subscriptionBundleRepository.findActiveMembers(bundleGroupId);
		BundleProduct product = productOf(members);
		if (members.size() >= maxLineCount(product)) {
			throw new SubscriptionException(SubscriptionErrorCode.BUNDLE_GROUP_FULL);
		}
		// 본인 명의가 아닌 번호와 없는 번호를 같은 오류로 묶어 다른 사람 번호의 존재 여부를 드러내지 않는다.
		Subscription target = subscriptionRepository.findActiveByUserIdAndPhoneDigits(userId, phoneNumber.replaceAll("\\D", ""))
				.filter(found -> !subscriptionBundleRepository.existsActiveBySubscriptionId(found.getSubscriptionId()))
				.orElseThrow(() -> new SubscriptionException(SubscriptionErrorCode.BUNDLE_MEMBER_NOT_ADDABLE));
		ProductWriteSupport.saveUnique(
				() -> subscriptionBundleRepository.saveAndFlush(
						SubscriptionBundle.join(target, product, group, false, LocalDateTime.now())),
				() -> new SubscriptionException(SubscriptionErrorCode.BUNDLE_MEMBER_NOT_ADDABLE));
		log.info("결합에 회선을 추가했습니다: 그룹ID={}, 회선ID={}", bundleGroupId, target.getSubscriptionId());
		return toResponse(group);
	}

	/**
	 * subscriptionId는 요청한 사용자의 회선으로, 이 결합의 구성 회선이어야 한다.
	 * 대표 회선이면 아무 구성원이나, 구성 회선이면 자기 회선만 뺄 수 있다.
	 * 대표 회선이 빠지면 결합 전체를 종료하고 null을 돌려준다.
	 */
	public BundleGroupResponseDto removeMember(
			Long userId, Long subscriptionId, Long bundleGroupId, Long memberSubscriptionId) {
		BundleGroup group = bundleGroupRepository.findActiveForUpdate(bundleGroupId)
				.orElseThrow(() -> new SubscriptionException(SubscriptionErrorCode.BUNDLE_GROUP_NOT_FOUND));
		List<SubscriptionBundle> members = subscriptionBundleRepository.findActiveMembers(bundleGroupId);
		members.stream()
				.map(SubscriptionBundle::getSubscription)
				.filter(member -> member.getSubscriptionId().equals(subscriptionId) && isOwner(member, userId))
				.findFirst()
				.orElseThrow(() -> new SubscriptionException(SubscriptionErrorCode.BUNDLE_GROUP_NOT_FOUND));
		SubscriptionBundle target = members.stream()
				.filter(member -> member.getSubscription().getSubscriptionId().equals(memberSubscriptionId))
				.findFirst()
				.orElseThrow(() -> new SubscriptionException(SubscriptionErrorCode.BUNDLE_MEMBER_NOT_FOUND));
		if (!group.isRepresentedBy(subscriptionId) && !subscriptionId.equals(memberSubscriptionId)) {
			throw new SubscriptionException(SubscriptionErrorCode.BUNDLE_MEMBER_NOT_FOUND);
		}
		LocalDateTime now = LocalDateTime.now();
		if (group.isRepresentedBy(memberSubscriptionId)) {
			members.forEach(member -> member.end(now));
			group.end(now);
			log.info("대표 회선이 빠져 결합을 종료했습니다: 그룹ID={}", bundleGroupId);
			return null;
		}
		target.end(now);
		log.info("결합에서 회선을 뺐습니다: 그룹ID={}, 회선ID={}", bundleGroupId, memberSubscriptionId);
		return toResponse(group);
	}

	/**
	 * 회선 해지에 따른 결합 정리. 대표 회선이면 결합 전체를 종료하고, 구성 회선이면 그 회선만 뺀다.
	 * 호출하는 쪽이 회선을 잠근 상태여야 한다.
	 */
	public void leaveOnTermination(Subscription subscription, LocalDateTime now) {
		subscriptionBundleRepository.findActiveBySubscriptionId(subscription.getSubscriptionId()).ifPresent(record -> {
			BundleGroup group = record.getBundleGroup() == null ? null
					: bundleGroupRepository.findActiveForUpdate(record.getBundleGroup().getBundleGroupId()).orElse(null);
			if (group != null && group.isRepresentedBy(subscription.getSubscriptionId())) {
				subscriptionBundleRepository.findActiveMembers(group.getBundleGroupId()).forEach(member -> member.end(now));
				group.end(now);
				log.info("대표 회선이 해지되어 결합을 종료했습니다: 그룹ID={}", group.getBundleGroupId());
				return;
			}
			record.end(now);
		});
	}

	/** 이용 중인 결합 기록의 그룹 정보. 그룹이 없거나 종료됐으면 null이다. */
	@Transactional(readOnly = true)
	public BundleGroupResponseDto getActiveGroup(SubscriptionBundle record) {
		if (record.getStatus() != SubscriptionBundleStatus.ACTIVE || record.getBundleGroup() == null) {
			return null;
		}
		return toResponse(record.getBundleGroup());
	}

	private BundleGroupResponseDto toResponse(BundleGroup group) {
		List<SubscriptionBundle> members = subscriptionBundleRepository.findActiveMembers(group.getBundleGroupId());
		BundleProduct product = productOf(members);
		boolean discountApplied = discountApplied(product, members.size(), group.getRepresentativeSubscription());
		return BundleGroupResponseDto.builder()
				.bundleGroupId(group.getBundleGroupId())
				.bundleProductId(product.getBundleProductId())
				.bundleProductName(product.getName())
				.bundleType(product.getBundleType())
				.lineCount(members.size())
				.requiredLineCount(product.getLineCount())
				.discountApplied(discountApplied)
				.discountAmount(discountApplied ? product.getDiscountAmount() : 0)
				.members(members.stream()
						.map(member -> BundleGroupResponseDto.Member.builder()
								.subscriptionId(member.getSubscription().getSubscriptionId())
								.maskedPhoneNumber(maskPhoneNumber(member.getSubscription().getPhoneNumber()))
								.representative(group.isRepresentedBy(member.getSubscription().getSubscriptionId()))
								.build())
						.toList())
				.build();
	}

	private int maxLineCount(BundleProduct product) {
		return product.getLineCount() == null ? MAX_LINE_COUNT_WITHOUT_LIMIT : product.getLineCount();
	}

	// 회선 수를 채워야 할인한다. 홈 결합은 대표 회선 명의자가 연결된 인터넷 상품을 이용 중이어야 한다.
	private boolean discountApplied(BundleProduct product, int lineCount, Subscription representative) {
		boolean enoughLines = product.getLineCount() == null
				? lineCount >= MIN_LINE_COUNT_WITHOUT_LIMIT
				: lineCount >= product.getLineCount();
		if (!enoughLines || representative == null) {
			return false;
		}
		if (product.getBundleType() != BundleType.HOME) {
			return true;
		}
		return product.getHomeProduct() != null && homeSubscriptionRepository
				.findActiveByUserIdAndProductType(representative.getUser().getId(), HomeProductType.INTERNET)
				.map(home -> home.getHomeProduct().getHomeProductId().equals(product.getHomeProduct().getHomeProductId()))
				.orElse(false);
	}

	// 그룹의 결합 상품은 구성 회선 기록에서 읽는다. 대표 회선 기록이 남아 있는 동안은 비어 있지 않다.
	private BundleProduct productOf(List<SubscriptionBundle> members) {
		return members.stream()
				.map(SubscriptionBundle::getBundleProduct)
				.findFirst()
				.orElseThrow(() -> new SubscriptionException(SubscriptionErrorCode.BUNDLE_GROUP_NOT_FOUND));
	}

	private boolean isOwner(Subscription subscription, Long userId) {
		return subscription != null && subscription.getUser().getId().equals(userId);
	}

	// 시드로 만든 기존 결합에는 다른 사용자의 회선이 있을 수 있어 끝 4자리만 남긴다. 010-1234-5678 → 010-****-5678
	private String maskPhoneNumber(String phoneNumber) {
		String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
		if (digits.length() == 11) {
			return digits.substring(0, 3) + "-****-" + digits.substring(7);
		}
		if (digits.length() == 10) {
			return digits.substring(0, 3) + "-***-" + digits.substring(6);
		}
		if (digits.length() > 4) {
			return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
		}
		return "****";
	}
}

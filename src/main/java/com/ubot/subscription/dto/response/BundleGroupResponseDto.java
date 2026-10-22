package com.ubot.subscription.dto.response;

import java.util.List;

import com.ubot.bundle.enums.BundleType;

import lombok.Builder;

/**
 * 결합 그룹의 현재 상태. requiredLineCount는 결합 상품의 회선 수이고 null이면 2회선 이상이다.
 * discountAmount는 할인이 적용될 때 대표 회선에서 빠지는 금액이며, 적용되지 않으면 0이다.
 * 다른 사용자의 회선도 들어 있으므로 번호는 마스킹해서 보여 준다.
 */
@Builder
public record BundleGroupResponseDto(
		Long bundleGroupId,
		Long bundleProductId,
		String bundleProductName,
		BundleType bundleType,
		int lineCount,
		Integer requiredLineCount,
		boolean discountApplied,
		int discountAmount,
		List<Member> members
) {

	public BundleGroupResponseDto {
		members = members == null ? List.of() : List.copyOf(members);
	}

	@Builder
	public record Member(Long subscriptionId, String maskedPhoneNumber, boolean representative) {
	}
}

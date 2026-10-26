package com.ubot.counsel.session.dto.response;

import java.util.List;

/** 상담 화면에 보여 줄 고객 정보입니다. 이메일·주소 같은 다른 개인정보는 넣지 않습니다. */
public record CustomerInfoResponseDto(String name, List<Line> lines, List<HomeProduct> homeProducts) {

	/** 해지되지 않은 회선과 그 회선에서 이용 중인 상품 이름입니다. */
	public record Line(Long subscriptionId, String phoneNumber, String status, String planName,
			List<String> addons, List<String> bundles, List<String> roamings) {
	}

	/** 이용 중인 인터넷·IPTV 상품입니다. */
	public record HomeProduct(Long subscriptionId, String productType, String name) {
	}
}

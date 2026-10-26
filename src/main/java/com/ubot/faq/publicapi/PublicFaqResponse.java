package com.ubot.faq.publicapi;

/** 공개 FAQ 응답입니다. 관리자 ID·의도 같은 내부 필드는 넣지 않습니다. */
public final class PublicFaqResponse {
	private PublicFaqResponse() {
	}

	public record Category(String name, long count) {
	}

	/** 목록에는 답변을 넣지 않습니다. */
	public record Summary(Long id, String category, String question) {
	}

	public record Detail(Long id, String category, String question, String answer) {
	}
}

package com.ubot.faq.service;

import java.util.Set;

/** 커밋된 FAQ 변경분만 영문 허용 목록에 반영하기 위한 이벤트입니다. */
public record FaqChangedEvent(Set<Long> faqIds) {
	public FaqChangedEvent {
		faqIds = Set.copyOf(faqIds);
	}
}

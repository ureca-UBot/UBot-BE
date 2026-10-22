package com.ubot.guardreview.service;

import com.ubot.guardreview.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 수동 금칙어 변경과 검토 승인을 동일한 트랜잭션 잠금으로 직렬화합니다. */
@Component
@RequiredArgsConstructor
public class ReviewCatalogLock {
	private final ReviewRepository repository;
	public void acquire() {
		repository.catalogLock();
	}
}

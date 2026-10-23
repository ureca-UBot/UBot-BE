package com.ubot.forbiddenword.service;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/** 수동 금칙어 변경과 후보 승인을 호출자의 트랜잭션 종료까지 직렬화합니다. */
@Component
@RequiredArgsConstructor
public class ForbiddenWordCatalogLock {

	private static final long CATALOG_LOCK_KEY = 674102231L;
	@NonNull
	private final JdbcTemplate jdbc;

	public void acquire() {
		Assert.state(TransactionSynchronizationManager.isActualTransactionActive(), "금칙어 목록 잠금에는 활성 트랜잭션이 필요합니다.");
		jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, CATALOG_LOCK_KEY);
	}
}

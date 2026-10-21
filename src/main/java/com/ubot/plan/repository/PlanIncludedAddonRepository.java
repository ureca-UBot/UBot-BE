package com.ubot.plan.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/** 요금제에 포함된 부가서비스. 연결만 담는 표라 엔티티 없이 다룬다. */
@Repository
@RequiredArgsConstructor
public class PlanIncludedAddonRepository {

	private final JdbcTemplate jdbcTemplate;

	/** 삭제되지 않은 부가서비스를 등록 순서대로 돌려준다. */
	public List<Long> findAddonServiceIds(Long planId) {
		return jdbcTemplate.queryForList("""
				SELECT pia.addon_service_id
				FROM plan_included_addons pia
				JOIN addon_services a ON a.addon_service_id = pia.addon_service_id
				WHERE pia.plan_id = ? AND a.deleted_at IS NULL
				ORDER BY pia.created_at, pia.addon_service_id
				""", Long.class, planId);
	}

	public void replace(Long planId, List<Long> addonServiceIds) {
		jdbcTemplate.update("DELETE FROM plan_included_addons WHERE plan_id = ?", planId);
		jdbcTemplate.batchUpdate("""
				INSERT INTO plan_included_addons (plan_id, addon_service_id) VALUES (?, ?)
				""", addonServiceIds, addonServiceIds.size(), (statement, addonServiceId) -> {
			statement.setLong(1, planId);
			statement.setLong(2, addonServiceId);
		});
	}
}

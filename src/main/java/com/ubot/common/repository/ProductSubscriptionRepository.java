package com.ubot.common.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProductSubscriptionRepository {

	private final JdbcTemplate jdbcTemplate;

	public boolean existsByPlanId(Long planId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM subscriptions WHERE plan_id = ?)
				""", Boolean.class, planId));
	}

	public boolean existsByBundleProductId(Long bundleProductId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM subscription_bundles WHERE bundle_product_id = ?)
				""", Boolean.class, bundleProductId));
	}

	public boolean existsByAddonServiceId(Long addonServiceId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM subscription_addons WHERE addon_service_id = ?)
				""", Boolean.class, addonServiceId));
	}

	public boolean existsByRoamingProductId(Long roamingProductId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM subscription_roamings WHERE roaming_product_id = ?)
				""", Boolean.class, roamingProductId));
	}

	public boolean existsByHomeProductId(Long homeProductId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM home_subscriptions WHERE home_product_id = ?)
				""", Boolean.class, homeProductId));
	}

	// 홈 결합이 연결한 인터넷 상품은 결합 상품이 남아 있는 동안 지우지 않는다.
	public boolean existsBundleProductByHomeProductId(Long homeProductId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM bundle_products WHERE home_product_id = ? AND deleted_at IS NULL)
				""", Boolean.class, homeProductId));
	}
}

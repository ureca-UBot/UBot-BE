package com.ubot.subscription.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.subscription.entity.SubscriptionBundle;
import com.ubot.subscription.enums.SubscriptionBundleStatus;

public interface SubscriptionBundleRepository extends JpaRepository<SubscriptionBundle, Long> {
	@Query("""
			select record from SubscriptionBundle record
			where record.subscription.subscriptionId = :subscriptionId
			  and record.subscription.user.id = :userId
			  and record.subscription.user.deletedAt is null
			order by record.joinedAt desc, record.subscriptionBundleId desc
			""")
	List<SubscriptionBundle> findAllBySubscriptionIdAndUserId(
			@Param("subscriptionId") Long subscriptionId, @Param("userId") Long userId);

	boolean existsBySubscription_SubscriptionIdAndStatus(Long subscriptionId, SubscriptionBundleStatus status);

	// 대표 회선이 가장 먼저 가입하므로 가입 순서로 정렬하면 대표 회선이 맨 앞에 온다.
	@EntityGraph(attributePaths = { "subscription", "subscription.user", "bundleProduct" })
	@Query("""
			select record from SubscriptionBundle record
			where record.bundleGroup.bundleGroupId = :bundleGroupId
			  and record.status = com.ubot.subscription.enums.SubscriptionBundleStatus.ACTIVE
			order by record.joinedAt asc, record.subscriptionBundleId asc
			""")
	List<SubscriptionBundle> findActiveMembers(@Param("bundleGroupId") Long bundleGroupId);
}

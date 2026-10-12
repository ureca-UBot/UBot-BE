package com.ubot.subscription.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.subscription.entity.SubscriptionBundle;

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
}

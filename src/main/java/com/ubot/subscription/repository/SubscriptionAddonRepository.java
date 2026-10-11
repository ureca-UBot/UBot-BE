package com.ubot.subscription.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.subscription.entity.SubscriptionAddon;

public interface SubscriptionAddonRepository extends JpaRepository<SubscriptionAddon, Long> {
	@Query("""
			select record from SubscriptionAddon record
			where record.subscription.subscriptionId = :subscriptionId
			  and record.subscription.user.id = :userId
			  and record.subscription.user.deletedAt is null
			order by record.subscribedAt desc, record.subscriptionAddonId desc
			""")
	List<SubscriptionAddon> findAllBySubscriptionIdAndUserId(
			@Param("subscriptionId") Long subscriptionId, @Param("userId") Long userId);
}

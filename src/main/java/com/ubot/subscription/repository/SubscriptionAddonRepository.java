package com.ubot.subscription.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.subscription.entity.SubscriptionAddon;
import com.ubot.subscription.enums.SubscriptionAddonStatus;

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

	boolean existsBySubscription_SubscriptionIdAndAddonService_AddonServiceIdAndStatus(
			Long subscriptionId, Long addonServiceId, SubscriptionAddonStatus status);

	Optional<SubscriptionAddon> findBySubscriptionAddonIdAndSubscription_SubscriptionIdAndStatus(
			Long subscriptionAddonId, Long subscriptionId, SubscriptionAddonStatus status);
}

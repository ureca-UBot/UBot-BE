package com.ubot.subscription.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
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

	@Query("""
			select count(record) > 0 from SubscriptionAddon record
			where record.subscription.subscriptionId = :subscriptionId
			  and record.addonService.addonServiceId = :addonServiceId
			  and record.status = com.ubot.subscription.enums.SubscriptionAddonStatus.ACTIVE
			""")
	boolean existsActiveBySubscriptionIdAndAddonServiceId(
			@Param("subscriptionId") Long subscriptionId, @Param("addonServiceId") Long addonServiceId);

	@Query("""
			select record from SubscriptionAddon record
			where record.subscriptionAddonId = :subscriptionAddonId
			  and record.subscription.subscriptionId = :subscriptionId
			  and record.status = com.ubot.subscription.enums.SubscriptionAddonStatus.ACTIVE
			""")
	Optional<SubscriptionAddon> findActiveBySubscriptionAddonIdAndSubscriptionId(
			@Param("subscriptionAddonId") Long subscriptionAddonId, @Param("subscriptionId") Long subscriptionId);

	@EntityGraph(attributePaths = "addonService")
	@Query("""
			select record from SubscriptionAddon record
			where record.subscription.subscriptionId = :subscriptionId
			  and record.status = com.ubot.subscription.enums.SubscriptionAddonStatus.ACTIVE
			""")
	List<SubscriptionAddon> findAllActiveBySubscriptionId(@Param("subscriptionId") Long subscriptionId);
}

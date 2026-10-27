package com.ubot.subscription.entity;

import java.time.LocalDateTime;

import com.ubot.addon.entity.AddonService;
import com.ubot.common.entity.BusinessEntity;
import com.ubot.subscription.enums.SubscriptionAddonSource;
import com.ubot.subscription.enums.SubscriptionAddonStatus;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@DynamicUpdate
@Table(name = "subscription_addons")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class SubscriptionAddon extends BusinessEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "subscription_addon_id")
	private Long subscriptionAddonId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "subscription_id")
	private Subscription subscription;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "addon_service_id")
	private AddonService addonService;

	@Column(name = "subscribed_at")
	private LocalDateTime subscribedAt;

	@Column(name = "canceled_at")
	private LocalDateTime canceledAt;

	@Builder.Default
	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private SubscriptionAddonStatus status = SubscriptionAddonStatus.ACTIVE;

	@Column(name = "service_name_snapshot")
	private String serviceNameSnapshot;

	@Column(name = "monthly_fee_snapshot")
	private Integer monthlyFeeSnapshot;

	@Builder.Default
	@Enumerated(EnumType.STRING)
	@Column(name = "source")
	private SubscriptionAddonSource source = SubscriptionAddonSource.SUBSCRIBED;

	public static SubscriptionAddon subscribe(Subscription subscription, AddonService addonService, LocalDateTime now) {
		return SubscriptionAddon.builder()
				.subscription(subscription)
				.addonService(addonService)
				.subscribedAt(now)
				.serviceNameSnapshot(addonService.getName())
				.monthlyFeeSnapshot(addonService.getMonthlyFee())
				.build();
	}

	/** 요금제에 포함된 부가서비스. 요금제 요금에 들어 있으므로 0원으로 남긴다. */
	public static SubscriptionAddon includeInPlan(Subscription subscription, AddonService addonService, LocalDateTime now) {
		return SubscriptionAddon.builder()
				.subscription(subscription)
				.addonService(addonService)
				.subscribedAt(now)
				.serviceNameSnapshot(addonService.getName())
				.monthlyFeeSnapshot(0)
				.source(SubscriptionAddonSource.PLAN_INCLUDED)
				.build();
	}

	public boolean isIncludedInPlan() {
		return source == SubscriptionAddonSource.PLAN_INCLUDED;
	}

	public void cancel(LocalDateTime now) {
		this.status = SubscriptionAddonStatus.CANCELED;
		this.canceledAt = now;
	}

	@PrePersist
	private void initializeMetadata() {
		initializeTimestamps();
	}

	@PreUpdate
	private void updateTimestamp() {
		markUpdated();
	}
}

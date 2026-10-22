package com.ubot.subscription.entity;

import java.time.LocalDateTime;

import com.ubot.bundle.entity.BundleProduct;
import com.ubot.common.entity.BusinessEntity;
import com.ubot.subscription.enums.SubscriptionBundleStatus;

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
@Table(name = "subscription_bundles")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class SubscriptionBundle extends BusinessEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "subscription_bundle_id")
	private Long subscriptionBundleId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "subscription_id")
	private Subscription subscription;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "bundle_product_id")
	private BundleProduct bundleProduct;

	@Column(name = "joined_at")
	private LocalDateTime joinedAt;

	@Column(name = "ended_at")
	private LocalDateTime endedAt;

	@Builder.Default
	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private SubscriptionBundleStatus status = SubscriptionBundleStatus.ACTIVE;

	@Column(name = "product_name_snapshot")
	private String productNameSnapshot;

	@Column(name = "applied_discount_amount")
	private Integer appliedDiscountAmount;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "bundle_group_id")
	private BundleGroup bundleGroup;

	/** 결합 할인액은 대표 회선 기록에만 적고 구성 회선은 0원이다. */
	public static SubscriptionBundle join(Subscription subscription, BundleProduct bundleProduct,
			BundleGroup bundleGroup, boolean representative, LocalDateTime now) {
		return SubscriptionBundle.builder()
				.subscription(subscription)
				.bundleProduct(bundleProduct)
				.bundleGroup(bundleGroup)
				.joinedAt(now)
				.productNameSnapshot(bundleProduct.getName())
				.appliedDiscountAmount(representative ? bundleProduct.getDiscountAmount() : 0)
				.build();
	}

	public void end(LocalDateTime now) {
		this.status = SubscriptionBundleStatus.ENDED;
		this.endedAt = now;
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

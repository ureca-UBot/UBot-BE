package com.ubot.subscription.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.ubot.common.entity.BusinessEntity;
import com.ubot.roaming.entity.RoamingProduct;
import com.ubot.subscription.enums.SubscriptionRoamingStatus;

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
@Table(name = "subscription_roamings")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class SubscriptionRoaming extends BusinessEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "subscription_roaming_id")
	private Long subscriptionRoamingId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "subscription_id")
	private Subscription subscription;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "roaming_product_id")
	private RoamingProduct roamingProduct;

	@Column(name = "start_date")
	private LocalDate startDate;

	@Column(name = "end_date")
	private LocalDate endDate;

	@Builder.Default
	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private SubscriptionRoamingStatus status = SubscriptionRoamingStatus.SCHEDULED;

	@Column(name = "product_name_snapshot")
	private String productNameSnapshot;

	@Column(name = "daily_fee_snapshot")
	private Integer dailyFeeSnapshot;

	@Column(name = "data_amount_mb_snapshot")
	private Long dataAmountMbSnapshot;

	@Column(name = "canceled_at")
	private LocalDateTime canceledAt;

	@PrePersist
	private void initializeMetadata() {
		initializeTimestamps();
	}

	@PreUpdate
	private void updateTimestamp() {
		markUpdated();
	}
}

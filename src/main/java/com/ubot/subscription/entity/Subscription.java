package com.ubot.subscription.entity;

import java.time.LocalDateTime;

import com.ubot.common.entity.BusinessEntity;
import com.ubot.plan.entity.Plan;
import com.ubot.subscription.enums.SimStatus;
import com.ubot.subscription.enums.SimType;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.user.entity.User;

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
@Table(name = "subscriptions")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class Subscription extends BusinessEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "subscription_id")
	private Long subscriptionId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	private User user;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "plan_id")
	private Plan plan;

	@Column(name = "phone_number")
	private String phoneNumber;

	@Builder.Default
	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

	@Column(name = "activated_at")
	private LocalDateTime activatedAt;

	@Column(name = "terminated_at")
	private LocalDateTime terminatedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "sim_type")
	private SimType simType;

	@Column(name = "sim_identifier")
	private String simIdentifier;

	@Enumerated(EnumType.STRING)
	@Column(name = "sim_status")
	private SimStatus simStatus;

	@Column(name = "sim_activated_at")
	private LocalDateTime simActivatedAt;

	@Column(name = "sim_deactivated_at")
	private LocalDateTime simDeactivatedAt;

	// 마지막으로 요금제를 바꾼 시각. 요금제 변경 월 1회 제한에 쓴다.
	@Column(name = "plan_changed_at")
	private LocalDateTime planChangedAt;

	@PrePersist
	private void initializeMetadata() {
		initializeTimestamps();
	}

	@PreUpdate
	private void updateTimestamp() {
		markUpdated();
	}
}

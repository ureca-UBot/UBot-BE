package com.ubot.subscription.entity;

import java.time.LocalDateTime;

import com.ubot.common.entity.BusinessEntity;
import com.ubot.subscription.enums.BundleGroupStatus;

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
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 같은 결합으로 묶인 회선들과 대표 회선. 결합 상품은 구성 회선의 subscription_bundles에서 읽는다.
 * 대표 회선이 정리(purge)되면 representativeSubscription은 null이 된다.
 */
@Entity
@DynamicUpdate
@Table(name = "bundle_groups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BundleGroup extends BusinessEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "bundle_group_id")
	private Long bundleGroupId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "representative_subscription_id")
	private Subscription representativeSubscription;

	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private BundleGroupStatus status;

	@Column(name = "ended_at")
	private LocalDateTime endedAt;

	public static BundleGroup create(Subscription representativeSubscription) {
		BundleGroup group = new BundleGroup();
		group.representativeSubscription = representativeSubscription;
		group.status = BundleGroupStatus.ACTIVE;
		return group;
	}

	public boolean isRepresentedBy(Long subscriptionId) {
		return representativeSubscription != null
				&& representativeSubscription.getSubscriptionId().equals(subscriptionId);
	}

	public void end(LocalDateTime now) {
		this.status = BundleGroupStatus.ENDED;
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

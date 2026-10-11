package com.ubot.usage.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import com.ubot.subscription.entity.Subscription;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "usage_records")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class UsageRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "usage_id")
	private Long usageId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "subscription_id")
	private Subscription subscription;

	@Column(name = "billing_month")
	private LocalDate billingMonth;

	@Builder.Default
	@Column(name = "data_used_mb")
	private Long dataUsedMb = 0L;

	@Builder.Default
	@Column(name = "voice_used_minutes")
	private Integer voiceUsedMinutes = 0;

	@Builder.Default
	@Column(name = "sms_used_count")
	private Integer smsUsedCount = 0;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "allowance_snapshot")
	private Map<String, Object> allowanceSnapshot;

	@Column(name = "aggregated_through_date")
	private LocalDate aggregatedThroughDate;

	@Column(name = "finalized_at")
	private LocalDateTime finalizedAt;

	@Column(name = "full_month_active")
	private Boolean fullMonthActive;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@PrePersist
	@PreUpdate
	private void updateTimestamp() {
		this.updatedAt = LocalDateTime.now();
	}
}

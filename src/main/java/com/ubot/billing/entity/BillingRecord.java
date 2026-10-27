package com.ubot.billing.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.ubot.billing.enums.PaymentMethod;
import com.ubot.billing.enums.PaymentStatus;
import com.ubot.common.entity.BusinessEntity;
import com.ubot.subscription.entity.Subscription;

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
@Table(name = "billing_records")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class BillingRecord extends BusinessEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "billing_id")
	private Long billingId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "subscription_id")
	private Subscription subscription;

	@Column(name = "billing_month")
	private LocalDate billingMonth;

	@Builder.Default
	@Column(name = "base_fee")
	private Integer baseFee = 0;

	@Builder.Default
	@Column(name = "addon_fee")
	private Integer addonFee = 0;

	@Builder.Default
	@Column(name = "roaming_fee")
	private Integer roamingFee = 0;

	@Builder.Default
	@Column(name = "extra_fee")
	private Integer extraFee = 0;

	@Builder.Default
	@Column(name = "discount_amount")
	private Integer discountAmount = 0;

	@Column(name = "total_amount")
	private Integer totalAmount;

	@Column(name = "due_date")
	private LocalDate dueDate;

	@Builder.Default
	@Enumerated(EnumType.STRING)
	@Column(name = "payment_status")
	private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

	@Builder.Default
	@Column(name = "paid_amount")
	private Integer paidAmount = 0;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method")
	private PaymentMethod paymentMethod;

	@Column(name = "paid_at")
	private LocalDateTime paidAt;

	@PrePersist
	private void initializeMetadata() {
		initializeTimestamps();
	}

	@PreUpdate
	private void updateTimestamp() {
		markUpdated();
	}
}

package com.ubot.contract.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.ubot.common.entity.BusinessEntity;
import com.ubot.contract.enums.ContractStatus;
import com.ubot.contract.enums.ContractType;
import com.ubot.contract.enums.InstallmentStatus;
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
@Table(name = "subscription_contracts")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class SubscriptionContract extends BusinessEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "contract_id")
	private Long contractId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "subscription_id")
	private Subscription subscription;

	@Enumerated(EnumType.STRING)
	@Column(name = "contract_type")
	private ContractType contractType;

	@Column(name = "started_at")
	private LocalDate startedAt;

	@Column(name = "expires_at")
	private LocalDate expiresAt;

	@Builder.Default
	@Column(name = "discount_amount")
	private Integer discountAmount = 0;

	@Builder.Default
	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private ContractStatus status = ContractStatus.ACTIVE;

	@Column(name = "canceled_at")
	private LocalDateTime canceledAt;

	@Column(name = "device_name")
	private String deviceName;

	@Column(name = "device_total_amount")
	private Integer deviceTotalAmount;

	@Column(name = "installment_months")
	private Integer installmentMonths;

	@Column(name = "paid_installment_months")
	private Integer paidInstallmentMonths;

	@Column(name = "monthly_installment_amount")
	private Integer monthlyInstallmentAmount;

	@Column(name = "remaining_installment_amount")
	private Integer remainingInstallmentAmount;

	@Column(name = "installment_started_at")
	private LocalDate installmentStartedAt;

	@Column(name = "installment_expires_at")
	private LocalDate installmentExpiresAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "installment_status")
	private InstallmentStatus installmentStatus;

	@PrePersist
	private void initializeMetadata() {
		initializeTimestamps();
	}

	@PreUpdate
	private void updateTimestamp() {
		markUpdated();
	}
}

package com.ubot.plan.entity;

import com.ubot.common.entity.AdminManagedEntity;
import com.ubot.common.enums.MasterStatus;
import com.ubot.plan.enums.NetworkType;
import com.ubot.plan.enums.PlanTargetGroup;
import com.ubot.user.entity.User;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@DynamicUpdate
@Table(name = "plans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Plan extends AdminManagedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "plan_code")
    private String planCode;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "network_type")
    private NetworkType networkType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_group")
    private PlanTargetGroup targetGroup;

    @Column(name = "monthly_fee")
    private Integer monthlyFee;

    @Column(name = "data_amount_mb")
    private Long dataAmountMb;

    @Column(name = "data_unlimited")
    private boolean dataUnlimited;

    @Column(name = "exhausted_speed_kbps")
    private Integer exhaustedSpeedKbps;

    @Column(name = "voice_minutes")
    private Integer voiceMinutes;

    @Column(name = "voice_unlimited")
    private boolean voiceUnlimited;

    @Column(name = "sms_count")
    private Integer smsCount;

    @Column(name = "sms_unlimited")
    private boolean smsUnlimited;

    @Column(name = "tethering_amount_mb")
    private Long tetheringAmountMb;

    @Column(name = "min_age")
    private Integer minAge;

    @Column(name = "max_age")
    private Integer maxAge;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private MasterStatus status;

    public static Plan create(
            User administrator,
            String planCode,
            String name,
            String description,
            NetworkType networkType,
            PlanTargetGroup targetGroup,
            Integer monthlyFee,
            Long dataAmountMb,
            boolean dataUnlimited,
            Integer exhaustedSpeedKbps,
            Integer voiceMinutes,
            boolean voiceUnlimited,
            Integer smsCount,
            boolean smsUnlimited,
            Long tetheringAmountMb,
            Integer minAge,
            Integer maxAge
    ) {
        Plan product = new Plan();
        product.planCode = planCode;
        product.name = name;
        product.description = description;
        product.networkType = networkType;
        product.targetGroup = targetGroup;
        product.monthlyFee = monthlyFee;
        product.dataAmountMb = dataAmountMb;
        product.dataUnlimited = dataUnlimited;
        product.exhaustedSpeedKbps = exhaustedSpeedKbps;
        product.voiceMinutes = voiceMinutes;
        product.voiceUnlimited = voiceUnlimited;
        product.smsCount = smsCount;
        product.smsUnlimited = smsUnlimited;
        product.tetheringAmountMb = tetheringAmountMb;
        product.minAge = minAge;
        product.maxAge = maxAge;
        product.status = MasterStatus.ACTIVE;
        product.initializeMetadata(administrator);
        return product;
    }

    public void update(
            User administrator,
            String name,
            String description,
            NetworkType networkType,
            PlanTargetGroup targetGroup,
            Integer monthlyFee,
            Long dataAmountMb,
            boolean dataUnlimited,
            Integer exhaustedSpeedKbps,
            Integer voiceMinutes,
            boolean voiceUnlimited,
            Integer smsCount,
            boolean smsUnlimited,
            Long tetheringAmountMb,
            Integer minAge,
            Integer maxAge
    ) {
        this.name = name;
        this.description = description;
        this.networkType = networkType;
        this.targetGroup = targetGroup;
        this.monthlyFee = monthlyFee;
        this.dataAmountMb = dataAmountMb;
        this.dataUnlimited = dataUnlimited;
        this.exhaustedSpeedKbps = exhaustedSpeedKbps;
        this.voiceMinutes = voiceMinutes;
        this.voiceUnlimited = voiceUnlimited;
        this.smsCount = smsCount;
        this.smsUnlimited = smsUnlimited;
        this.tetheringAmountMb = tetheringAmountMb;
        this.minAge = minAge;
        this.maxAge = maxAge;
        markUpdated(administrator);
    }

    public void changeStatus(User administrator, MasterStatus status) {
        this.status = status;
        markUpdated(administrator);
    }

    public void delete(User administrator) {
        this.status = MasterStatus.INACTIVE;
        markDeleted(administrator);
    }
}

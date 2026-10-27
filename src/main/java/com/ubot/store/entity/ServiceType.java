package com.ubot.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "service_types")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "service_type_id")
    private Long serviceTypeId;

    @Column(name = "service_code")
    private String serviceCode;

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "description")
    private String description;

    @Column(name = "is_active")
    private boolean isActive;
}

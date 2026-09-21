package com.ubot.store.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stores")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "store_name", nullable = false, length = 150)
    private String storeName;

    @Column(name = "sido", length = 50)
    private String sido;

    @Column(name = "sigungu", length = 50)
    private String sigungu;

    @Column(name = "address", nullable = false, length = 500)
    private String address;

    @Column(name = "latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(name = "business_hours")
    private String businessHours;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "store_services",
            joinColumns = @JoinColumn(name = "store_id"),
            inverseJoinColumns = @JoinColumn(name = "service_type_id")
    )
    private Set<ServiceType> serviceTypes = new LinkedHashSet<>();

    public static Store create(
            String storeName,
            String sido,
            String sigungu,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String phoneNumber,
            String businessHours
    ) {
        LocalDateTime now = LocalDateTime.now();
        Store store = new Store();
        store.storeName = storeName;
        store.sido = sido;
        store.sigungu = sigungu;
        store.address = address;
        store.latitude = latitude;
        store.longitude = longitude;
        store.phoneNumber = phoneNumber;
        store.businessHours = businessHours;
        store.isActive = true;
        store.createdAt = now;
        store.updatedAt = now;
        return store;
    }

    public void updateStoreName(String storeName) {
        this.storeName = storeName;
        touch();
    }

    public void updateSido(String sido) {
        this.sido = sido;
        touch();
    }

    public void updateSigungu(String sigungu) {
        this.sigungu = sigungu;
        touch();
    }

    public void updateAddress(String address) {
        this.address = address;
        touch();
    }

    public void updateCoordinates(BigDecimal latitude, BigDecimal longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        touch();
    }

    public void updatePhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
        touch();
    }

    public void updateBusinessHours(String businessHours) {
        this.businessHours = businessHours;
        touch();
    }

    public void replaceServiceTypes(Collection<ServiceType> serviceTypes) {
        this.serviceTypes.clear();
        this.serviceTypes.addAll(serviceTypes);
        touch();
    }

    public void deactivate() {
        LocalDateTime now = LocalDateTime.now();
        this.isActive = false;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}

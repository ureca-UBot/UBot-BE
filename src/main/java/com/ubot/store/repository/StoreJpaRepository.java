package com.ubot.store.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ubot.store.entity.Store;

public interface StoreJpaRepository extends JpaRepository<Store, Long> {

    Optional<Store> findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(Long storeId);

    Optional<Store> findByStoreIdAndIsActiveFalseAndDeletedAtIsNotNull(Long storeId);

    boolean existsByStoreNameAndAddressAndIsActiveTrueAndDeletedAtIsNull(String storeName, String address);

    boolean existsByStoreNameAndAddressAndStoreIdNotAndIsActiveTrueAndDeletedAtIsNull(
            String storeName,
            String address,
            Long storeId
    );

}

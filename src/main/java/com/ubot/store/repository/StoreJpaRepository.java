package com.ubot.store.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ubot.store.entity.Store;

public interface StoreJpaRepository extends JpaRepository<Store, Long> {

    Optional<Store> findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(Long storeId);

    Optional<Store> findByStoreIdAndIsActiveFalseAndDeletedAtIsNotNull(Long storeId);

    Optional<Store> findByStoreNameAndAddress(String storeName, String address);

    boolean existsByStoreNameAndAddressAndStoreIdNot(
            String storeName,
            String address,
            Long storeId
    );

}

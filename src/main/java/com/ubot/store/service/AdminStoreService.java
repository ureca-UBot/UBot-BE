package com.ubot.store.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.store.dto.request.AdminStoreCreateRequestDto;
import com.ubot.store.dto.request.AdminStoreUpdateRequestDto;
import com.ubot.store.dto.response.AdminStoreResponseDto;
import com.ubot.store.entity.ServiceType;
import com.ubot.store.entity.Store;
import com.ubot.store.exception.StoreErrorCode;
import com.ubot.store.exception.StoreException;
import com.ubot.store.repository.ServiceTypeJpaRepository;
import com.ubot.store.repository.StoreJpaRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminStoreService {

    private final StoreJpaRepository storeJpaRepository;
    private final ServiceTypeJpaRepository serviceTypeJpaRepository;

    public AdminStoreResponseDto createStore(AdminStoreCreateRequestDto request) {
        String storeName = normalize(request.storeName());
        String address = normalize(request.address());

        storeJpaRepository.findByStoreNameAndAddress(storeName, address).ifPresent(existingStore -> {
            if (!existingStore.isActive()
                    && existingStore.getDeletedAt() != null) {
                throw new StoreException(StoreErrorCode.DELETED_STORE_ALREADY_EXISTS);
            }

            throw new StoreException(StoreErrorCode.DUPLICATE_STORE);
        });

        Set<ServiceType> serviceTypes = resolveServiceTypes(request.serviceCodes());

        Store store = Store.create(
                storeName,
                normalize(request.sido()),
                normalize(request.sigungu()),
                address,
                request.latitude(),
                request.longitude(),
                normalize(request.phoneNumber()),
                normalize(request.businessHours())
        );
        store.replaceServiceTypes(serviceTypes);

        return AdminStoreResponseDto.from(storeJpaRepository.save(store));
    }

    public AdminStoreResponseDto updateStore(Long storeId, AdminStoreUpdateRequestDto request) {
        validateCoordinatePair(request);
        Store store = getActiveStore(storeId);


        String newStoreName = request.storeName() != null
                ? normalize(request.storeName())
                : store.getStoreName();

        String newAddress = request.address() != null
                ? normalize(request.address())
                : store.getAddress();

        if (storeJpaRepository.existsByStoreNameAndAddressAndStoreIdNot(
                newStoreName,
                newAddress,
                storeId
        )) {
            throw new StoreException(StoreErrorCode.DUPLICATE_STORE);
        }

        boolean updated = false;

        if (request.storeName() != null) {
            store.updateStoreName(newStoreName);
            updated = true;
        }
        if (request.address() != null) {
            store.updateAddress(newAddress);
            updated = true;
        }
        if (request.sido() != null) {
            store.updateSido(normalize(request.sido()));
            updated = true;
        }
        if (request.sigungu() != null) {
            store.updateSigungu(normalize(request.sigungu()));
            updated = true;
        }
        if (request.latitude() != null) {
            store.updateCoordinates(request.latitude(), request.longitude());
            updated = true;
        }
        if (request.phoneNumber() != null) {
            store.updatePhoneNumber(normalize(request.phoneNumber()));
            updated = true;
        }
        if (request.businessHours() != null) {
            store.updateBusinessHours(normalize(request.businessHours()));
            updated = true;
        }
        if (request.serviceCodes() != null) {
            store.replaceServiceTypes(resolveServiceTypes(request.serviceCodes()));
            updated = true;
        }

        if (updated) {
            store.markUpdated();
        }

        return AdminStoreResponseDto.from(store);
    }

    public void deleteStore(Long storeId) {
        getActiveStore(storeId).deactivate();
    }

    public AdminStoreResponseDto activateStore(Long storeId) {
        Store store = getDeletedStore(storeId);

        store.activate();

        return AdminStoreResponseDto.from(store);
    }

    private Store getActiveStore(Long storeId) {
        return storeJpaRepository
                .findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(storeId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
    }

    private Store getDeletedStore(Long storeId) {
        return storeJpaRepository
                .findByStoreIdAndIsActiveFalseAndDeletedAtIsNotNull(storeId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
    }

    private Set<ServiceType> resolveServiceTypes(List<String> serviceCodes) {
        Set<String> normalizedCodes = normalizeServiceCodes(serviceCodes);
        if (normalizedCodes.isEmpty()) {
            return Set.of();
        }

        List<ServiceType> serviceTypes = serviceTypeJpaRepository
                .findAllByServiceCodeInAndIsActiveTrue(normalizedCodes);
        if (serviceTypes.size() != normalizedCodes.size()) {
            throw new StoreException(StoreErrorCode.SERVICE_TYPE_NOT_FOUND);
        }
        return new LinkedHashSet<>(serviceTypes);
    }

    private Set<String> normalizeServiceCodes(Collection<String> serviceCodes) {
        if (serviceCodes == null) {
            return Set.of();
        }
        return serviceCodes.stream()
                .map(this::normalize)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private void validateCoordinatePair(AdminStoreUpdateRequestDto request) {
        if ((request.latitude() == null) != (request.longitude() == null)) {
            throw new StoreException(StoreErrorCode.INVALID_STORE_COORDINATES);
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }
}

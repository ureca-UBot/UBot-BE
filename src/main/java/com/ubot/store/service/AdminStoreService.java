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
import com.ubot.store.exception.DuplicateStoreException;
import com.ubot.store.exception.InvalidStoreCoordinatesException;
import com.ubot.store.exception.ServiceTypeNotFoundException;
import com.ubot.store.exception.StoreNotFoundException;
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
        Store existingStore = storeJpaRepository.findByStoreNameAndAddress(storeName, address)
                .orElse(null);

        if (existingStore != null && existingStore.isActive() && existingStore.getDeletedAt() == null) {
            throw new DuplicateStoreException();
        }

        Set<ServiceType> serviceTypes = resolveServiceTypes(request.serviceCodes());
        if (existingStore != null) {
            existingStore.restore(
                    storeName,
                    normalize(request.sido()),
                    normalize(request.sigungu()),
                    address,
                    request.latitude(),
                    request.longitude(),
                    normalize(request.phoneNumber()),
                    normalize(request.businessHours())
            );
            existingStore.replaceServiceTypes(serviceTypes);
            return AdminStoreResponseDto.from(existingStore);
        }

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

        if (request.storeName() != null) {
            store.updateStoreName(normalize(request.storeName()));
        }
        if (request.sido() != null) {
            store.updateSido(normalize(request.sido()));
        }
        if (request.sigungu() != null) {
            store.updateSigungu(normalize(request.sigungu()));
        }
        if (request.address() != null) {
            store.updateAddress(normalize(request.address()));
        }
        if (request.latitude() != null) {
            store.updateCoordinates(request.latitude(), request.longitude());
        }
        if (request.phoneNumber() != null) {
            store.updatePhoneNumber(normalize(request.phoneNumber()));
        }
        if (request.businessHours() != null) {
            store.updateBusinessHours(normalize(request.businessHours()));
        }
        if (request.serviceCodes() != null) {
            store.replaceServiceTypes(resolveServiceTypes(request.serviceCodes()));
        }

        return AdminStoreResponseDto.from(store);
    }

    public void deleteStore(Long storeId) {
        getActiveStore(storeId).deactivate();
    }

    private Store getActiveStore(Long storeId) {
        return storeJpaRepository.findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(storeId)
                .orElseThrow(StoreNotFoundException::new);
    }

    private Set<ServiceType> resolveServiceTypes(List<String> serviceCodes) {
        Set<String> normalizedCodes = normalizeServiceCodes(serviceCodes);
        if (normalizedCodes.isEmpty()) {
            return Set.of();
        }

        List<ServiceType> serviceTypes = serviceTypeJpaRepository
                .findAllByServiceCodeInAndIsActiveTrue(normalizedCodes);
        if (serviceTypes.size() != normalizedCodes.size()) {
            throw new ServiceTypeNotFoundException();
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
            throw new InvalidStoreCoordinatesException();
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }
}

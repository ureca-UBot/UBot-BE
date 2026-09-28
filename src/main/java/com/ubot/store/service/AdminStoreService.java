package com.ubot.store.service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ubot.common.PageResponseDto;
import com.ubot.store.repository.AdminStoreSpecification;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
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
        String storeName = normalizeCondition(request.storeName());
        String address = normalizeCondition(request.address());

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
                normalizeCondition(request.sido()),
                normalizeCondition(request.sigungu()),
                address,
                request.latitude(),
                request.longitude(),
                normalizeCondition(request.phoneNumber()),
                normalizeCondition(request.businessHours())
        );
        store.replaceServiceTypes(serviceTypes);

        return AdminStoreResponseDto.from(storeJpaRepository.save(store));
    }

    public AdminStoreResponseDto updateStore(Long storeId, AdminStoreUpdateRequestDto request) {
        validateCoordinatePair(request);
        Store store = getActiveStore(storeId);


        String newStoreName = request.storeName() != null
                ? normalizeCondition(request.storeName())
                : store.getStoreName();

        String newAddress = request.address() != null
                ? normalizeCondition(request.address())
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
            store.updateSido(normalizeCondition(request.sido()));
            updated = true;
        }
        if (request.sigungu() != null) {
            store.updateSigungu(normalizeCondition(request.sigungu()));
            updated = true;
        }
        if (request.latitude() != null) {
            store.updateCoordinates(request.latitude(), request.longitude());
            updated = true;
        }
        if (request.phoneNumber() != null) {
            store.updatePhoneNumber(normalizeCondition(request.phoneNumber()));
            updated = true;
        }
        if (request.businessHours() != null) {
            store.updateBusinessHours(normalizeCondition(request.businessHours()));
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
                .map(this::normalizeCondition)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private void validateCoordinatePair(AdminStoreUpdateRequestDto request) {
        if ((request.latitude() == null) != (request.longitude() == null)) {
            throw new StoreException(StoreErrorCode.INVALID_STORE_COORDINATES);
        }
    }

    @Transactional(readOnly = true)
    public PageResponseDto<AdminStoreResponseDto> getStores(
            String storeName,
            String phoneNumber,
            List<String> serviceCodes,
            int page,
            int size
    ) {
        String normalizedStoreName = normalizeCondition(storeName);
        String normalizedPhoneNumber = normalizeCondition(phoneNumber);
        Set<String> normalizedServiceCodes = normalizeServiceCodes(serviceCodes);

        validateServiceTypes(normalizedServiceCodes);

        Specification<Store> specification =
                AdminStoreSpecification.filter(
                        normalizedStoreName,
                        normalizedPhoneNumber,
                        normalizedServiceCodes
                );

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "storeId")
        );

        Page<Store> storePage =
                storeJpaRepository.findAll(specification, pageable);

        if (storePage.isEmpty()) {
            return PageResponseDto.from(
                    storePage.map(AdminStoreResponseDto::from)
            );
        }

        List<Long> storeIds = storePage.getContent().stream()
                .map(Store::getStoreId)
                .toList();

        Map<Long, Store> storesWithServices =
                storeJpaRepository
                        .findAllByStoreIdIn(storeIds)
                        .stream()
                        .collect(Collectors.toMap(
                                Store::getStoreId,
                                Function.identity()
                        ));

        List<AdminStoreResponseDto> content =
                storeIds.stream()
                        .map(storesWithServices::get)
                        .map(AdminStoreResponseDto::from)
                        .toList();

        Page<AdminStoreResponseDto> responsePage =
                new PageImpl<>(
                        content,
                        pageable,
                        storePage.getTotalElements()
                );

        return PageResponseDto.from(responsePage);
    }

    private String normalizeCondition(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private void validateServiceTypes(Set<String> serviceCodes) {
        if (serviceCodes.isEmpty()) {return;}

        List<ServiceType> serviceTypes = serviceTypeJpaRepository.findAllByServiceCodeInAndIsActiveTrue(serviceCodes);

        if (serviceTypes.size() != serviceCodes.size()) {
            throw new StoreException(StoreErrorCode.SERVICE_TYPE_NOT_FOUND);
        }
    }

}

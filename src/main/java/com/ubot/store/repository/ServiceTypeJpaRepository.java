package com.ubot.store.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ubot.store.entity.ServiceType;

public interface ServiceTypeJpaRepository extends JpaRepository<ServiceType, Long> {

    List<ServiceType> findAllByServiceCodeInAndIsActiveTrue(Collection<String> serviceCodes);
}

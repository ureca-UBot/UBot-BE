package com.ubot.telemap.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ubot.telemap.entity.SurgingAlert;

public interface SurgingAlertRepository extends JpaRepository<SurgingAlert, Long> {
}

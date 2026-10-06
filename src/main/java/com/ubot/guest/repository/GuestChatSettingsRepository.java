package com.ubot.guest.repository;

import com.ubot.guest.entity.GuestChatSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestChatSettingsRepository extends JpaRepository<GuestChatSettings, Long> {
}

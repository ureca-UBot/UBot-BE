package com.ubot.guest.service;

import com.ubot.guest.dto.request.GuestChatSettingsUpdateRequestDto;
import com.ubot.guest.dto.response.GuestChatSettingsResponseDto;
import com.ubot.guest.entity.GuestChatSettings;
import com.ubot.guest.repository.GuestChatSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuestChatSettingsService {
	private final GuestChatSettingsRepository guestChatSettingsRepository;

	@Transactional(readOnly = true)
	public GuestChatSettingsResponseDto getGuestChatSettings() {
		return GuestChatSettingsResponseDto.from(getSettings());
	}

	@Transactional
	public GuestChatSettingsResponseDto updateGuestChatSettings(
			GuestChatSettingsUpdateRequestDto requestDto, Long adminId
	) {
		GuestChatSettings settings = getSettings();
		settings.update(requestDto.maxQuestionCount(), adminId);
		return GuestChatSettingsResponseDto.from(settings);
	}

	private GuestChatSettings getSettings() {
		return guestChatSettingsRepository.findById(GuestChatSettings.SETTINGS_ID).orElseThrow();
	}
}

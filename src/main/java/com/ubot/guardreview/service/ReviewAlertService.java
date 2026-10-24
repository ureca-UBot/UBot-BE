package com.ubot.guardreview.service;

import com.ubot.guardreview.repository.ReviewRepository;
import com.ubot.notification.repository.NotificationRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewAlertService {
	private final ReviewRepository repository;
	private final NotificationRepository notifications;
	@Transactional
	public void flush() {
		repository.analysisLock();
		var admins = repository.admins();
		// 수신 관리자가 아직 없으면 발송 완료로 표시하지 않습니다.
		if (admins.isEmpty())
			return;
		for (var group : repository.pendingAlerts()) {
			long generation = repository.alertGeneration(group.candidateId());
			var candidate = repository.candidate(group.candidateId(), true);
			for (long admin : admins) {
				String key = "GUARD_REVIEW:" + group.candidateId() + ":" + generation + ":" + admin;
				notifications.insertWithoutReservationIfAbsent(admin, "GUARD_REVIEW", "위험 문장 검토 알림",
						candidate.category() + " · 신규 " + group.newCount() + "건 · 누적 " + group.totalCount() + "건", key,
						LocalDateTime.now());
				repository.attachNotification(group.candidateId(), key);
			}
			repository.markNotified(group.candidateId(), generation);
		}
	}
}

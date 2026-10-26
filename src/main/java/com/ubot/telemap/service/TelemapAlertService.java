package com.ubot.telemap.service;

import java.time.LocalDateTime;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.dto.response.SurgingAlertDetailResponse;
import com.ubot.telemap.dto.response.SurgingAlertSummaryResponse;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.enums.SurgingAlertStatus;
import com.ubot.telemap.exception.TelemapErrorCode;
import com.ubot.telemap.exception.TelemapException;
import com.ubot.telemap.repository.SurgingAlertRepository;
import com.ubot.user.entity.User;
import com.ubot.user.exception.UserErrorCode;
import com.ubot.user.exception.UserException;
import com.ubot.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TelemapAlertService {

    private static final Sort NEWEST_FIRST = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id")
    );

    private final SurgingAlertRepository surgingAlertRepository;
    private final UserRepository userRepository;

    public PageResponseDto<SurgingAlertSummaryResponse> getAlerts(
            SurgingAlertStatus status,
            RegionSido region,
            int page,
            int size
    ) {
        return PageResponseDto.from(surgingAlertRepository.findAllByFilters(
                status,
                region,
                PageRequest.of(page, size, NEWEST_FIRST)
        ).map(SurgingAlertSummaryResponse::from));
    }

    public SurgingAlertDetailResponse getAlert(Long alertId) {
        return SurgingAlertDetailResponse.from(findAlert(alertId));
    }

    /**
     * 대기 중인 알림의 질문 수를 연결된 클러스터에 지금까지 쌓인 질문 수로 갱신하고, 바뀐 알림 수를 돌려줍니다.
     * 클러스터가 연결되어 있지 않거나(NULL) 이미 정리되어 질문이 없는 알림은 갱신하지 않습니다.
     */
    @Transactional
    public int refreshPendingQuestionCounts() {
        return surgingAlertRepository.refreshPendingQuestionCounts();
    }

    @Transactional
    public SurgingAlertDetailResponse markProcessed(Long alertId, Long administratorId) {
        SurgingAlert alert = findAlert(alertId);

        if(alert.getSubject()==null)
            throw new TelemapException(TelemapErrorCode.UNPROCESSED_ALERT);

        User administrator = userRepository.findByIdAndDeletedAtIsNull(administratorId)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));
        int exists = surgingAlertRepository.markProcessedIfPending(
                alertId,
                administrator,
                LocalDateTime.now()
        );
        if(exists == 0) {
            throw new TelemapException(TelemapErrorCode.PROCESSED_ALERT);
        }

        SurgingAlert processedAlert = findAlert(alertId);
        return SurgingAlertDetailResponse.from(processedAlert);
    }

    private SurgingAlert findAlert(Long alertId) {
        return surgingAlertRepository.findById(alertId)
                .orElseThrow(() -> new TelemapException(TelemapErrorCode.SURGING_ALERT_NOT_FOUND));
    }
}

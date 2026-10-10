package com.ubot.notice.service;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.notice.dto.request.AdminNoticeRequestDto;
import com.ubot.notice.dto.response.AdminNoticeResponseDto;
import com.ubot.notice.entity.Notice;
import com.ubot.notice.enums.NoticeType;
import com.ubot.notice.exception.NoticeErrorCode;
import com.ubot.notice.exception.NoticeException;
import com.ubot.notice.repository.NoticeRepository;
import com.ubot.notice.repository.NoticeSpecification;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AdminNoticeService {

    private static final Sort NEWEST_FIRST = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("noticeId"));

    private final NoticeRepository noticeRepository;
    private final UserRepository userRepository;

    public AdminNoticeResponseDto createNotice(Long administratorId, AdminNoticeRequestDto request) {
        validateEndsAt(request.endsAt());
        Notice notice = noticeRepository.save(Notice.create(
                userRepository.getReferenceById(administratorId),
                request.type(),
                request.title().strip(),
                request.content().strip(),
                request.targetRegion(),
                request.endsAt()));
        log.info("공지를 등록했습니다: 공지ID={}, 유형={}", notice.getNoticeId(), notice.getType());
        return AdminNoticeResponseDto.from(notice);
    }

    /**
     * deleted가 null이면 삭제된 공지까지 모두, true면 삭제된 공지만, false면 삭제되지 않은 공지만 조회합니다.
     * region은 대상 지역이 정확히 일치하는 공지만 조회합니다.
     */
    @Transactional(readOnly = true)
    public PageResponseDto<AdminNoticeResponseDto> getNoticeList(
            NoticeType type, RegionSido region, Boolean deleted, int page, int size) {
        return PageResponseDto.from(noticeRepository.findAll(
                NoticeSpecification.deleted(deleted)
                        .and(NoticeSpecification.hasType(type))
                        .and(NoticeSpecification.hasTargetRegion(region)),
                PageRequest.of(page, size, NEWEST_FIRST)).map(AdminNoticeResponseDto::from));
    }

    /** 삭제된 공지도 이력으로 조회할 수 있습니다. */
    @Transactional(readOnly = true)
    public AdminNoticeResponseDto getNotice(Long noticeId) {
        return AdminNoticeResponseDto.from(noticeRepository.findById(noticeId)
                .orElseThrow(() -> new NoticeException(NoticeErrorCode.NOTICE_NOT_FOUND)));
    }

    public AdminNoticeResponseDto updateNotice(Long administratorId, Long noticeId, AdminNoticeRequestDto request) {
        Notice notice = getUndeletedNotice(noticeId);
        // 이미 노출이 끝난 공지의 다른 항목을 고칠 수 있도록, 종료 시각을 바꿀 때만 검증합니다.
        if (!Objects.equals(notice.getEndsAt(), request.endsAt())) {
            validateEndsAt(request.endsAt());
        }
        notice.update(
                userRepository.getReferenceById(administratorId),
                request.type(),
                request.title().strip(),
                request.content().strip(),
                request.targetRegion(),
                request.endsAt());
        log.info("공지를 수정했습니다: 공지ID={}", noticeId);
        return AdminNoticeResponseDto.from(notice);
    }

    public void deleteNotice(Long administratorId, Long noticeId) {
        getUndeletedNotice(noticeId).delete(userRepository.getReferenceById(administratorId));
        log.info("공지를 삭제했습니다: 공지ID={}", noticeId);
    }

    private Notice getUndeletedNotice(Long noticeId) {
        return noticeRepository.findByIdForUpdate(noticeId)
                .orElseThrow(() -> new NoticeException(NoticeErrorCode.NOTICE_NOT_FOUND));
    }

    private void validateEndsAt(LocalDateTime endsAt) {
        if (endsAt != null && !endsAt.isAfter(LocalDateTime.now())) {
            throw new NoticeException(NoticeErrorCode.INVALID_ENDS_AT);
        }
    }
}
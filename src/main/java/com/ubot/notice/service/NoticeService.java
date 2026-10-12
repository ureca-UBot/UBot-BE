package com.ubot.notice.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.notice.dto.response.NoticeBannerResponseDto;
import com.ubot.notice.dto.response.NoticeResponseDto;
import com.ubot.notice.dto.response.NoticeSummaryResponseDto;
import com.ubot.notice.entity.Notice;
import com.ubot.notice.enums.NoticeType;
import com.ubot.notice.exception.NoticeErrorCode;
import com.ubot.notice.exception.NoticeException;
import com.ubot.notice.repository.NoticeRepository;
import com.ubot.notice.repository.NoticeSpecification;
import com.ubot.ranking.enums.RegionSido;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NoticeService {

    /** 배너에 한 번에 내려 주는 종류별 최대 개수입니다. */
    static final int BANNER_LIMIT = 10;

    private static final Sort NEWEST_FIRST = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("noticeId"));

    private final NoticeRepository noticeRepository;

    /**
     * 배너로 보여 줄 공지와 장애를 각각 최신순으로 반환합니다.
     * 전체 공지와 region 공지를 함께 반환하고, region이 null이면 전체 공지만 반환합니다.
     */
    public NoticeBannerResponseDto getBanners(RegionSido region) {
        LocalDateTime now = LocalDateTime.now();
        return new NoticeBannerResponseDto(
                getBannerList(NoticeType.NOTICE, region, now),
                getBannerList(NoticeType.OUTAGE, region, now));
    }

    /**
     * 공지 목록을 최신순으로 반환합니다. 노출이 끝난 공지도 이력으로 포함하고, 삭제된 공지는 제외합니다.
     * region을 지정하면 전체 공지와 해당 지역 공지만 반환하고, 지정하지 않으면 모든 지역의 공지를 반환합니다.
     */
    public PageResponseDto<NoticeSummaryResponseDto> getNoticeList(
            NoticeType type, RegionSido region, int page, int size) {
        Specification<Notice> specification = NoticeSpecification.notDeleted()
                .and(NoticeSpecification.hasType(type));
        if (region != null) {
            specification = specification.and(NoticeSpecification.visibleIn(region));
        }
        return PageResponseDto.from(noticeRepository
                .findAll(specification, PageRequest.of(page, size, NEWEST_FIRST))
                .map(NoticeSummaryResponseDto::from));
    }

    public NoticeResponseDto getNotice(Long noticeId) {
        return NoticeResponseDto.from(noticeRepository.findByNoticeIdAndDeletedAtIsNull(noticeId)
                .orElseThrow(() -> new NoticeException(NoticeErrorCode.NOTICE_NOT_FOUND)));
    }

    private List<NoticeResponseDto> getBannerList(NoticeType type, RegionSido region, LocalDateTime now) {
        Specification<Notice> specification = NoticeSpecification.notDeleted()
                .and(NoticeSpecification.hasType(type))
                .and(NoticeSpecification.activeAt(now))
                .and(NoticeSpecification.visibleIn(region));
        return noticeRepository
                .findAll(specification, PageRequest.of(0, BANNER_LIMIT, NEWEST_FIRST))
                .map(NoticeResponseDto::from)
                .getContent();
    }
}
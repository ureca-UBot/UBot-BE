package com.ubot.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import com.ubot.notice.dto.response.NoticeBannerResponseDto;
import com.ubot.notice.entity.Notice;
import com.ubot.notice.enums.NoticeType;
import com.ubot.notice.exception.NoticeErrorCode;
import com.ubot.notice.exception.NoticeException;
import com.ubot.notice.repository.NoticeRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("공지·장애 조회 Service 테스트")
class NoticeServiceTest {

    @Mock
    private NoticeRepository noticeRepository;

    @InjectMocks
    private NoticeService noticeService;

    @Test
    @DisplayName("없거나 삭제된 공지를 조회하면 NOTICE-001 예외가 발생한다")
    void throwsWhenNoticeNotFound() {
        when(noticeRepository.findByNoticeIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> noticeService.getNotice(1L))
                .isInstanceOf(NoticeException.class)
                .extracting(e -> ((NoticeException) e).getErrorCode())
                .isEqualTo(NoticeErrorCode.NOTICE_NOT_FOUND);
    }

    @Test
    @DisplayName("배너는 공지와 장애를 각각 최대 10개씩 최신순으로 조회해 나눠 반환한다")
    @SuppressWarnings("unchecked")
    void splitsBannerByTypeWithLimit() {
        Notice notice = notice(1L, NoticeType.NOTICE);
        Notice outage = notice(2L, NoticeType.OUTAGE);
        when(noticeRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(notice)))
                .thenReturn(new PageImpl<>(List.of(outage)));

        NoticeBannerResponseDto result = noticeService.getBanners(null);

        assertThat(result.notices()).extracting("noticeId").containsExactly(1L);
        assertThat(result.outages()).extracting("noticeId").containsExactly(2L);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository, times(2)).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getAllValues()).allSatisfy(p -> {
            assertThat(p.getPageNumber()).isZero();
            assertThat(p.getPageSize()).isEqualTo(10);
            assertThat(p.getSort().getOrderFor("createdAt").isDescending()).isTrue();
            assertThat(p.getSort().getOrderFor("noticeId").isDescending()).isTrue();
        });
    }

    private Notice notice(Long id, NoticeType type) {
        Notice notice = Notice.create(null, type, "제목", "내용", null, null);
        ReflectionTestUtils.setField(notice, "noticeId", id);
        return notice;
    }
}
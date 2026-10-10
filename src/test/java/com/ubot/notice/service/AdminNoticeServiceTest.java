package com.ubot.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ubot.notice.dto.request.AdminNoticeRequestDto;
import com.ubot.notice.dto.response.AdminNoticeResponseDto;
import com.ubot.notice.entity.Notice;
import com.ubot.notice.enums.NoticeType;
import com.ubot.notice.exception.NoticeErrorCode;
import com.ubot.notice.exception.NoticeException;
import com.ubot.notice.repository.NoticeRepository;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.user.entity.User;
import com.ubot.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("관리자 공지·장애 Service 테스트")
class AdminNoticeServiceTest {

    private static final Long ADMIN_ID = 1L;

    @Mock
    private NoticeRepository noticeRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminNoticeService adminNoticeService;

    private final User administrator = mock(User.class);

    @BeforeEach
    void stubAdministrator() {
        org.mockito.Mockito.lenient().when(userRepository.getReferenceById(ADMIN_ID)).thenReturn(administrator);
    }

    @Test
    @DisplayName("노출 종료 시각이 과거이면 등록하지 않고 NOTICE-002 예외가 발생한다")
    void rejectsPastEndsAtOnCreate() {
        AdminNoticeRequestDto request = request(LocalDateTime.now().minusMinutes(1));

        assertThatThrownBy(() -> adminNoticeService.createNotice(ADMIN_ID, request))
                .isInstanceOf(NoticeException.class)
                .extracting(e -> ((NoticeException) e).getErrorCode())
                .isEqualTo(NoticeErrorCode.INVALID_ENDS_AT);
        // rejectsPastEndsAtOnCreate() 안
        verify(noticeRepository, never()).save(any(Notice.class));
    }

    @Test
    @DisplayName("공지를 등록하면 제목과 본문의 앞뒤 공백을 제거해 저장한다")
    void createsNoticeWithTrimmedText() {
        when(noticeRepository.save(any(Notice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AdminNoticeRequestDto request = new AdminNoticeRequestDto(
                NoticeType.NOTICE, "  제목  ", "  내용  ", null, null);

        AdminNoticeResponseDto response = adminNoticeService.createNotice(ADMIN_ID, request);

        assertThat(response.detail().title()).isEqualTo("제목");
        assertThat(response.detail().content()).isEqualTo("내용");
        assertThat(response.detail().targetRegion()).isNull();
        assertThat(response.detail().endsAt()).isNull();
    }

    @Test
    @DisplayName("종료 시각을 바꾸지 않으면 이미 지난 공지도 다른 항목을 수정할 수 있다")
    void updatesExpiredNoticeWhenEndsAtIsUnchanged() {
        LocalDateTime expiredAt = LocalDateTime.now().minusDays(1);
        Notice notice = notice(10L, expiredAt);
        when(noticeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(notice));

        AdminNoticeResponseDto response = adminNoticeService.updateNotice(ADMIN_ID, 10L,
                new AdminNoticeRequestDto(NoticeType.NOTICE, "수정 제목", "수정 내용", RegionSido.SEOUL, expiredAt));

        assertThat(response.detail().title()).isEqualTo("수정 제목");
        assertThat(response.detail().targetRegion()).isEqualTo(RegionSido.SEOUL);
        assertThat(response.detail().endsAt()).isEqualTo(expiredAt);
    }

    @Test
    @DisplayName("종료 시각을 과거 값으로 바꾸면 NOTICE-002 예외가 발생한다")
    void rejectsChangedPastEndsAtOnUpdate() {
        Notice notice = notice(10L, null);
        when(noticeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(notice));
        AdminNoticeRequestDto request = request(LocalDateTime.now().minusHours(1));

        assertThatThrownBy(() -> adminNoticeService.updateNotice(ADMIN_ID, 10L, request))
                .isInstanceOf(NoticeException.class)
                .extracting(e -> ((NoticeException) e).getErrorCode())
                .isEqualTo(NoticeErrorCode.INVALID_ENDS_AT);
        assertThat(notice.getTitle()).isEqualTo("제목");
    }

    @Test
    @DisplayName("없거나 이미 삭제된 공지를 수정·삭제하면 NOTICE-001 예외가 발생한다")
    void throwsWhenNoticeNotFound() {
        when(noticeRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminNoticeService.updateNotice(ADMIN_ID, 99L, request(null)))
                .isInstanceOf(NoticeException.class)
                .extracting(e -> ((NoticeException) e).getErrorCode())
                .isEqualTo(NoticeErrorCode.NOTICE_NOT_FOUND);
        assertThatThrownBy(() -> adminNoticeService.deleteNotice(ADMIN_ID, 99L))
                .isInstanceOf(NoticeException.class)
                .extracting(e -> ((NoticeException) e).getErrorCode())
                .isEqualTo(NoticeErrorCode.NOTICE_NOT_FOUND);
    }

    @Test
    @DisplayName("공지를 삭제하면 삭제 시각이 기록된다(soft delete)")
    void softDeletesNotice() {
        Notice notice = notice(10L, null);
        when(noticeRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(notice));

        adminNoticeService.deleteNotice(ADMIN_ID, 10L);

        assertThat(notice.getDeletedAt()).isNotNull();
        verify(noticeRepository, never()).delete(any(Notice.class));
    }

    private AdminNoticeRequestDto request(LocalDateTime endsAt) {
        return new AdminNoticeRequestDto(NoticeType.NOTICE, "제목", "내용", null, endsAt);
    }

    private Notice notice(Long id, LocalDateTime endsAt) {
        Notice notice = Notice.create(null, NoticeType.NOTICE, "제목", "내용", null, endsAt);
        ReflectionTestUtils.setField(notice, "noticeId", id);
        return notice;
    }
}
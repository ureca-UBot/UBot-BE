package com.ubot.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.ubot.inquiry.dto.request.InquiryCreateRequest;
import com.ubot.inquiry.dto.request.InquiryReplyRequest;
import com.ubot.inquiry.dto.request.InquiryUpdateRequest;
import com.ubot.inquiry.entity.Inquiry;
import com.ubot.inquiry.enums.InquiryStatus;
import com.ubot.inquiry.exception.InquiryErrorCode;
import com.ubot.inquiry.exception.InquiryException;
import com.ubot.inquiry.repository.InquiryRepository;
import com.ubot.user.entity.User;
import com.ubot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("고객문의 서비스 테스트")
class InquiryServiceTest {
    private static final long OWNER_ID = 11L;
    private static final long OTHER_ID = 12L;
    private static final long ADMIN_ID = 99L;
    private static final long INQUIRY_ID = 42L;

    @Mock InquiryRepository inquiryRepository;
    @Mock UserRepository userRepository;
    @InjectMocks InquiryService inquiryService;

    @Test
    @DisplayName("문의 등록 시 인증된 회원을 작성자로 지정하고 답변 대기 상태로 저장한다")
    void createUsesAuthenticatedUserAndStartsReceived() {
        User owner = user(OWNER_ID);
        when(userRepository.getReferenceById(OWNER_ID)).thenReturn(owner);
        when(inquiryRepository.save(any(Inquiry.class))).thenAnswer(invocation -> {
            Inquiry inquiry = invocation.getArgument(0);
            ReflectionTestUtils.setField(inquiry, "id", INQUIRY_ID);
            return inquiry;
        });

        var response = inquiryService.create(OWNER_ID, new InquiryCreateRequest("  제목  ", "  내용  "));

        assertThat(response.id()).isEqualTo(INQUIRY_ID);
        assertThat(response.title()).isEqualTo("제목");
        assertThat(response.content()).isEqualTo("내용");
        assertThat(response.status()).isEqualTo(InquiryStatus.RECEIVED);
        assertThat(response.adminReply()).isNull();
        verify(userRepository).getReferenceById(OWNER_ID);
    }

    @Test
    @DisplayName("내 문의 목록은 인증된 회원 ID로 조회한다")
    void myListQueriesOnlyAuthenticatedOwnersInquiries() {
        Inquiry own = inquiry(OWNER_ID);
        when(inquiryRepository.findByUserId(eq(OWNER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(own)));

        var response = inquiryService.getMine(OWNER_ID, 0, 20);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().id()).isEqualTo(INQUIRY_ID);
        verify(inquiryRepository).findByUserId(eq(OWNER_ID), any(Pageable.class));
    }

    @Test
    @DisplayName("작성자는 답변 전 문의의 제목과 내용을 수정할 수 있다")
    void ownerCanUpdateReceivedInquiry() {
        Inquiry inquiry = inquiry(OWNER_ID);
        when(inquiryRepository.findByIdForUpdate(INQUIRY_ID)).thenReturn(Optional.of(inquiry));

        var response = inquiryService.update(OWNER_ID, INQUIRY_ID,
                new InquiryUpdateRequest(" 새 제목 ", " 새 내용 "));

        assertThat(response.title()).isEqualTo("새 제목");
        assertThat(response.content()).isEqualTo("새 내용");
        assertThat(response.status()).isEqualTo(InquiryStatus.RECEIVED);
    }

    @Test
    @DisplayName("다른 회원은 문의를 수정하거나 삭제할 수 없다")
    void anotherUserCannotUpdateOrDeleteInquiry() {
        Inquiry inquiry = inquiry(OWNER_ID);
        when(inquiryRepository.findByIdForUpdate(INQUIRY_ID)).thenReturn(Optional.of(inquiry));

        assertCode(InquiryErrorCode.NOT_FOUND, () -> inquiryService.update(
                OTHER_ID, INQUIRY_ID, new InquiryUpdateRequest("변경", null)));
        assertCode(InquiryErrorCode.NOT_FOUND, () -> inquiryService.delete(OTHER_ID, INQUIRY_ID));
        assertThat(inquiry.getTitle()).isEqualTo("원래 제목");
        verify(inquiryRepository, never()).delete(any(Inquiry.class));
    }

    @Test
    @DisplayName("작성자는 답변 전 문의를 실제로 삭제할 수 있다")
    void ownerCanHardDeleteReceivedInquiry() {
        Inquiry inquiry = inquiry(OWNER_ID);
        when(inquiryRepository.findByIdForUpdate(INQUIRY_ID)).thenReturn(Optional.of(inquiry));

        inquiryService.delete(OWNER_ID, INQUIRY_ID);

        verify(inquiryRepository).delete(inquiry);
    }

    @Test
    @DisplayName("관리자 답변으로 처리 완료되면 수정·삭제·재답변을 거부한다")
    void replyResolvesInquiryAndBlocksFurtherChanges() {
        Inquiry inquiry = inquiry(OWNER_ID);
        User admin = user(ADMIN_ID);
        when(inquiryRepository.findByIdForUpdate(INQUIRY_ID)).thenReturn(Optional.of(inquiry));
        when(userRepository.getReferenceById(ADMIN_ID)).thenReturn(admin);

        var response = inquiryService.reply(ADMIN_ID, INQUIRY_ID, new InquiryReplyRequest("  답변입니다.  "));

        assertThat(response.inquiry().status()).isEqualTo(InquiryStatus.RESOLVED);
        assertThat(response.inquiry().adminReply()).isEqualTo("답변입니다.");
        assertThat(response.inquiry().resolvedAt()).isNotNull();
        assertThat(response.resolvedById()).isEqualTo(ADMIN_ID);
        assertCode(InquiryErrorCode.ALREADY_RESOLVED, () -> inquiryService.update(
                OWNER_ID, INQUIRY_ID, new InquiryUpdateRequest("변경", null)));
        assertCode(InquiryErrorCode.ALREADY_RESOLVED, () -> inquiryService.delete(OWNER_ID, INQUIRY_ID));
        assertCode(InquiryErrorCode.ALREADY_RESOLVED, () -> inquiryService.reply(
                ADMIN_ID, INQUIRY_ID, new InquiryReplyRequest("두 번째 답변")));
        verify(inquiryRepository, never()).delete(any(Inquiry.class));
    }

    @Test
    @DisplayName("수정 항목이 없거나 문의가 존재하지 않으면 오류를 반환한다")
    void emptyPatchAndMissingInquiryAreRejected() {
        assertCode(InquiryErrorCode.INVALID_UPDATE, () -> inquiryService.update(
                OWNER_ID, INQUIRY_ID, new InquiryUpdateRequest(null, null)));
        when(inquiryRepository.findByIdForUpdate(INQUIRY_ID)).thenReturn(Optional.empty());
        assertCode(InquiryErrorCode.NOT_FOUND, () -> inquiryService.delete(OWNER_ID, INQUIRY_ID));
    }

    private Inquiry inquiry(long ownerId) {
        Inquiry inquiry = Inquiry.create(user(ownerId), "원래 제목", "원래 내용");
        ReflectionTestUtils.setField(inquiry, "id", INQUIRY_ID);
        return inquiry;
    }

    private User user(long id) {
        return User.builder().id(id).name("테스트 회원").build();
    }

    private void assertCode(InquiryErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(InquiryException.class)
                .extracting(error -> ((InquiryException) error).getErrorCode())
                .isEqualTo(code);
    }
}

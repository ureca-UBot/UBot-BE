package com.ubot.inquiry.service;

import com.ubot.common.PageResponseDto;
import com.ubot.inquiry.enums.InquiryStatus;
import com.ubot.inquiry.dto.request.InquiryCreateRequest;
import com.ubot.inquiry.dto.request.InquiryReplyRequest;
import com.ubot.inquiry.dto.request.InquiryUpdateRequest;
import com.ubot.inquiry.dto.response.AdminInquiryResponse;
import com.ubot.inquiry.dto.response.InquiryResponse;
import com.ubot.inquiry.entity.Inquiry;
import com.ubot.inquiry.exception.InquiryErrorCode;
import com.ubot.inquiry.exception.InquiryException;
import com.ubot.inquiry.repository.InquiryRepository;
import com.ubot.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class InquiryService {
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final InquiryRepository inquiryRepository;
    private final UserRepository userRepository;

    public InquiryResponse create(Long userId, InquiryCreateRequest request) {
        Inquiry inquiry = inquiryRepository.save(Inquiry.create(
                userRepository.getReferenceById(userId), request.title(), request.content()));
        return InquiryResponse.from(inquiry);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<InquiryResponse> getMine(Long userId, int page, int size) {
        return PageResponseDto.from(inquiryRepository.findByUserId(userId,
                PageRequest.of(page, size, NEWEST_FIRST)).map(InquiryResponse::from));
    }

    public InquiryResponse update(Long userId, Long inquiryId, InquiryUpdateRequest request) {
        if (request.title() == null && request.content() == null) {
            throw new InquiryException(InquiryErrorCode.INVALID_UPDATE);
        }
        Inquiry inquiry = getOwnedForUpdate(userId, inquiryId);
        requireReceived(inquiry);
        inquiry.update(request.title(), request.content());
        return InquiryResponse.from(inquiry);
    }

    public void delete(Long userId, Long inquiryId) {
        Inquiry inquiry = getOwnedForUpdate(userId, inquiryId);
        requireReceived(inquiry);
        inquiryRepository.delete(inquiry);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<AdminInquiryResponse> getAll(InquiryStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, NEWEST_FIRST);
        return PageResponseDto.from((status == null
                ? inquiryRepository.findAll(pageable)
                : inquiryRepository.findByStatus(status, pageable)).map(AdminInquiryResponse::from));
    }

    @Transactional(readOnly = true)
    public AdminInquiryResponse getForAdmin(Long inquiryId) {
        return AdminInquiryResponse.from(inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new InquiryException(InquiryErrorCode.NOT_FOUND)));
    }

    public AdminInquiryResponse reply(Long administratorId, Long inquiryId, InquiryReplyRequest request) {
        Inquiry inquiry = inquiryRepository.findByIdForUpdate(inquiryId)
                .orElseThrow(() -> new InquiryException(InquiryErrorCode.NOT_FOUND));
        requireReceived(inquiry);
        inquiry.resolve(userRepository.getReferenceById(administratorId), request.reply());
        return AdminInquiryResponse.from(inquiry);
    }

    private Inquiry getOwnedForUpdate(Long userId, Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findByIdForUpdate(inquiryId)
                .orElseThrow(() -> new InquiryException(InquiryErrorCode.NOT_FOUND));
        if (!inquiry.getUser().getId().equals(userId)) {
            throw new InquiryException(InquiryErrorCode.NOT_FOUND);
        }
        return inquiry;
    }

    private void requireReceived(Inquiry inquiry) {
        if (inquiry.getStatus() != InquiryStatus.RECEIVED) {
            throw new InquiryException(InquiryErrorCode.ALREADY_RESOLVED);
        }
    }
}

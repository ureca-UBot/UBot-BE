package com.ubot.unanswered.service;

import com.ubot.common.GlobalException;
import com.ubot.common.PageResponseDto;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.faq.dto.request.FaqCreateRequestDto;
import com.ubot.faq.dto.response.FaqResponseDto;
import com.ubot.faq.service.FaqService;
import com.ubot.unanswered.dto.request.UnansweredGroupFaqCreateRequestDto;
import com.ubot.unanswered.dto.request.UnansweredGroupStatusUpdateRequestDto;
import com.ubot.unanswered.dto.response.UnansweredGroupDetailResponseDto;
import com.ubot.unanswered.dto.response.UnansweredGroupResponseDto;
import com.ubot.unanswered.entity.UnansweredQuestionGroup;
import com.ubot.unanswered.enums.UnansweredGroupStatus;
import com.ubot.unanswered.exception.UnansweredErrorCode;
import com.ubot.unanswered.exception.UnansweredException;
import com.ubot.unanswered.repository.UnansweredQuestionGroupRepository;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class UnansweredGroupService {
	private static final Set<Integer> ALLOWED_PAGE_SIZES = Set.of(10, 20, 50);
	private static final Map<String, Sort> SORTS = Map.of(
			"recent", Sort.by(Sort.Order.desc("lastOccurredAt"), Sort.Order.desc("id")),
			"count", Sort.by(Sort.Order.desc("questionCount"), Sort.Order.desc("lastOccurredAt"), Sort.Order.desc("id"))
	);

	private final UnansweredQuestionGroupRepository unansweredQuestionGroupRepository;
	private final UnansweredQuestionRepository unansweredQuestionRepository;
	private final FaqService faqService;

	@Transactional(readOnly = true)
	public PageResponseDto<UnansweredGroupResponseDto> getUnansweredGroupList(
			UnansweredGroupStatus status,
			int minCount,
			String sort,
			int page,
			int size
	){
		if(!ALLOWED_PAGE_SIZES.contains(size) || !SORTS.containsKey(sort)){
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
		}
		PageRequest pageRequest = PageRequest.of(page, size, SORTS.get(sort));
		Page<UnansweredQuestionGroup> groups = status == null
				? unansweredQuestionGroupRepository.findAllByQuestionCountGreaterThanEqual(minCount, pageRequest)
				: unansweredQuestionGroupRepository.findAllByStatusAndQuestionCountGreaterThanEqual(status, minCount, pageRequest);
		return PageResponseDto.from(groups.map(UnansweredGroupResponseDto::from));
	}

	@Transactional(readOnly = true)
	public UnansweredGroupDetailResponseDto getUnansweredGroup(Long groupId){
		UnansweredQuestionGroup group = findGroup(groupId);
		return UnansweredGroupDetailResponseDto.from(
				group,
				unansweredQuestionRepository.findAllByGroupIdOrderByCreatedAtDesc(groupId)
		);
	}

	@Transactional
	public UnansweredGroupResponseDto updateUnansweredGroupStatus(
			Long groupId,
			UnansweredGroupStatusUpdateRequestDto requestDto
	){
		UnansweredQuestionGroup group = findGroup(groupId);
		if(group.getResolvedFaqId() != null){
			throw new UnansweredException(UnansweredErrorCode.UNANSWERED_GROUP_ALREADY_RESOLVED);
		}
		group.updateStatus(requestDto.status());
		log.info("미응답 질문 그룹 상태를 변경했습니다: 그룹ID={}, 상태={}", groupId, requestDto.status());
		return UnansweredGroupResponseDto.from(group);
	}

	@Transactional
	public UnansweredGroupResponseDto createUnansweredGroupFaq(
			Long groupId,
			UnansweredGroupFaqCreateRequestDto requestDto,
			Long adminId
	){
		UnansweredQuestionGroup group = unansweredQuestionGroupRepository.findByIdForUpdate(groupId)
				.orElseThrow(() -> new UnansweredException(UnansweredErrorCode.UNANSWERED_GROUP_NOT_FOUND));
		if(group.getResolvedFaqId() != null){
			throw new UnansweredException(UnansweredErrorCode.UNANSWERED_GROUP_ALREADY_RESOLVED);
		}

		String question = StringUtils.hasText(requestDto.question())
				? requestDto.question().strip()
				: group.getRepresentativeQuestion();
		FaqResponseDto faq = faqService.createFaq(
				new FaqCreateRequestDto(requestDto.categoryId(), question, requestDto.answer(), requestDto.intent()),
				adminId
		);

		group.resolve(faq.id());
		log.info("미응답 질문 그룹을 FAQ로 전환했습니다: 그룹ID={}, FAQID={}, 관리자ID={}", groupId, faq.id(), adminId);
		return UnansweredGroupResponseDto.from(group);
	}

	private UnansweredQuestionGroup findGroup(Long groupId){
		return unansweredQuestionGroupRepository.findById(groupId)
				.orElseThrow(() -> new UnansweredException(UnansweredErrorCode.UNANSWERED_GROUP_NOT_FOUND));
	}
}

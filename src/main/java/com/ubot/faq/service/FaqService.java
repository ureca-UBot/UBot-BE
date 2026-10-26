package com.ubot.faq.service;

import com.ubot.common.PageResponseDto;
import com.ubot.faq.dto.request.FaqRestoreRequestDto;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.user.exception.UserErrorCode;
import com.ubot.user.exception.UserException;
import com.ubot.faq.dto.request.FaqCreateRequestDto;
import com.ubot.faq.dto.request.FaqUpdateRequestDto;
import com.ubot.faq.dto.response.FaqResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.entity.FaqQuestionVariant;
import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.repository.FaqCategoryRepository;
import com.ubot.faq.repository.OldFaqRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.repository.FaqQuestionVariantRepository;
import com.ubot.user.entity.User;
import com.ubot.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaqService {

	private final FaqRepository faqRepository;
	private final FaqVectorService faqVectorService;
	private final FaqQuestionVariantRepository faqQuestionVariantRepository;
	private final FaqCategoryRepository faqCategoryRepository;
	private final OldFaqRepository oldFaqRepository;
	private final UserRepository userRepository;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public FaqResponseDto createFaq(FaqCreateRequestDto requestDto, Long adminId){

		User admin = userRepository.findById(adminId).orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));
		FaqCategory category = faqCategoryRepository.findByIdAndDeletedAtIsNull(requestDto.categoryId()).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_CATEGORY_NOT_FOUND));

		Faq faq = Faq.builder()
				.admin(admin)
				.faqCategory(category)
				.question(requestDto.question())
				.answer(requestDto.answer())
				.intent(requestDto.intent())
				.cacheable(requestDto.cacheable())
				.createdAt(LocalDateTime.now())
				.updatedAt(LocalDateTime.now())
				.build();

		Faq savedFaq = faqRepository.save(faq);

		List<String> similarQuestions = uniqueSimilarQuestions(savedFaq.getQuestion(), requestDto.similarQuestions());
		List<String> questions = new ArrayList<>();
		questions.add(savedFaq.getQuestion());
		questions.addAll(similarQuestions);
		for (String question : questions) {
			createVariant(savedFaq, question);
		}
		log.info("FAQ를 생성했습니다: FAQID={}, 관리자ID={}, 카테고리ID={}", savedFaq.getId(), adminId, category.getId());
		eventPublisher.publishEvent(new FaqChangedEvent(Set.of(savedFaq.getId())));

		return FaqResponseDto.from(savedFaq, similarQuestions);
	}

	public FaqResponseDto getActiveFaq(Long faqId){
		Faq faq = faqRepository.findActiveById(faqId).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
		return toResponse(faq);
	}

	public PageResponseDto<FaqResponseDto> getActiveFaqList(int page, int size, String keyword, Long categoryId){
		String normalizedKeyword = StringUtils.hasText(keyword) ? keyword : "";

		Page<Faq> faqPage = faqRepository.
				findAllActives(PageRequest.of(page, size, Sort.by(
						Sort.Order.asc("id")
				)), normalizedKeyword, categoryId);


		return toPageResponse(faqPage);
	}

	public PageResponseDto<FaqResponseDto> getDeletedFaqList(int page, int size){
		Page<Faq> faqPage = faqRepository.
				findAllDeletedFaq(PageRequest.of(page, size, Sort.by(
						Sort.Order.desc("deletedAt"),
						Sort.Order.desc("id")
				)));

		return toPageResponse(faqPage);
	}

	public PageResponseDto<FaqResponseDto> getActiveFaqListByFaqCategoryId(int page, int size, Long faqCategoryId){
		Page<Faq> faqPage = faqRepository.
				findAllByFaqCategoryIdAndDeletedAtIsNull(faqCategoryId, PageRequest.of(page, size, Sort.by(
						Sort.Order.asc("id")
				)));

		return toPageResponse(faqPage);
	}

	@Transactional
	public FaqResponseDto updateActiveFaq(FaqUpdateRequestDto requestDto, Long adminId){

		Faq faq = faqRepository.findActiveById(requestDto.id()).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
		User updatedBy = userRepository.findById(adminId).orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));

		OldFaq oldFaq = OldFaq.from(faq, updatedBy);
		oldFaqRepository.saveAndFlush(oldFaq);

		FaqCategory category = faqCategoryRepository.findByIdAndDeletedAtIsNull(requestDto.categoryId()).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_CATEGORY_NOT_FOUND));

		boolean questionChanged =
		        !faq.getQuestion().equals(requestDto.question());
		faq.update(
		        category,
		        requestDto.question(),
		        requestDto.answer(),
		        requestDto.intent(),
		        requestDto.cacheable()
		);

		Faq savedFaq = faqRepository.save(faq);

		List<FaqQuestionVariant> existing = new ArrayList<>(
				faqQuestionVariantRepository.findByOriginFaqIdOrderByIdAsc(savedFaq.getId()));
		if (questionChanged) {
			for (FaqQuestionVariant variant : existing) {
				deleteVariant(variant);
			}
			existing.clear();
		}

		List<String> questions = new ArrayList<>();
		List<String> similarQuestions = uniqueSimilarQuestions(savedFaq.getQuestion(), requestDto.similarQuestions());
		questions.add(savedFaq.getQuestion());
		questions.addAll(similarQuestions);
		for (String question : questions) {
			FaqQuestionVariant kept = null;
			for (FaqQuestionVariant variant : existing) {
				if (variant.getQuestion().equals(question)) {
					kept = variant;
					break;
				}
			}
			if (kept == null) {
				createVariant(savedFaq, question);
			} else {
				existing.remove(kept);
				faqVectorService.updateQuestionVectorVersionForVariant(
						kept.getId(), savedFaq.getVersion());
			}
		}
		for (FaqQuestionVariant removed : existing) {
			deleteVariant(removed);
		}
		log.info("FAQ를 수정했습니다: FAQID={}, 관리자ID={}, 카테고리ID={}", savedFaq.getId(), adminId, category.getId());
		eventPublisher.publishEvent(new FaqChangedEvent(Set.of(savedFaq.getId())));
		return FaqResponseDto.from(savedFaq, similarQuestions);
	}

	private List<String> uniqueSimilarQuestions(String originalQuestion, List<String> requestedQuestions) {
		Set<String> seen = new HashSet<>();
		seen.add(normalizeQuestion(originalQuestion));
		return requestedQuestions.stream()
				.filter(question -> seen.add(normalizeQuestion(question)))
				.toList();
	}

	private String normalizeQuestion(String question) {
		return question.strip().replaceAll("\\s+", " ");
	}

	private void createVariant(Faq faq, String question) {
		FaqQuestionVariant variant = faqQuestionVariantRepository.save(
				FaqQuestionVariant.builder().originFaq(faq).question(question).build());
		faqVectorService.saveQuestionVectorForVariant(variant.getId(), faq.getVersion(), question);
	}

	private void deleteVariant(FaqQuestionVariant variant) {
		faqQuestionVariantRepository.delete(variant);
	}

	private PageResponseDto<FaqResponseDto> toPageResponse(Page<Faq> faqPage) {
		Map<Long, List<String>> questionsByFaqId = new HashMap<>();
		if (!faqPage.isEmpty()) {
			List<Long> faqIds = faqPage.getContent().stream().map(Faq::getId).toList();
			for (FaqQuestionVariant variant : faqQuestionVariantRepository
					.findByOriginFaqIdsOrderByFaqIdAndId(faqIds)) {
				questionsByFaqId.computeIfAbsent(variant.getOriginFaq().getId(), ignored -> new ArrayList<>())
						.add(variant.getQuestion());
			}
		}

		return PageResponseDto.from(faqPage.map(faq -> {
			List<String> similarQuestions = new ArrayList<>(
					questionsByFaqId.getOrDefault(faq.getId(), List.of()));
			similarQuestions.removeIf(question -> question.equals(faq.getQuestion()));
			return FaqResponseDto.from(faq, similarQuestions);
		}));
	}

	private FaqResponseDto toResponse(Faq faq) {
		List<String> similarQuestions = new ArrayList<>(faqQuestionVariantRepository
				.findByOriginFaqIdOrderByIdAsc(faq.getId())
				.stream().map(FaqQuestionVariant::getQuestion).toList());
		similarQuestions.removeIf(question -> question.equals(faq.getQuestion()));
		return FaqResponseDto.from(faq, similarQuestions);
	}

	@Transactional
	public void deleteFaq(Long faqId){
		Faq faq = faqRepository.findActiveById(faqId).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));

		faq.delete();
		eventPublisher.publishEvent(new FaqChangedEvent(Set.of(faqId)));
		log.info("FAQ를 삭제했습니다: FAQID={}", faqId);
	}

	@Transactional
	public void restoreFaqs(FaqRestoreRequestDto requestDto){
		List<Faq> faqs = faqRepository.findDeletedFaqByFaqIds(requestDto.faqIds());
		for(Faq faq : faqs){
			faq.restore();
			if (!faqQuestionVariantRepository.existsByOriginFaqIdAndQuestion(faq.getId(), faq.getQuestion())) {
				createVariant(faq, faq.getQuestion());
			}
		}
		log.info("FAQ를 복구했습니다: 요청건수={}, 복구건수={}", requestDto.faqIds().size(), faqs.size());
		if (!faqs.isEmpty()) {
			Set<Long> restoredIds = new HashSet<>();
			faqs.forEach(faq -> restoredIds.add(faq.getId()));
			eventPublisher.publishEvent(new FaqChangedEvent(restoredIds));
		}
	}
}


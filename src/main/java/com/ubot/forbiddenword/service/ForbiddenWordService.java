package com.ubot.forbiddenword.service;

import com.ubot.common.GlobalException;
import com.ubot.common.PageResponseDto;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.forbiddenword.dto.request.ForbiddenWordCreateRequestDto;
import com.ubot.forbiddenword.dto.request.ForbiddenWordStatusUpdateRequestDto;
import com.ubot.forbiddenword.dto.request.ForbiddenWordUpdateRequestDto;
import com.ubot.forbiddenword.dto.response.ForbiddenWordResponseDto;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.exception.ForbiddenWordException;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ForbiddenWordService {
	private static final Set<Integer> ALLOWED_PAGE_SIZES = Set.of(10, 20, 50);

	private final ForbiddenWordRepository forbiddenWordRepository;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public ForbiddenWordResponseDto createForbiddenWord(ForbiddenWordCreateRequestDto requestDto) {
		String word = normalizeWord(requestDto.word());
		if (forbiddenWordRepository.existsByWord(word)) {
			throw new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_EXIST);
		}

		LocalDateTime now = LocalDateTime.now();
		ForbiddenWord forbiddenWord;
		try {
			forbiddenWord = forbiddenWordRepository.saveAndFlush(ForbiddenWord.builder()
					.word(word)
					.status(ForbiddenWordStatus.ACTIVE)
					.createdAt(now)
					.updatedAt(now)
					.build());
		} catch (DataIntegrityViolationException exception) {
			// 중복 확인 이후 다른 관리자가 같은 단어를 먼저 저장한 경우입니다.
			throw new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_EXIST);
		}

		eventPublisher.publishEvent(new ForbiddenWordChangedEvent());
		return ForbiddenWordResponseDto.from(forbiddenWord);
	}

	@Transactional(readOnly = true)
	public PageResponseDto<ForbiddenWordResponseDto> getForbiddenWordList(int page, int size) {
		if (!ALLOWED_PAGE_SIZES.contains(size)) {
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
		}
		Page<ForbiddenWordResponseDto> forbiddenWordPage = forbiddenWordRepository
				.findAll(PageRequest.of(page, size, Sort.by(Sort.Order.desc("id"))))
				.map(ForbiddenWordResponseDto::from);
		return PageResponseDto.from(forbiddenWordPage);
	}

	@Transactional
	public ForbiddenWordResponseDto updateForbiddenWord(Long forbiddenWordId, ForbiddenWordUpdateRequestDto requestDto) {
		ForbiddenWord forbiddenWord = getForbiddenWordForUpdate(forbiddenWordId);

		String word = normalizeWord(requestDto.word());
		if (forbiddenWordRepository.existsByWordAndIdNot(word, forbiddenWordId)) {
			throw new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_EXIST);
		}

		// 단어만 바꾸고 상태는 유지합니다.
		forbiddenWord.updateWord(word);
		try {
			forbiddenWordRepository.flush();
		} catch (DataIntegrityViolationException exception) {
			// 중복 확인 이후 다른 관리자가 같은 단어로 먼저 저장한 경우입니다.
			throw new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_EXIST);
		}

		eventPublisher.publishEvent(new ForbiddenWordChangedEvent());
		return ForbiddenWordResponseDto.from(forbiddenWord);
	}

	@Transactional
	public ForbiddenWordResponseDto updateForbiddenWordStatus(
			Long forbiddenWordId, ForbiddenWordStatusUpdateRequestDto requestDto
	) {
		ForbiddenWord forbiddenWord = getForbiddenWordForUpdate(forbiddenWordId);

		if (requestDto.status() == null) {
			throw new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_STATUS_REQUIRED);
		}
		forbiddenWord.updateStatus(requestDto.status());

		eventPublisher.publishEvent(new ForbiddenWordChangedEvent());
		return ForbiddenWordResponseDto.from(forbiddenWord);
	}

	private ForbiddenWord getForbiddenWordForUpdate(Long forbiddenWordId) {
		return forbiddenWordRepository.findByIdForUpdate(forbiddenWordId)
				.orElseThrow(() -> new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_NOT_FOUND));
	}

	private String normalizeWord(String word) {
		if (word == null || word.isBlank()) {
			throw new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_REQUIRED);
		}
		return word.strip();
	}
}

package com.ubot.faq.service;

import com.ubot.faq.config.FaqCacheProperties;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 시맨틱 판정 후 DB의 최신 승인·내용·버전을 검증해 공식 답변을 반환합니다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaqCacheService {
    private final FaqRepository repository;
    private final FaqCacheProperties properties;

    public Optional<String> getOfficialAnswer(FaqSearchResponseDto selected) {
        if (!properties.isEnabled() || !selected.cacheable() || selected.intent() != Intent.GENERAL) {
            return Optional.empty();
        }
        try {
            return repository.findActiveById(selected.faqId())
                    .filter(faq -> faq.isCacheable() && faq.getIntent() == Intent.GENERAL
                            && Objects.equals(faq.getVersion(), selected.version())
                            && Objects.equals(faq.getQuestion(), selected.question())
                            && Objects.equals(faq.getAnswer(), selected.answer())
                            && StringUtils.hasText(faq.getAnswer()))
                    .map(Faq::getAnswer);
        } catch (DataAccessException exception) {
            log.warn("FAQ approval recheck failed: faqId={}; direct answer skipped", selected.faqId());
            return Optional.empty();
        }
    }
}

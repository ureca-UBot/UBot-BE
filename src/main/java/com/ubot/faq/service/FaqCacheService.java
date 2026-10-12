package com.ubot.faq.service;

import com.ubot.faq.config.FaqCacheProperties;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Stores official answers only. Current DB approval is checked even on a Redis hit. */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaqCacheService {
    private final StringRedisTemplate redis;
    private final FaqRepository repository;
    private final ObjectMapper mapper;
    private final FaqCacheProperties properties;

    public Optional<String> getOrLoadOfficialAnswer(FaqSearchResponseDto selected) {
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
                    .map(faq -> readOrPopulate(selected));
        } catch (DataAccessException exception) {
            log.warn("FAQ approval recheck failed: faqId={}; direct answer skipped", selected.faqId());
            return Optional.empty();
        }
    }

    private String readOrPopulate(FaqSearchResponseDto faq) {
        String key = "faq:answer:" + faq.faqId() + ":v" + faq.version();
        CachedAnswer expected = new CachedAnswer("FAQ_OFFICIAL", 1, faq.faqId(),
                faq.question(), faq.answer(), faq.version());
        try {
            String value = redis.opsForValue().get(key);
            if (value != null && expected.equals(mapper.readValue(value, CachedAnswer.class))) {
                log.info("FAQ official cache HIT: faqId={}, version={}", faq.faqId(), faq.version());
                return faq.answer();
            }
        } catch (DataAccessException | JacksonException exception) {
            log.warn("FAQ cache read failed: faqId={}; using verified DB answer", faq.faqId());
        }
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(expected), properties.getTtl());
        } catch (DataAccessException | JacksonException exception) {
            log.warn("FAQ cache write failed: faqId={}; using verified DB answer", faq.faqId());
        }
        log.info("FAQ official cache MISS/DB: faqId={}, version={}", faq.faqId(), faq.version());
        return faq.answer();
    }

    public record CachedAnswer(
            String kind,
            int schemaVersion,
            Long faqId,
            String question,
            String answer,
            int version
    ) {
    }
}

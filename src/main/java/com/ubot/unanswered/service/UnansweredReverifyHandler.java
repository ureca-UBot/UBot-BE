package com.ubot.unanswered.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ubot.faq.service.FaqChangedEvent;
import com.ubot.outbox.OutboxEventHandler;
import com.ubot.outbox.OutboxEventRepository;
import com.ubot.outbox.OutboxEventType;
import com.ubot.unanswered.repository.UnansweredReverifyRepository;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

@Slf4j
@Component
public class UnansweredReverifyHandler implements OutboxEventHandler {

    private final OutboxEventRepository outboxEventRepository;
    private final UnansweredReverifyRepository reverifyRepository;
    private final double threshold;

    public UnansweredReverifyHandler(
            OutboxEventRepository outboxEventRepository,
            UnansweredReverifyRepository reverifyRepository,
            @Value("${CHAT_CONFIDENCE_THRESHOLD:0.75}") double threshold
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.reverifyRepository = reverifyRepository;
        this.threshold = threshold;
    }

    @EventListener
    public void onFaqChanged(FaqChangedEvent event) {
        event.faqIds().forEach(faqId ->
                outboxEventRepository.enqueue(OutboxEventType.FAQ_REVERIFY, String.valueOf(faqId), Map.of("faqId", faqId)));
    }

    @Override
    public OutboxEventType type() {
        return OutboxEventType.FAQ_REVERIFY;
    }

    @Override
    public void handle(JsonNode payload) {
        long faqId = payload.get("faqId").asLong();
        int matched = reverifyRepository.reverify(faqId, threshold);
        log.info("FAQ 변경 후 미응답 질문을 다시 검증했습니다: FAQID={}, 답할 수 있게 된 질문수={}", faqId, matched);
    }
}

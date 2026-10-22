package com.ubot.telemap.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.ubot.chat.entity.QuestionLog;
import com.ubot.outbox.OutboxEventRepository;
import com.ubot.outbox.OutboxEventType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TelemapOutboxService {

    private final OutboxEventRepository outboxEventRepository;

    public void enqueueSurgingQuestions(List<QuestionLog> questionLogs) {
        questionLogs.forEach(questionLog -> outboxEventRepository.enqueue(
                OutboxEventType.SURGING_QUESTION,
                String.valueOf(questionLog.getId()),
                Map.of("questionLogId", questionLog.getId())
        ));
    }
}

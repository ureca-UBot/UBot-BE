package com.ubot.telemap.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.dto.SurgingQuestionEmbedding;
import com.ubot.outbox.OutboxEventRepository;
import com.ubot.outbox.OutboxEventType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TelemapOutboxService {

    private final OutboxEventRepository outboxEventRepository;

    public void enqueueSurgingQuestions(List<QuestionLog> questionLogs) {
        enqueueSurgingQuestions(questionLogs, Map.of());
    }

    public void enqueueSurgingQuestions(
            List<QuestionLog> questionLogs,
            Map<Integer, SurgingQuestionEmbedding> questionEmbeddings
    ) {
        for (int index = 0; index < questionLogs.size(); index++) {
            QuestionLog questionLog = questionLogs.get(index);
            SurgingQuestionEmbedding embedding = questionEmbeddings.get(index);
            if (embedding == null) continue;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("questionLogId", questionLog.getId());
            payload.put("embeddingProfileId", embedding.profileId());
            payload.put("embedding", embedding.vector());
            outboxEventRepository.enqueue(
                    OutboxEventType.SURGING_QUESTION,
                    String.valueOf(questionLog.getId()),
                    payload
            );
        }
    }
}

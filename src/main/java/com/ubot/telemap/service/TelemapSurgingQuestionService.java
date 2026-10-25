package com.ubot.telemap.service;

import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.exception.TelemapErrorCode;
import com.ubot.telemap.exception.TelemapException;
import com.ubot.telemap.repository.SurgingQuestionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelemapSurgingQuestionService {

    private final QuestionLogRepository questionLogRepository;
    private final EmbeddingService embeddingService;
    private final EmbeddingProfileService embeddingProfileService;
    private final SurgingQuestionRepository surgingQuestionRepository;
    private final TelemapSurgingQuestionPersistenceService persistenceService;

    public void registerQuestion(long questionLogId) {
        if (surgingQuestionRepository.existsByQuestionLogId(questionLogId)) return;

        QuestionLog questionLog = questionLogRepository.findById(questionLogId)
                .orElseThrow(() -> new TelemapException(TelemapErrorCode.QUESTION_LOG_NOT_FOUND));
        RegionSido region = questionLog.getRegion();
        if (region == null) {
            log.debug("지역 정보가 없는 질문 로그는 급증 감지에서 제외합니다: questionLogId={}", questionLogId);
            return;
        }

        Long userId = questionLog.getUserId();
        if (userId == null) return;

        String question = questionText(questionLog);
        long profileId = embeddingProfileService.getCurrentProfileId();
        PGvector vector = embeddingService.embedText(question);

        persistenceService.registerQuestion(questionLogId, profileId, vector);
    }

    private String questionText(QuestionLog questionLog) {
        String subQuestion = questionLog.getSubQuestion();
        if (subQuestion != null && !subQuestion.isBlank()) return subQuestion;
        return questionLog.getUserQuestion();
    }
}

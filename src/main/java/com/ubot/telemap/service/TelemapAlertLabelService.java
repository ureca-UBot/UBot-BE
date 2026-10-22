package com.ubot.telemap.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.service.LlmService;
import com.ubot.notification.service.NotificationService;
import com.ubot.telemap.config.TelemapProperties;
import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.exception.TelemapErrorCode;
import com.ubot.telemap.exception.TelemapException;
import com.ubot.telemap.repository.SurgingAlertRepository;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Slf4j
public class TelemapAlertLabelService {

    private static final int SUBJECT_MAX_LENGTH = 80;
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final Map<String, Object> RESPONSE_SCHEMA = createResponseSchema();

    private final SurgingAlertRepository surgingAlertRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final LlmService llmService;
    private final TelemapProperties telemapProperties;
    private final Resource systemPromptResource;

    public TelemapAlertLabelService(
            SurgingAlertRepository surgingAlertRepository,
            UserRepository userRepository,
            NotificationService notificationService,
            LlmService llmService,
            TelemapProperties telemapProperties,
            @Value("${prompt.telemap-alert-subject.system-location:classpath:prompts/telemap-alert-subject-system.txt}")
            Resource systemPromptResource
    ) {
        this.surgingAlertRepository = surgingAlertRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.llmService = llmService;
        this.telemapProperties = telemapProperties;
        this.systemPromptResource = systemPromptResource;
    }

    public void labelAndNotify(long alertId) {
        SurgingAlert alert = surgingAlertRepository.findById(alertId)
                .orElseThrow(() -> new TelemapException(TelemapErrorCode.SURGING_ALERT_NOT_FOUND));
        if (!StringUtils.hasText(alert.getSubject())) {
            alert.updateSubject(generateSubjectOrFallback(alert));
        }
        notificationService.createSurgingAlertNotifications(
                alert,
                userRepository.findAllByRoleAndDeletedAtIsNull(UserRole.ADMIN).stream()
                        .map(user -> user.getId())
                        .toList()
        );
    }

    private String generateSubjectOrFallback(SurgingAlert alert) {
        try {
            String subject = parseSubject(callLlm(alert));
            if (subject != null) return subject;
            log.warn("급증 알림 제목 LLM 응답이 유효하지 않아 기본 제목을 사용합니다: alertId={}", alert.getId());
        } catch (RuntimeException exception) {
            log.warn("급증 알림 제목 생성에 실패해 기본 제목을 사용합니다: alertId={}", alert.getId(), exception);
        }
        return alert.getRegion().name() + " 지역 유사 문의 급증";
    }

    private String callLlm(SurgingAlert alert) {
        List<SurgingQuestionSnapshot> snapshots = alert.getQuestionSnapshots().stream()
                .limit(telemapProperties.getMaxQuestionsForSubject())
                .toList();
        StringBuilder user = new StringBuilder()
                .append("지역: ").append(alert.getRegion().name())
                .append("\n집계 시간: 최근 ").append(alert.getWindowMinutes()).append("분")
                .append("\n집계 건수: ").append(alert.getQuestionCount())
                .append("\n질문 목록:");
        snapshots.forEach(snapshot -> user.append("\n- ").append(snapshot.question()));
        LlmRequestDto request = new LlmRequestDto(List.of(
                new LlmMessageRequestDto(LlmMessageRole.SYSTEM, readSystemPrompt()),
                new LlmMessageRequestDto(LlmMessageRole.USER, user.toString())
        )).withResponseSchema(RESPONSE_SCHEMA);
        LlmResponseDto response = llmService.generateAnswer(request);
        return response.answer();
    }

    private String parseSubject(String output) {
        if (!StringUtils.hasText(output)) return null;
        try {
            JsonNode root = JSON_MAPPER.readTree(output.strip());
            JsonNode subjectNode = root.get("subject");
            if (subjectNode == null || !subjectNode.isString()) return null;
            String subject = subjectNode.asString().strip();
            return StringUtils.hasText(subject) && subject.length() <= SUBJECT_MAX_LENGTH ? subject : null;
        } catch (JacksonException exception) {
            return null;
        }
    }

    private String readSystemPrompt() {
        try (InputStream input = systemPromptResource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new TelemapException(TelemapErrorCode.SUBJECT_GENERATION_FAILED);
        }
    }

    private static Map<String, Object> createResponseSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("subject", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(properties));
        schema.put("required", List.of("subject"));
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }
}

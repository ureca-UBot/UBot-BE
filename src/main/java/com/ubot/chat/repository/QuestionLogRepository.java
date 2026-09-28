package com.ubot.chat.repository;

import com.ubot.chat.entity.QuestionLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionLogRepository extends JpaRepository<QuestionLog, Long> {
}

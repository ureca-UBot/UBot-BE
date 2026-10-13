package com.ubot.faq.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FAQ 검색 설정")
class FaqRetrievalPropertiesTest {

    @Test
    @DisplayName("기본값은 Reranker 꺼짐, 후보 10개, 실패하면 벡터 결과로 답하기다")
    void defaults() {
        FaqRetrievalProperties properties = new FaqRetrievalProperties();
        properties.afterPropertiesSet();

        assertThat(properties.getRerank().isEnabled()).isFalse();
        assertThat(properties.getRerank().getCandidateK()).isEqualTo(10);
        assertThat(properties.getRerank().isFallbackEnabled()).isTrue();
        assertThat(properties.getRerank().getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.getRerank().getReadTimeout()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    @DisplayName("후보 수가 1보다 작거나 제한 시간이 0 이하면 시작할 때 막는다")
    void rejectsInvalidNumbers() {
        FaqRetrievalProperties candidateK = new FaqRetrievalProperties();
        candidateK.getRerank().setCandidateK(0);
        assertThatThrownBy(candidateK::afterPropertiesSet).isInstanceOf(IllegalStateException.class);

        FaqRetrievalProperties readTimeout = new FaqRetrievalProperties();
        readTimeout.getRerank().setReadTimeout(Duration.ZERO);
        assertThatThrownBy(readTimeout::afterPropertiesSet).isInstanceOf(IllegalStateException.class);

        FaqRetrievalProperties connectTimeout = new FaqRetrievalProperties();
        connectTimeout.getRerank().setConnectTimeout(Duration.ofMillis(-1));
        assertThatThrownBy(connectTimeout::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("켠 상태에서 서버 주소나 모델 이름이 비어 있으면 시작할 때 막고, 꺼 둔 상태에서는 막지 않는다")
    void requiresUrlAndModelOnlyWhenEnabled() {
        FaqRetrievalProperties disabled = new FaqRetrievalProperties();
        disabled.getRerank().setBaseUrl("");
        disabled.getRerank().setModel(" ");
        disabled.afterPropertiesSet();

        FaqRetrievalProperties noUrl = new FaqRetrievalProperties();
        noUrl.getRerank().setEnabled(true);
        noUrl.getRerank().setBaseUrl(" ");
        assertThatThrownBy(noUrl::afterPropertiesSet).isInstanceOf(IllegalStateException.class);

        FaqRetrievalProperties noModel = new FaqRetrievalProperties();
        noModel.getRerank().setEnabled(true);
        noModel.getRerank().setModel("");
        assertThatThrownBy(noModel::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }
}

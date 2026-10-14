package com.ubot.faq.retrieval;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.ubot.faq.retrieval.rerank.HttpRerankerClient;
import com.ubot.faq.retrieval.rerank.RerankerClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FaqRetrievalProperties.class)
public class FaqRetrievalConfig {

    /** Reranker를 꺼 두면 이 클라이언트는 호출되지 않습니다. 만들 때는 서버에 연결하지 않습니다. */
    @Bean
    public RerankerClient rerankerClient(
            FaqRetrievalProperties properties,
            RestClient.Builder restClientBuilder
    ) {
        FaqRetrievalProperties.Rerank rerank = properties.getRerank();

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(rerank.getConnectTimeout())
                .build();

        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        // 제한 시간을 넘기면 실패로 처리하고, 검색은 벡터 검색 결과로 이어갑니다.
        requestFactory.setReadTimeout(rerank.getReadTimeout());

        // 꺼 둔 상태에서는 주소를 비워 둘 수 있으므로 null을 넘기지 않습니다.
        String baseUrl = rerank.getBaseUrl() == null ? "" : rerank.getBaseUrl().replaceAll("/+$", "");

        RestClient restClient = restClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();

        return new HttpRerankerClient(restClient, rerank.getModel());
    }
}

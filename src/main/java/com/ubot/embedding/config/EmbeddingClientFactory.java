package com.ubot.embedding.config;

import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.embedding.client.OllamaEmbeddingClient;
import com.ubot.embedding.client.OpenAiCompatibleEmbeddingClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

public class EmbeddingClientFactory {

    public EmbeddingClient createOllama(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String modelName,
            Duration connectTimeout,
            Duration readTimeout) {

        validateTimeouts(connectTimeout, readTimeout);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();

        var requestFactory =
                new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(readTimeout);

        RestClient restClient = restClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();

        return new OllamaEmbeddingClient(
                restClient,
                modelName
        );
    }

    public EmbeddingClient createOpenAiCompatible(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String modelName,
            Duration connectTimeout,
            Duration readTimeout) {

        validateTimeouts(connectTimeout, readTimeout);

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();

        var requestFactory =
                new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(readTimeout);

        RestClient restClient = restClientBuilder
                .clone()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(requestFactory)
                .build();

        return new OpenAiCompatibleEmbeddingClient(
                restClient,
                modelName
        );
    }

    private void validateTimeouts(
            Duration connectTimeout,
            Duration readTimeout) {

        Assert.isTrue(
                connectTimeout.isPositive(),
                "임베딩 연결 제한 시간은 0보다 커야 합니다."
        );

        Assert.isTrue(
                readTimeout.isPositive(),
                "임베딩 응답 제한 시간은 0보다 커야 합니다."
        );
    }
}
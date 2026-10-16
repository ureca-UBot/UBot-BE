package com.ubot.faq.retrieval;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** QUESTION 검색 후보에서 질문과 유사한 답변 문장만 고릅니다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaqSentenceFilterService {
    private final EmbeddingService embeddingService;

    @Value("${faq.retrieval.sentence-similarity-threshold}")
    private double threshold;

    public List<MatchedFaq> filter(String question, List<FaqSearchResponseDto> faqs) {
        List<Sentence> sentences = new ArrayList<>();
        for (int index = 0; index < faqs.size(); index++) {
            String answer = faqs.get(index).answer();
            if (answer == null) {
                continue;
            }
            String normalized = answer.replaceAll("\\s+", " ").trim();
            BreakIterator boundaries = BreakIterator.getSentenceInstance(Locale.KOREAN);
            boundaries.setText(normalized);
            for (int start = boundaries.first(), end = boundaries.next(); end != BreakIterator.DONE;
                    start = end, end = boundaries.next()) {
                String text = normalized.substring(start, end).trim();
                if (!text.isEmpty()) {
                    sentences.add(new Sentence(index, text));
                }
            }
        }
        if (sentences.isEmpty()) {
            log.info("FAQ 답변에서 비교할 문장을 찾지 못했습니다: 후보수={}", faqs.size());
            return List.of();
        }

        List<String> texts = new ArrayList<>(sentences.size() + 1);
        texts.add(question);
        sentences.stream().map(Sentence::text).forEach(texts::add);
        List<PGvector> vectors = embeddingService.embedTexts(texts);
        List<List<String>> passed = new ArrayList<>(faqs.size());
        for (int index = 0; index < faqs.size(); index++) {
            passed.add(new ArrayList<>());
        }
        for (int index = 0; index < sentences.size(); index++) {
            Sentence sentence = sentences.get(index);
            double similarity = cosine(vectors.getFirst(), vectors.get(index + 1));
            boolean accepted = similarity >= threshold;
            log.info("FAQ 답변 문장 유사도: FAQ ID={}, 유사도={}, 기준값={}, 결과={}, 문장={}",
                    faqs.get(sentence.faqIndex()).faqId(), similarity, threshold,
                    accepted ? "통과" : "제외", sentence.text());
            if (accepted) {
                passed.get(sentence.faqIndex()).add(sentence.text());
            }
        }

        List<MatchedFaq> result = new ArrayList<>();
        for (int index = 0; index < faqs.size(); index++) {
            if (!passed.get(index).isEmpty()) {
                result.add(new MatchedFaq(faqs.get(index), String.join("\n", passed.get(index))));
            }
        }
        return result;
    }

    private double cosine(PGvector left, PGvector right) {
        float[] a = left.toArray();
        float[] b = right.toArray();
        if (a.length != b.length) {
            return Double.NaN;
        }
        double dot = 0, aNorm = 0, bNorm = 0;
        for (int index = 0; index < a.length; index++) {
            dot += (double) a[index] * b[index];
            aNorm += (double) a[index] * a[index];
            bNorm += (double) b[index] * b[index];
        }
        return aNorm == 0 || bNorm == 0 ? Double.NaN : dot / Math.sqrt(aNorm * bNorm);
    }

    private record Sentence(int faqIndex, String text) {
    }

    public record MatchedFaq(FaqSearchResponseDto faq, String answerSentences) {
    }
}

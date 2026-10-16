package com.ubot.faq.retrieval;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;

import lombok.extern.slf4j.Slf4j;

/** QUESTION 검색 후보에서 질문과 유사한 답변 문장만 고릅니다. */
@Service
@Slf4j
public class FaqSentenceFilterService {
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*((?:[-*•]|\\d+[.)]))(?:\\s+(.*))?$");

    private final EmbeddingService embeddingService;
    private final double threshold;

    public FaqSentenceFilterService(
            EmbeddingService embeddingService,
            @Value("${FAQ_SENTENCE_SIMILARITY_THRESHOLD:0.7}") double threshold
    ) {
        this.embeddingService = embeddingService;
        this.threshold = threshold;
    }

    public List<MatchedFaq> filter(PGvector queryVector, List<FaqSearchResponseDto> faqs) {
        List<Segment> segments = new ArrayList<>();
        for (int index = 0; index < faqs.size(); index++) {
            addSegments(segments, index, faqs.get(index).answer());
        }

        List<Sentence> sentences = new ArrayList<>();
        for (int index = 0; index < segments.size(); index++) {
            Segment segment = segments.get(index);
            if (segment.list()) {
                sentences.add(new Sentence(index, segment.text()));
                continue;
            }
            BreakIterator boundaries = BreakIterator.getSentenceInstance(Locale.KOREAN);
            boundaries.setText(segment.text());
            for (int start = boundaries.first(), end = boundaries.next(); end != BreakIterator.DONE;
                    start = end, end = boundaries.next()) {
                String text = segment.text().substring(start, end).trim();
                if (!text.isEmpty()) {
                    sentences.add(new Sentence(index, text));
                }
            }
        }
        if (sentences.isEmpty()) {
            log.info("FAQ 답변에서 비교할 문장을 찾지 못했습니다: 후보수={}", faqs.size());
            return List.of();
        }

        List<String> texts = new ArrayList<>(sentences.size());
        sentences.stream().map(Sentence::text).forEach(texts::add);
        List<PGvector> vectors = embeddingService.embedTexts(texts);
        List<List<String>> passed = new ArrayList<>(segments.size());
        for (int index = 0; index < segments.size(); index++) {
            passed.add(new ArrayList<>());
        }
        for (int index = 0; index < sentences.size(); index++) {
            Sentence sentence = sentences.get(index);
            Segment segment = segments.get(sentence.segmentIndex());
            double similarity = cosine(queryVector, vectors.get(index));
            boolean accepted = similarity >= threshold;
            log.debug("FAQ 답변 문장 유사도: FAQ ID={}, 유사도={}, 기준값={}, 결과={}, 문장={}",
                    faqs.get(segment.faqIndex()).faqId(), similarity, threshold,
                    accepted ? "통과" : "제외", sentence.text());
            if (accepted) {
                passed.get(sentence.segmentIndex()).add(sentence.text());
            }
        }

        List<List<String>> selectedByFaq = new ArrayList<>(faqs.size());
        for (int index = 0; index < faqs.size(); index++) {
            selectedByFaq.add(new ArrayList<>());
        }
        for (int index = 0; index < segments.size(); index++) {
            Segment segment = segments.get(index);
            List<String> selected = passed.get(index);
            if (selected.isEmpty()) {
                continue;
            }
            if (!segment.list()) {
                selectedByFaq.get(segment.faqIndex()).addAll(selected);
            } else {
                selectedByFaq.get(segment.faqIndex()).add(segment.displayText());
            }
        }

        List<MatchedFaq> result = new ArrayList<>();
        for (int index = 0; index < faqs.size(); index++) {
            if (!selectedByFaq.get(index).isEmpty()) {
                result.add(new MatchedFaq(faqs.get(index), String.join("\n", selectedByFaq.get(index))));
            }
        }
        return result;
    }

    private void addSegments(List<Segment> segments, int faqIndex, String answer) {
        if (answer == null) {
            return;
        }
        StringBuilder content = new StringBuilder();
        StringBuilder display = new StringBuilder();
        boolean list = false;
        String[] lines = answer.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (nextLineStartsList(lines, index + 1)) {
                    if (!display.isEmpty()) {
                        display.append('\n');
                    }
                    continue;
                }
                addSegment(segments, faqIndex, list, content, display);
                list = false;
                content.setLength(0);
                display.setLength(0);
                continue;
            }
            Matcher listItem = LIST_ITEM.matcher(line);
            if (listItem.matches()) {
                list = true;
                appendLine(content, listItem.group(2));
            } else {
                // 들여 쓴 줄은 마지막 항목의 연속 내용으로, 들여 쓰지 않은 줄은 목록 뒤의 일반 문장으로 봅니다.
                if (list && !Character.isWhitespace(line.charAt(0))) {
                    addSegment(segments, faqIndex, true, content, display);
                    list = false;
                    content.setLength(0);
                    display.setLength(0);
                }
                appendLine(content, trimmed);
            }
            if (!display.isEmpty()) {
                display.append('\n');
            }
            display.append(trimmed);
        }
        addSegment(segments, faqIndex, list, content, display);
    }

    private boolean nextLineStartsList(String[] lines, int start) {
        for (int index = start; index < lines.length; index++) {
            if (!lines[index].isBlank()) {
                return LIST_ITEM.matcher(lines[index]).matches();
            }
        }
        return false;
    }

    private void addSegment(List<Segment> segments, int faqIndex, boolean list,
            StringBuilder content, StringBuilder display) {
        String text = content.toString().replaceAll("\\s+", " ").trim();
        if (!text.isEmpty()) {
            segments.add(new Segment(faqIndex, list, text, display.toString()));
        }
    }

    private void appendLine(StringBuilder content, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (!content.isEmpty()) {
            content.append(' ');
        }
        content.append(line.trim());
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

    private record Segment(int faqIndex, boolean list, String text, String displayText) {
    }

    private record Sentence(int segmentIndex, String text) {
    }

    public record MatchedFaq(FaqSearchResponseDto faq, String answerSentences) {
    }
}

package com.ubot.faq.policy;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/** Reviewed scope constraints supplement embeddings; unknown or extra requests fall back. */
public final class ReviewedFaqPolicy {
    public record Offer(long id, String question, String answer, double score,
                        boolean registered, boolean approvedGeneral) {}
    public record Decision(Long faqId, String reason, double score, double gap) {}
    private record Rule(long id, String question, String answer, double threshold, double margin,
                        List<Pattern> required, Pattern excluded, List<String> vocabulary) {
        boolean matches(String input) {
            return required.stream().allMatch(p -> p.matcher(input).find())
                    && !excluded.matcher(input).find()
                    && Arrays.stream(input.split("\\s+")).allMatch(token -> covered(token, vocabulary));
        }
        boolean pins(Offer offer) {
            return id == offer.id() && question.equals(offer.question()) && answer.equals(offer.answer());
        }
    }

    private final List<Rule> rules;
    private final Pattern excluded;

    public ReviewedFaqPolicy() {
        Properties values = new Properties();
        try (var stream = ReviewedFaqPolicy.class.getResourceAsStream("/faq-semantic-policies.properties")) {
            if (stream == null) throw new IllegalStateException("Missing reviewed semantic cache policy");
            values.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load reviewed semantic cache policy", exception);
        }
        excluded = pattern(required(values, "excluded"));
        List<Rule> loaded = new ArrayList<>();
        for (String id : required(values, "ids").split(",")) {
            String prefix = "faq." + id.trim() + ".";
            double threshold = Double.parseDouble(required(values, prefix + "threshold"));
            double margin = Double.parseDouble(required(values, prefix + "margin"));
            if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1
                    || !Double.isFinite(margin) || margin <= 0 || margin > 2) {
                throw new IllegalStateException("Invalid reviewed semantic cache thresholds");
            }
            loaded.add(new Rule(Long.parseLong(id.trim()), required(values, prefix + "question"),
                    required(values, prefix + "answer"), threshold, margin,
                    List.of(pattern(required(values, prefix + "subject")),
                            pattern(required(values, prefix + "condition")),
                            pattern(required(values, prefix + "request"))),
                    pattern(required(values, prefix + "excluded")),
                    List.of(required(values, "vocabulary").split(","))));
        }
        if (loaded.stream().map(Rule::id).distinct().count() != loaded.size()) {
            throw new IllegalStateException("Duplicate reviewed FAQ policy");
        }
        rules = List.copyOf(loaded);
    }

    public boolean supports(String question) { return scope(question).isPresent(); }

    private Optional<Rule> scope(String question) {
        if (question == null || question.isBlank() || question.length() > 160
                || question.chars().filter(c -> c == '?' || c == '？').count() > 1) return Optional.empty();
        String input = Normalizer.normalize(question, Normalizer.Form.NFC);
        // Ignore a short greeting only. A second sentence/request is not covered by one answer.
        input = input.replaceFirst("^(?:안녕하세요|안녕하십니까|안녕)[\\s,.!！?？]*", "")
                .replaceFirst("[\\s.!?。！？]+$", "");
        if (Pattern.compile("[,.!?。！？]").matcher(input).find()) return Optional.empty();
        if (excluded.matcher(input).find()) return Optional.empty();
        final String scopedInput = input;
        List<Rule> matched = rules.stream().filter(r -> r.matches(scopedInput)).toList();
        return matched.size() == 1 ? Optional.of(matched.getFirst()) : Optional.empty();
    }

    public Decision decide(String question, List<Offer> offers) {
        Optional<Rule> scope = scope(question);
        if (scope.isEmpty()) return rejected("outside_reviewed_scope");
        if (offers == null || offers.isEmpty()) return rejected("no_candidates");
        if (offers.stream().anyMatch(o -> !Double.isFinite(o.score()))) return rejected("invalid_scores");
        Rule target = scope.get();
        // Only alternatives with unchanged reviewed Q/A can be ruled out by an explicit condition.
        // All other FAQ competitors, including unapproved ones, remain in the margin calculation.
        List<Offer> retained = offers.stream().filter(o -> rules.stream()
                .noneMatch(r -> r.id() != target.id() && r.pins(o)))
                .sorted(Comparator.comparingDouble(Offer::score).reversed().thenComparingLong(Offer::id))
                .toList();
        if (retained.isEmpty()) return rejected("no_candidates");
        Offer first = retained.getFirst();
        double gap = retained.size() > 1 ? first.score() - retained.get(1).score() : Double.NaN;
        String reason;
        if (!target.pins(first)) reason = "different_or_changed_faq";
        else if (!first.registered() || !first.approvedGeneral()) reason = "not_approved_or_registered";
        else if (first.score() < target.threshold()) reason = "below_scope_threshold";
        else if (!Double.isFinite(gap) || gap < target.margin()) reason = "ambiguous_scope";
        else reason = "accepted_reviewed_scope";
        return new Decision(reason.equals("accepted_reviewed_scope") ? first.id() : null,
                reason, first.score(), gap);
    }

    private static Decision rejected(String reason) {
        return new Decision(null, reason, Double.NaN, Double.NaN);
    }
    private static Pattern pattern(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
    /** Bounded word segmentation. Unknown content words cannot silently add another request. */
    private static boolean covered(String token, List<String> vocabulary) {
        boolean[] reachable = new boolean[token.length() + 1];
        reachable[0] = true;
        for (int offset = 0; offset < token.length(); offset++) {
            if (!reachable[offset]) continue;
            for (String term : vocabulary) {
                int end = offset + term.length();
                if (end <= token.length() && token.regionMatches(true, offset, term, 0, term.length())) {
                    reachable[end] = true;
                }
            }
        }
        return reachable[token.length()];
    }
    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing policy field: " + key);
        return value;
    }
}

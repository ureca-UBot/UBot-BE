package com.ubot.faq.publicapi;
import java.util.List;
public final class PublicFaqResponse {
    private PublicFaqResponse() {}
    public record Category(String name, long count) {}
    public record Summary(Long id, String category, String question) {}
    public record Detail(Long id, String category, String question, String answer) {}
}

package com.stockmind.application.news;

import java.time.LocalDateTime;

/** A normalized news item from an external news provider. */
public record NewsArticle(
        String id,
        String title,
        String summary,
        String source,
        String url,
        LocalDateTime publishedAt) {
}

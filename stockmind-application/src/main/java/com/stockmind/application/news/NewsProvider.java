package com.stockmind.application.news;

import java.time.LocalDate;
import java.util.List;

public interface NewsProvider {
    List<NewsArticle> search(String keyword, LocalDate startDate, LocalDate endDate, int limit);
}

package com.stockmind.application.announcement;

import java.time.LocalDate;

/** A normalized listed-company announcement. */
public record CompanyAnnouncement(
        String id,
        String title,
        String type,
        LocalDate publishedDate,
        String detailUrl,
        String pdfUrl) {
}

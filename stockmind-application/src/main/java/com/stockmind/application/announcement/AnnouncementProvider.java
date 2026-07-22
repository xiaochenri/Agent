package com.stockmind.application.announcement;

import java.time.LocalDate;
import java.util.List;

public interface AnnouncementProvider {
    List<CompanyAnnouncement> search(String symbol, LocalDate startDate, LocalDate endDate, int limit);
}

package com.stockmind.application.market;

import java.time.LocalDate;

/** Regression checks for explicit historical evidence windows. */
public final class StockTimeWindowResolverAcceptanceTest {
    public static void main(String[] args) {
        var explicit = StockTimeWindowResolver.resolveNews(
                "2025-01-01", "2025-12-31", "");
        require(explicit.startDate().equals(LocalDate.of(2025, 1, 1))
                        && explicit.endDate().equals(LocalDate.of(2025, 12, 31)),
                "显式历史窗口不得被替换为最近30天");

        var textual = StockTimeWindowResolver.resolveNews(
                "", "", "2024-01-01至2024-03-31");
        require(textual.startDate().equals(LocalDate.of(2024, 1, 1))
                        && textual.endDate().equals(LocalDate.of(2024, 3, 31)),
                "time_window中的显式历史范围不得被改写");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

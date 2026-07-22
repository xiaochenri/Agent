package com.stockmind.application.financial;

public enum StatementScope {
    SINGLE_QUARTER,
    CUMULATIVE_QUARTER,
    HALF_YEAR,
    ANNUAL,
    UNKNOWN;

    /** Infers cumulative statement scope from the report period's month and day. */
    public static StatementScope inferFromReportPeriod(java.time.LocalDate reportPeriod) {
        if (reportPeriod == null) return UNKNOWN;
        return switch (reportPeriod.getMonthValue()) {
            case 3, 9 -> CUMULATIVE_QUARTER;
            case 6 -> HALF_YEAR;
            case 12 -> ANNUAL;
            default -> UNKNOWN;
        };
    }
}

package com.stockmind.application.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 财务趋势工具交给业务分析层的标准化报告期数据。 */
public record FinancialTrendPoint(
        LocalDate reportPeriod,
        BigDecimal revenueYoyPct,
        BigDecimal netProfitYoyPct,
        BigDecimal cashToNetProfitRatio) { }

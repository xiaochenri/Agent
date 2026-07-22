package com.stockmind.bootstrap.business.tool;

import com.stockmind.domain.analysis.AnalysisEvidence;
import com.stockmind.domain.analysis.BusinessSignal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将业务信号转换为工具输出协议，供不同股票工具复用。 */
final class StockBusinessSignalMapper {
    private StockBusinessSignalMapper() { }

    static List<Map<String, Object>> maps(List<BusinessSignal> signals) {
        return signals.stream().map(StockBusinessSignalMapper::map).toList();
    }

    static Map<String, Object> map(BusinessSignal signal) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("signal_id", signal.id());
        value.put("thesis", signal.thesis().name());
        value.put("direction", signal.direction().name());
        value.put("strength", signal.strength().name());
        value.put("summary", signal.summary());
        value.put("rationale", signal.rationale());
        value.put("boundary", signal.boundary());
        value.put("evidence", signal.evidence().stream().map(StockBusinessSignalMapper::evidenceMap).toList());
        return value;
    }

    private static Map<String, Object> evidenceMap(AnalysisEvidence evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("fact", evidence.fact());
        value.put("source_type", evidence.sourceType());
        value.put("as_of", evidence.asOf() == null ? null : evidence.asOf().toString());
        value.put("report_period", evidence.reportPeriod() == null ? null : evidence.reportPeriod().toString());
        value.put("basis", evidence.basis());
        value.put("sources", evidence.sources());
        return value;
    }
}

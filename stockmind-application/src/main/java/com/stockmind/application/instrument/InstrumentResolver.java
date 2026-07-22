package com.stockmind.application.instrument;

import com.stockmind.domain.instrument.Exchange;
import com.stockmind.domain.instrument.Instrument;
import com.stockmind.domain.instrument.InstrumentType;
import java.util.Map;

/** Resolves user-facing symbols before any provider request is made. */
public final class InstrumentResolver {
    private static final Map<String, String> INDEX_NAMES = Map.ofEntries(
            Map.entry("SH000001", "上证指数"),
            Map.entry("SH000016", "上证50"),
            Map.entry("SH000300", "沪深300"),
            Map.entry("SH000905", "中证500"),
            Map.entry("SH000852", "中证1000"),
            Map.entry("SZ399001", "深证成指"),
            Map.entry("SZ399005", "中小100"),
            Map.entry("SZ399006", "创业板指"));

    /** Resolves a symbol without constraining the instrument type. */
    public Instrument resolve(String input) {
        return resolve(input, null);
    }

    /** Normalizes a symbol and rejects instruments that do not match the required type. */
    public Instrument resolve(String input, InstrumentType expectedType) {
        String value = input == null ? "" : input.trim().toUpperCase();
        if (value.matches("\\d{6}")) {
            if (expectedType == null) {
                throw new AmbiguousInstrumentException(
                        "裸6位代码缺少资产类型和交易所，无法确定性解析: " + value);
            }
            if (expectedType != InstrumentType.STOCK) {
                throw new AmbiguousInstrumentException("指数或ETF必须显式提供交易所前缀: " + value);
            }
            Exchange exchange = inferStockExchange(value);
            if (!isStockCode(exchange, value)) {
                throw new IllegalArgumentException(value + " 不符合 " + exchange + " 股票代码规则");
            }
            return instrument(exchange, value, expectedType);
        }

        String compact = value.matches("\\d{6}\\.(SH|SZ|BJ)")
                ? value.substring(7) + value.substring(0, 6)
                : value;
        if (!compact.matches("(SH|SZ|BJ)\\d{6}")) {
            throw new IllegalArgumentException("证券代码必须采用SH/SZ/BJ加6位代码的格式");
        }
        Exchange exchange = Exchange.valueOf(compact.substring(0, 2));
        String code = compact.substring(2);
        InstrumentType inferred = INDEX_NAMES.containsKey(compact)
                ? InstrumentType.INDEX
                : expectedType == null ? inferExplicitType(exchange, code) : expectedType;
        if (inferred == null) {
            throw new AmbiguousInstrumentException("代码已包含交易所，但仍需明确资产类型: " + compact);
        }
        if (expectedType != null && inferred != expectedType) {
            throw new IllegalArgumentException(compact + " 实际为 " + inferred + "，与期望的 " + expectedType + " 不一致");
        }
        if (inferred == InstrumentType.STOCK && !isStockCode(exchange, code)) {
            throw new IllegalArgumentException(compact + " 不符合股票代码规则");
        }
        return instrument(exchange, code, inferred);
    }

    private Instrument instrument(Exchange exchange, String code, InstrumentType type) {
        String normalized = exchange.name() + code;
        return new Instrument(normalized, code, exchange, type, INDEX_NAMES.getOrDefault(normalized, ""));
    }

    private Exchange inferStockExchange(String code) {
        if (code.startsWith("6") || code.startsWith("9")) return Exchange.SH;
        if (code.startsWith("4") || code.startsWith("8")) return Exchange.BJ;
        return Exchange.SZ;
    }

    private InstrumentType inferExplicitType(Exchange exchange, String code) {
        if (isStockCode(exchange, code)) return InstrumentType.STOCK;
        if ((exchange == Exchange.SH && code.startsWith("000"))
                || (exchange == Exchange.SZ && code.startsWith("399"))) return InstrumentType.INDEX;
        if ((exchange == Exchange.SH && code.startsWith("5"))
                || (exchange == Exchange.SZ && (code.startsWith("15") || code.startsWith("16")))) {
            return InstrumentType.ETF;
        }
        return null;
    }

    private boolean isStockCode(Exchange exchange, String code) {
        return switch (exchange) {
            case SH -> code.matches("(600|601|603|605|688|689|900)\\d{3}");
            case SZ -> code.matches("(000|001|002|003|200|300|301)\\d{3}");
            case BJ -> code.matches("[489]\\d{5}");
        };
    }
}

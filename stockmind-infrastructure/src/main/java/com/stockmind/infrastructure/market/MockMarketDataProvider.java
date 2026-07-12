package com.stockmind.infrastructure.market;

import com.stockmind.application.market.HistoricalBarsQuery;
import com.stockmind.application.market.MarketDataProvider;
import com.stockmind.domain.market.BarDataset;
import com.stockmind.domain.market.MarketBar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Component;

/** Deterministic OHLCV provider used by the stock analysis workflow. */
@Component
public class MockMarketDataProvider implements MarketDataProvider {

    private static final LocalDate SIMULATION_START_DATE = LocalDate.of(2000, 1, 3);

    @Override
    public BarDataset loadBars(HistoricalBarsQuery query) {
        long seed = query.instrumentId().hashCode() * 31L;
        Random random = new Random(seed);
        List<MarketBar> bars = new ArrayList<>();
        double price = 30 + Math.floorMod(query.instrumentId().hashCode(), 60000) / 100.0;
        LocalDate date = SIMULATION_START_DATE;
        int tradingIndex = 0;
        while (!date.isAfter(query.endDate())) {
            if (date.getDayOfWeek().getValue() <= 5) {
                double open = Math.max(0.01, price * (1 + random.nextGaussian() * 0.012));
                double change = random.nextGaussian() * 0.018 + Math.sin(tradingIndex / 14.0) * 0.004;
                double close = Math.max(0.01, open * (1 + change));
                double high = Math.max(open, close) * (1 + random.nextDouble() * 0.018);
                double low = Math.min(open, close) * (1 - random.nextDouble() * 0.018);
                long volume = 800_000L + Math.round(random.nextDouble() * 8_000_000L);
                Instant openTime = date.atTime(1, 30).toInstant(ZoneOffset.UTC);
                Instant closeTime = date.atTime(7, 0).toInstant(ZoneOffset.UTC);
                if (!date.isBefore(query.startDate())) {
                    bars.add(new MarketBar(query.instrumentId(), query.interval(), openTime, closeTime,
                            decimal(open), decimal(high), decimal(low), decimal(close), BigDecimal.valueOf(volume),
                            decimal(close * volume), query.adjustment()));
                }
                price = close;
                tradingIndex++;
            }
            date = date.plusDays(1);
        }
        String id = sha256(query.instrumentId() + "|" + query.interval() + "|" + query.startDate()
                + "|" + query.endDate() + "|" + query.adjustment());
        return new BarDataset(id, List.copyOf(bars), "stockmind_ohlcv", Instant.now(), List.of());
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}

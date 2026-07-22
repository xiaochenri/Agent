package com.stockmind.application.sector;

import java.util.List;

public interface SectorDataProvider {
    List<SectorSnapshot> loadIndustrySectors(String symbol);

    SectorConstituentSet loadTopIndustryConstituents(String symbol, int limit);
}

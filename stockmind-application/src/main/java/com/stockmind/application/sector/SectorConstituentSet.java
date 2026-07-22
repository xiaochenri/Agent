package com.stockmind.application.sector;

import java.util.List;

public record SectorConstituentSet(String sectorCode, String sectorName,
                                   List<SectorConstituent> constituents) {
}

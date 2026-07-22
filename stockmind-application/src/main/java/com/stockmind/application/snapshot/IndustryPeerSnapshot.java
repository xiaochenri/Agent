package com.stockmind.application.snapshot;

import com.stockmind.application.sector.SectorConstituentSet;
import java.time.LocalDate;
import java.util.List;

/** Industry peers loaded once for the request; current-only sources are explicit. */
public record IndustryPeerSnapshot(
        LocalDate requestedAsOf,
        boolean currentOnly,
        boolean usableForRequestedAsOf,
        SectorConstituentSet constituentSet,
        List<PeerMarketValue> marketValues,
        List<String> limitations) {

    public IndustryPeerSnapshot {
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        marketValues = marketValues == null ? List.of() : List.copyOf(marketValues);
    }

    public static IndustryPeerSnapshot unavailableForHistory(LocalDate asOf) {
        return new IndustryPeerSnapshot(asOf, true, false, null, List.of(),
                List.of("industry_peer_source_is_current_only"));
    }
}

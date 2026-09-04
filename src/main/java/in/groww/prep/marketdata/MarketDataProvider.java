package in.groww.prep.marketdata;

import java.util.List;
import java.util.Optional;

/**
 * Seam between "where prices come from" and everything else. The MVP
 * implementation (SimulatedMarketDataProvider) is a random-walk simulator —
 * deliberately, so the demo never depends on a third-party API's uptime or
 * rate limits. Swapping in a real feed (NSE/BSE data vendor, broker API)
 * later means writing one new class against this interface; nothing in
 * WatchlistService or the controller layer would need to change.
 */
public interface MarketDataProvider {

    Optional<Quote> getQuote(String symbol);

    List<Quote> getQuotes(List<String> symbols);

    /** Recent price samples, oldest first — the series behind the sparkline. */
    List<PricePoint> getHistory(String symbol);

    /** The fixed universe of symbols this provider can quote. */
    List<String> supportedSymbols();
}

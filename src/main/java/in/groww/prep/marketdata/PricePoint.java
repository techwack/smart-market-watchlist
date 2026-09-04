package in.groww.prep.marketdata;

import java.time.Instant;

/** One sample in a symbol's recent price history, for the delta sparkline. */
public record PricePoint(double price, Instant at) {
}

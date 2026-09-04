package in.groww.prep.marketdata;

/** Upstream feed is unreachable or erroring. Never allowed to reach the user as a 500. */
public class MarketDataUnavailableException extends RuntimeException {
    public MarketDataUnavailableException(String message) {
        super(message);
    }
}

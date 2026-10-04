package sdb.px.ratelimit;

public final class GlobalRateLimiter {
    private final double tokensPerSecond;
    private double tokens;
    private long lastRefill = System.nanoTime();

    public GlobalRateLimiter(double perTick, double perSecond, double perHour) {
        double tickLimitPerSecond = validLimit(perTick) * 20.0;
        double hourLimitPerSecond = validLimit(perHour) / 3_600.0;
        tokensPerSecond = Math.min(
                validLimit(perSecond),
                Math.min(tickLimitPerSecond, hourLimitPerSecond));
    }

    public boolean tryAcquire() {
        long now = System.nanoTime();
        double elapsedSeconds = Math.max(0L, now - lastRefill) / 1_000_000_000.0;
        tokens = Math.min(1.0, tokens + elapsedSeconds * tokensPerSecond);
        lastRefill = now;
        if (tokens < 1.0) {
            return false;
        }
        tokens -= 1.0;
        return true;
    }

    public boolean hasPositiveRate() {
        return tokensPerSecond > 0.0;
    }

    private static double validLimit(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("Rate limits must be finite and non-negative.");
        }
        return value;
    }
}

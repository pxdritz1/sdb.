package sdb.px.ratelimit;

public final class GlobalRateLimiter {
    private static final double TICKS_PER_SECOND = 20.0;
    private static final double TICKS_PER_HOUR = TICKS_PER_SECOND * 60.0 * 60.0;
    private static final double TOKEN_EPSILON = 1.0e-12;
    private final double effectiveTokensPerTick;
    private final double bucketCapacity;
    private double tokens;

    public GlobalRateLimiter(double perTick, double perSecond, double perHour) {
        effectiveTokensPerTick = Math.min(perTick, Math.min(perSecond / TICKS_PER_SECOND, perHour / TICKS_PER_HOUR));
        bucketCapacity = effectiveTokensPerTick > 0.0 ? 1.0 + effectiveTokensPerTick : 0.0;
        tokens = effectiveTokensPerTick > 0.0 ? 1.0 : 0.0;
    }

    public void tick() {
        tokens = Math.min(bucketCapacity, tokens + effectiveTokensPerTick);
    }

    public boolean tryAcquire() {
        if (tokens + TOKEN_EPSILON < 1.0) {
            return false;
        }
        tokens = Math.max(0.0, tokens - 1.0);
        return true;
    }
}

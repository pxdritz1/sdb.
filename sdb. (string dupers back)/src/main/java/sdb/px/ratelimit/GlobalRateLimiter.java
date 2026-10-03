package sdb.px.ratelimit;

import java.math.BigDecimal;
import java.math.MathContext;

public final class GlobalRateLimiter {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal TICKS_PER_SECOND = BigDecimal.valueOf(20L);
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(60L * 60L);
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final BigDecimal ONE_TOKEN = BigDecimal.ONE;
    private static final BigDecimal NANOS_PER_SECOND_DECIMAL = BigDecimal.valueOf(NANOS_PER_SECOND);
    private final BigDecimal maxTokensPerTick;
    private final BigDecimal maxTokensPerSecond;
    private final BigDecimal bucketCapacity;
    private BigDecimal tokens = BigDecimal.ZERO;
    private long lastRefillNanos;

    public GlobalRateLimiter(double perTick, double perSecond, double perHour) {
        maxTokensPerTick = BigDecimal.valueOf(perTick);
        BigDecimal tickRatePerSecond = maxTokensPerTick.multiply(TICKS_PER_SECOND);
        BigDecimal configuredPerSecond = BigDecimal.valueOf(perSecond);
        BigDecimal configuredPerHour = BigDecimal.valueOf(perHour).divide(SECONDS_PER_HOUR, PRECISION);
        maxTokensPerSecond = minimum(tickRatePerSecond, minimum(configuredPerSecond, configuredPerHour));
        bucketCapacity = ONE_TOKEN.add(maxTokensPerTick);
        lastRefillNanos = System.nanoTime();
    }

    public void tick(long nowNanos) {
        long elapsedNanos = nowNanos - lastRefillNanos;
        if (elapsedNanos <= 0L) {
            return;
        }
        lastRefillNanos = nowNanos;
        BigDecimal timeBasedTokens = maxTokensPerSecond
                .multiply(BigDecimal.valueOf(elapsedNanos), PRECISION)
                .divide(NANOS_PER_SECOND_DECIMAL, PRECISION);
        BigDecimal refill = minimum(maxTokensPerTick, timeBasedTokens);
        tokens = minimum(bucketCapacity, tokens.add(refill));
    }

    public boolean tryAcquire() {
        if (tokens.compareTo(ONE_TOKEN) < 0) {
            return false;
        }
        tokens = tokens.subtract(ONE_TOKEN);
        return true;
    }

    private static BigDecimal minimum(BigDecimal first, BigDecimal second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}

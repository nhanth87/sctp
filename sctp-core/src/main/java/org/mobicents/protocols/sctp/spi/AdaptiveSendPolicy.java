package org.mobicents.protocols.sctp.spi;

/**
 * AIMD send policy toward an STP. {@code maxTps <= 0} means unlimited until the
 * first congestion signal, then the controller uses the last offered rate (or
 * 1000) as the AIMD ceiling so a 1000 TPS blast backs off instead of ignoring SCON.
 */
public record AdaptiveSendPolicy(
        int maxTps,
        int minTps,
        long holdMs,
        long recoveryIntervalMs,
        int recoveryStepTps,
        boolean pauseUserDataAtSevere,
        double level1Fraction,
        double level2Fraction,
        double level3Fraction
) {
    public AdaptiveSendPolicy {
        if (minTps < 1) {
            throw new IllegalArgumentException("minTps must be >= 1");
        }
        if (holdMs < 0 || recoveryIntervalMs < 1) {
            throw new IllegalArgumentException("holdMs >= 0 and recoveryIntervalMs >= 1");
        }
        if (recoveryStepTps < 0) {
            throw new IllegalArgumentException("recoveryStepTps must be >= 0");
        }
        requireFraction("level1Fraction", level1Fraction);
        requireFraction("level2Fraction", level2Fraction);
        requireFraction("level3Fraction", level3Fraction);
        if (level1Fraction < level2Fraction || level2Fraction < level3Fraction) {
            throw new IllegalArgumentException("fractions must be level1 >= level2 >= level3");
        }
    }

    public static AdaptiveSendPolicy defaults() {
        return fromSystemProperties();
    }

    public static AdaptiveSendPolicy fromSystemProperties() {
        return new AdaptiveSendPolicy(
                intProp("sctp.cong.max-tps", 0),
                intProp("sctp.cong.min-tps", 10),
                longProp("sctp.cong.hold-ms", 2_000L),
                longProp("sctp.cong.recovery-ms", 1_000L),
                intProp("sctp.cong.recovery-step-tps", 0),
                boolProp("sctp.cong.pause-severe", true),
                doubleProp("sctp.cong.level1-fraction", 0.50),
                doubleProp("sctp.cong.level2-fraction", 0.25),
                doubleProp("sctp.cong.level3-fraction", 0.10)
        );
    }

    public double fractionFor(int level) {
        return switch (level) {
            case 1 -> level1Fraction;
            case 2 -> level2Fraction;
            case 3 -> level3Fraction;
            default -> 1.0;
        };
    }

    public int stepTps(int ceiling) {
        if (recoveryStepTps > 0) {
            return recoveryStepTps;
        }
        return Math.max(10, ceiling / 20);
    }

    private static void requireFraction(String name, double value) {
        if (value <= 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in (0, 1], got " + value);
        }
    }

    private static int intProp(String key, int fallback) {
        return Integer.getInteger(key, fallback);
    }

    private static long longProp(String key, long fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Long.parseLong(raw.trim());
    }

    private static boolean boolProp(String key, boolean fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static double doubleProp(String key, double fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Double.parseDouble(raw.trim());
    }
}

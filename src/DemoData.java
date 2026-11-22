import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Synthetic demo dataset so the app opens with something to explore before a
 * real FAA CSV is imported. The seasonal and time-of-day patterns are
 * modelled on what is publicly documented for these airports, but THE NUMBERS
 * ARE INVENTED and the UI labels it as demo data everywhere.
 */
final class DemoData {

    private static final class Species {
        final String name, size;
        final double weight;
        Species(String n, String s, double w) { name = n; size = s; weight = w; }
    }

    private static final Species[] KDEN = {
            new Species("Horned Lark", "Small", 3.0),
            new Species("Killdeer", "Small", 2.2),
            new Species("European Starling", "Small", 2.5),
            new Species("Barn Swallow", "Small", 1.8),
            new Species("Western Meadowlark", "Small", 1.5),
            new Species("Mourning Dove", "Medium", 1.6),
            new Species("Ring-billed Gull", "Medium", 1.4),
            new Species("Red-tailed Hawk", "Medium", 0.8),
            new Species("Canada Goose", "Large", 0.7),
            new Species("Great Blue Heron", "Large", 0.3)
    };

    private static final Species[] VIDP = {
            new Species("Black Kite", "Medium", 3.5),
            new Species("House Crow", "Medium", 2.8),
            new Species("Cattle Egret", "Medium", 1.8),
            new Species("Rock Pigeon", "Medium", 1.6),
            new Species("Alexandrine Parakeet", "Medium", 0.9),
            new Species("Black-winged Stilt", "Small", 0.8),
            new Species("Bank Myna", "Small", 1.2),
            new Species("Painted Stork", "Large", 0.6)
    };

    private static final Species[] WSSS = {
            new Species("Germain's Swiftlet", "Small", 3.0),
            new Species("Asian Palm Swift", "Small", 1.8),
            new Species("Javan Myna", "Small", 2.0),
            new Species("Spotted Dove", "Medium", 1.4),
            new Species("Black Kite", "Medium", 1.1),
            new Species("Little Egret", "Medium", 0.9),
            new Species("Purple Heron", "Large", 0.4)
    };

    record AirportDef(String id, String name, Species[] species,
                      double[] monthSeason, double[] hourSeason, double damageBase) {}

    private static final AirportDef[] AIRPORTS = {
            new AirportDef("KDEN", "Denver Intl (synthetic demo)", KDEN,
                    season(.5, .6, 1.0, 1.35, 1.9, 2.1, 1.9, 1.8, 1.5, 1.0, .65, .45),
                    hourCurve(true),
                    .06),
            new AirportDef("VIDP", "Delhi Indira Gandhi (synthetic demo)", VIDP,
                    season(2.3, 2.4, 1.2, .6, .4, .35, .3, .35, .7, 1.4, 2.1, 2.35),
                    hourCurve(false),
                    .09),
            new AirportDef("WSSS", "Singapore Changi (synthetic demo)", WSSS,
                    season(1.2, 1.3, 1.4, 1.1, .85, .7, .75, .85, 1.3, 1.55, 1.7, 1.4),
                    hourCurve(true),
                    .05)
    };

    private static double[] season(double... v) { return v; }

    /** true -> dawn/dusk peaks (US airfield), false -> night/dawn peaks (urban raptor city). */
    private static double[] hourCurve(boolean diurnal) {
        double[] h = new double[24];
        for (int i = 0; i < 24; i++) {
            double dawn = Math.exp(-Math.pow(i - 7, 2) / 6.0);
            double dusk = Math.exp(-Math.pow(i - 18.5, 2) / 5.0);
            double night = (i >= 20 || i <= 4) ? 0.55 : 0.0;
            double day = (i >= 9 && i <= 16) ? 0.7 : 0.0;
            h[i] = diurnal ? 0.35 + 1.6 * dawn + 1.3 * dusk + 0.8 * day
                           : 0.35 + 1.7 * dawn + 0.7 * dusk + 1.0 * night;
        }
        return h;
    }

    static StrikeData generate() {
        List<FaaCsvParser.StrikeRecord> out = new ArrayList<>();
        int seed = 20261031;
        for (AirportDef a : AIRPORTS) {
            for (int year = 2005; year <= 2024; year++) {
                for (int month = 1; month <= 12; month++) {
                    double seasonal = a.monthSeason()[month - 1];
                    int target = (int) Math.round((1.6 + 2.2 * seasonal)
                            * (0.7 + 0.6 * lcg(seed++)) * 30.44 / 6.0);
                    for (int k = 0; k < target; k++) {
                        // pick hour by weighted roulette on the hourly curve
                        double total = 0;
                        double[] w = new double[24];
                        for (int h = 0; h < 24; h++) {
                            w[h] = a.hourSeason()[h] * (0.75 + 0.5 * lcg(seed));
                            total += w[h];
                        }
                        double pick = lcg(seed++) * total;
                        int hour = 12;
                        for (int h = 0; h < 24; h++) { pick -= w[h]; if (pick <= 0) { hour = h; break; } }
                        int day = 1 + (int) (lcg(seed++) * LocalDate.of(year, month, 1)
                                .lengthOfMonth());
                        LocalDate date = LocalDate.of(year, month, day);

                        Species sp = roulette(a.species(), lcg(seed++));
                        int qty = pickQty(lcg(seed++));
                        boolean damaged = lcg(seed++) < a.damageBase
                                * (sp.size.equals("Large") ? 2.6 : sp.size.equals("Medium") ? 1.4 : 1.0);
                        out.add(new FaaCsvParser.StrikeRecord(
                                a.id(), date, hour, bucket(hour),
                                pickPhase(lcg(seed++)), sp.name, sp.size, qty, damaged,
                                pickSky(lcg(seed++)), pickPrecip(lcg(seed++)),
                                "Regional Air (demo)", "A320/737 (demo)"));
                    }
                }
            }
        }
        return new StrikeData(out, true);
    }

    private static Species roulette(Species[] pool, double r) {
        double total = 0;
        for (Species s : pool) total += s.weight;
        double p = r * total;
        for (Species s : pool) { p -= s.weight; if (p <= 0) return s; }
        return pool[0];
    }

    private static int pickQty(double r) {
        if (r < 0.72) return 1;
        if (r < 0.93) return 6;      // "2-10"
        return 55;                   // "11-100"
    }

    private static String pickPhase(double r) {
        if (r < 0.38) return "Approach";
        if (r < 0.62) return "Takeoff Run";
        if (r < 0.78) return "Climb";
        if (r < 0.88) return "Taxi";
        if (r < 0.95) return "Landing Roll";
        return "En Route";
    }

    private static String pickSky(double r) {
        if (r < 0.55) return "No Cloud";
        if (r < 0.8) return "Some Cloud";
        return "Overcast";
    }

    private static String pickPrecip(double r) {
        if (r < 0.85) return "None";
        if (r < 0.95) return "Rain";
        return "Snow";
    }

    private static String bucket(int hour) {
        if (hour >= 5 && hour <= 8) return "Dawn";
        if (hour >= 9 && hour <= 16) return "Day";
        if (hour >= 17 && hour <= 19) return "Dusk";
        return "Night";
    }

    /** Deterministic pseudo-random in [0,1) so the demo dataset is stable. */
    private static double lcg(int seed) {
        long x = (long) seed * 2654435761L;
        x ^= x >>> 13; x *= 0x2545F4914F6CDD1DL; x ^= x >>> 16;
        return (x & 0xFFFFFFL) / (double) 0x1000000L;
    }
}

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Explainable climatology risk model for one airport.
 *
 * Predicted strike intensity for (month, hour) = monthly base rate of
 * reported strikes per day  x  hour-of-day weighting. The score shown in the
 * UI is normalized against the airport's own peak, i.e. it is a RELATIVE
 * index, not an absolute probability. This mirrors how the underlying data
 * (reported strikes) must be used: the model forecasts when risk concentrates,
 * not how many birds are in the sky.
 */
public final class RiskModel {

    public static final String[] MONTHS = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };

    private final String airport;
    private final double[] monthBase = new double[12];   // reported strikes/day by month
    private final double[] hourWeight = new double[24];  // relative activity by hour
    private final double maxValue;
    private final int yearFrom, yearTo;

    public RiskModel(List<FaaCsvParser.StrikeRecord> records, String airport) {
        this.airport = airport;
        int from = Integer.MAX_VALUE, to = Integer.MIN_VALUE;

        // monthly base: strikes per calendar day, averaged over years of coverage
        Map<Integer, Integer> yearsCovered = new HashMap<>();
        double[] monthSum = new double[12];
        for (var r : records) {
            int y = r.date().getYear();
            monthSum[r.date().getMonthValue() - 1] += 1;
            yearsCovered.put(y, 1);
            from = Math.min(from, y);
            to = Math.max(to, y);
        }
        yearFrom = from == Integer.MAX_VALUE ? 0 : from;
        yearTo = to == Integer.MAX_VALUE ? 0 : to;
        int nYears = Math.max(1, yearsCovered.size());
        for (int m = 0; m < 12; m++) monthBase[m] = monthSum[m] / (nYears * 30.44);

        // hour weights: blend observed-hour distribution with time-of-day buckets
        double[] hourObs = new double[24];
        int obsCount = 0;
        double[] bucketCurve = new double[24];
        for (var r : records) {
            if (r.hour() >= 0) { hourObs[r.hour()] += 1; obsCount++; }
            addBucketCurve(bucketCurve, r.timeOfDay());
        }
        double[] hSource;
        if (obsCount >= 30) {
            double obsSum = 0, bSum = 0;
            for (int h = 0; h < 24; h++) { obsSum += hourObs[h]; bSum += bucketCurve[h]; }
            hSource = new double[24];
            for (int h = 0; h < 24; h++) {
                double a = obsSum > 0 ? hourObs[h] / obsSum : 0;
                double b = bSum > 0 ? bucketCurve[h] / bSum : 1.0 / 24;
                hSource[h] = 0.5 * a + 0.5 * b;
            }
        } else {
            hSource = bucketCurve;
        }
        double hSum = 0;
        for (double v : hSource) hSum += v;
        for (int h = 0; h < 24; h++) hourWeight[h] = hSum > 0 ? hSource[h] / hSum * 24 : 1;

        double max = 0;
        for (int m = 0; m < 12; m++)
            for (int h = 0; h < 24; h++)
                max = Math.max(max, monthBase[m] * hourWeight[h]);
        maxValue = max > 0 ? max : 1;
    }

    private static void addBucketCurve(double[] curve, String tod) {
        if (tod == null) return;
        switch (tod.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "DAWN" -> { curve[5] += 1; curve[6] += 2; curve[7] += 2; curve[8] += 1; }
            case "DAY" -> { for (int h = 9; h <= 16; h++) curve[h] += 0.7; }
            case "DUSK" -> { curve[17] += 2; curve[18] += 2; curve[19] += 1; }
            case "NIGHT" -> { for (int h = 20; h <= 23; h++) curve[h] += 0.5;
                              for (int h = 0; h <= 4; h++) curve[h] += 0.5; }
            default -> { }
        }
    }

    // ---------- predictions ----------

    /** Relative risk index 0-100 for a month (1-12) and hour of day (0-23). */
    public int riskIndex(int month, int hour) {
        double v = monthBase[month - 1] * hourWeight[hour];
        return (int) Math.round(100.0 * v / maxValue);
    }

    /** Average strikes/day predicted for a whole month. */
    public double monthRate(int month) { return monthBase[month - 1]; }

    /** Relative hour-of-day activity weight (busiest hour ≈ 24). */
    public double hourWeightOf(int hour) { return hourWeight[hour]; }

    public double dayRate(LocalDate d) { return monthBase[d.getMonthValue() - 1]; }

    public int peakMonth() {
        int best = 0; double v = -1;
        for (int m = 0; m < 12; m++) if (monthBase[m] > v) { v = monthBase[m]; best = m; }
        return best;
    }

    public int peakHour() {
        int best = 0; double v = -1;
        for (int h = 0; h < 24; h++) if (hourWeight[h] > v) { v = hourWeight[h]; best = h; }
        return best;
    }

    public String yearRange() { return yearFrom == 0 ? "-" : yearFrom + "-" + yearTo; }

    // ---------- validation ----------

    /**
     * Backtest: build predictions from training years only, then rank days in
     * the test years by predicted risk and measure how many of the actual
     * strikes fall in the highest-ranked fractions of days. If risk were
     * randomly spread in time, the top 10% of days would capture 10% of
     * strikes; a well-placed model captures more.
     */
    private Validation validate(int trainUpToYear) {
        // test-period actuals, and the full calendar range they span
        LocalDate min = null, max = null;
        Map<LocalDate, Integer> actual = new HashMap<>();
        for (var r : recordsForAirport()) {
            if (r.date().getYear() > trainUpToYear) {
                actual.merge(r.date(), 1, Integer::sum);
                if (min == null || r.date().isBefore(min)) min = r.date();
                if (max == null || r.date().isAfter(max)) max = r.date();
            }
        }
        if (actual.isEmpty()) return new Validation(0, 0, 0, 0, List.of());

        // rank EVERY calendar day in the test period (zero-strike days included)
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = min; !d.isAfter(max); d = d.plusDays(1)) days.add(d);
        int totalStrikes = 0;
        for (int v : actual.values()) totalStrikes += v;

        days.sort((a, b) -> Double.compare(dayRate(b), dayRate(a)));  // high risk first

        double[] curvePts = new double[days.size()];
        double cum = 0;
        for (int i = 0; i < days.size(); i++) {
            cum += actual.getOrDefault(days.get(i), 0);
            curvePts[i] = cum / totalStrikes;
        }
        List<double[]> curve = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            curve.add(new double[] { (i + 1.0) / days.size(), curvePts[i] });
        }

        double cap10 = capture(curve, 0.10), cap20 = capture(curve, 0.20);

        // correlation between predicted day rate and actual count (Pearson)
        double mp = 0, ma = 0;
        double[] pred = new double[days.size()];
        double[] act = new double[days.size()];
        for (int i = 0; i < days.size(); i++) {
            pred[i] = dayRate(days.get(i));
            act[i] = actual.getOrDefault(days.get(i), 0);
            mp += pred[i]; ma += act[i];
        }
        mp /= days.size(); ma /= days.size();
        double sp = 0, sa = 0, spa = 0;
        for (int i = 0; i < days.size(); i++) {
            double dp = pred[i] - mp, da = act[i] - ma;
            sp += dp * dp; sa += da * da; spa += dp * da;
        }
        double r = (sp > 0 && sa > 0) ? spa / Math.sqrt(sp * sa) : 0;

        return new Validation(cap10, cap20, r, totalStrikes, curve);
    }

    private static double capture(List<double[]> curve, double frac) {
        double best = 0;
        for (double[] p : curve) {
            if (p[0] >= frac) { best = p[1]; break; }
            best = p[1];
        }
        return best;
    }

    private List<FaaCsvParser.StrikeRecord> recordsForAirportCache;
    private List<FaaCsvParser.StrikeRecord> recordsForAirport() {
        if (recordsForAirportCache == null) throw new IllegalStateException("attach records first");
        return recordsForAirportCache;
    }

    /**
     * Validation must rank the test-period days, so it needs the raw records.
     * The model is fitted on training years only (up to and including
     * trainUpToYear); all later years form the test period.
     */
    public static Validation validate(List<FaaCsvParser.StrikeRecord> records,
                                      int trainUpToYear) {
        List<FaaCsvParser.StrikeRecord> train = new ArrayList<>();
        for (var r : records) {
            if (r.date().getYear() <= trainUpToYear) train.add(r);
        }
        RiskModel m = new RiskModel(train, "x");
        m.recordsForAirportCache = records;   // full set, for test-period actuals
        return m.validate(trainUpToYear);
    }

    public record Validation(double capture10, double capture20, double correlation,
                             int testStrikes, List<double[]> captureCurve) {}
}

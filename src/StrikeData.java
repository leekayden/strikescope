import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory strike dataset with the aggregate views the UI needs.
 */
public final class StrikeData {

    private final List<FaaCsvParser.StrikeRecord> records;
    private final Map<String, List<FaaCsvParser.StrikeRecord>> byAirport = new LinkedHashMap<>();
    private boolean demo = false;

    public StrikeData(List<FaaCsvParser.StrikeRecord> records, boolean demo) {
        this.records = List.copyOf(records);
        this.demo = demo;
        for (FaaCsvParser.StrikeRecord r : this.records) {
            byAirport.computeIfAbsent(r.airportId(), k -> new ArrayList<>()).add(r);
        }
    }

    public boolean isDemo() { return demo; }
    public int size() { return records.size(); }
    public List<String> airportIds() { return new ArrayList<>(byAirport.keySet()); }
    public List<FaaCsvParser.StrikeRecord> recordsFor(String airport) {
        return byAirport.getOrDefault(airport, List.of());
    }
    public List<FaaCsvParser.StrikeRecord> allRecords() { return records; }

    /** Airports with enough history to model, best first. */
    public List<String> modelableAirports() {
        List<String> ids = new ArrayList<>();
        for (var e : byAirport.entrySet()) if (e.getValue().size() >= 50) ids.add(e.getKey());
        return ids;
    }

    /** Aggregated counts for one airport. */
    public static final class Profile {
        public final String airport;
        public final Map<String, Integer> species = new HashMap<>();
        public final Map<String, Integer> phase = new HashMap<>();
        public final Map<Integer, Integer> perYear = new HashMap<>();
        public int total, damaged;

        public Profile(String airport) { this.airport = airport; }

        public List<Map.Entry<String, Integer>> topSpecies(int n) {
            return top(species, n);
        }

        public List<Map.Entry<String, Integer>> topPhase(int n) {
            return top(phase, n);
        }

        private static List<Map.Entry<String, Integer>> top(Map<String, Integer> m, int n) {
            List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
            l.sort((a, b) -> b.getValue() - a.getValue());
            return l.subList(0, Math.min(n, l.size()));
        }

        public double damageRate() { return total == 0 ? 0 : (double) damaged / total; }
    }

    public Profile profile(String airport) {
        Profile p = new Profile(airport);
        for (FaaCsvParser.StrikeRecord r : recordsFor(airport)) {
            p.total++;
            if (r.damaged()) p.damaged++;
            String sp = r.species() == null || r.species().isBlank() ? "Unknown" : r.species();
            p.species.merge(sp, 1, Integer::sum);
            String ph = r.phase() == null || r.phase().isBlank() ? "Unknown" : r.phase();
            p.phase.merge(ph, 1, Integer::sum);
            p.perYear.merge(r.date().getYear(), 1, Integer::sum);
        }
        return p;
    }

    /** Count of strikes per (year, month) for one airport. */
    public Map<Integer, int[]> yearMonthCounts(String airport) {
        Map<Integer, int[]> m = new java.util.TreeMap<>();
        for (FaaCsvParser.StrikeRecord r : recordsFor(airport)) {
            m.computeIfAbsent(r.date().getYear(), y -> new int[12])[r.date().getMonthValue() - 1]++;
        }
        return m;
    }

    /** Sorted distinct years present for an airport. */
    public List<Integer> years(String airport) {
        List<Integer> ys = new ArrayList<>(yearMonthCounts(airport).keySet());
        Collections.sort(ys);
        return ys;
    }
}

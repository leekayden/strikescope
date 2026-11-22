import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Tolerant parser for FAA National Wildlife Strike Database exports.
 * Column names differ slightly between the CSV search exports and the bulk
 * Excel/Access downloads (e.g. NUM_STRUCK vs Quantity), so headers are mapped
 * by fuzzy, case-insensitive rules rather than exact positions.
 *
 * Input rows can come from a CSV string or streamed from a .xlsx workbook —
 * the caller only has to supply rows as String[] arrays.
 */
public final class FaaCsvParser {

    /** Source of rows; implementations stream so huge files stay out of memory. */
    public interface RowSource { void supply(RowConsumer out); }

    public interface RowConsumer { void row(String[] cells); }

    /** One strike report. hour == -1 when the report has no usable clock time. */
    public record StrikeRecord(
            String airportId, LocalDate date, int hour, String timeOfDay,
            String phase, String species, String size, int quantity,
            boolean damaged, String sky, String precip,
            String operator, String aircraft) {

        public String hourText() {
            if (hour >= 0) return String.format("%02d:00", hour);
            if (timeOfDay != null && !timeOfDay.isBlank()) return timeOfDay;
            return "--";
        }
    }

    public record ParseResult(List<StrikeRecord> records, int skipped,
                              List<String> airports, int minYear, int maxYear,
                              Map<String, String> columns) {}

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("M/d/yy"),
            DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy")
    };

    // ------------------------------------------------------------------ entry

    public static ParseResult parse(String csv) {
        List<String[]> rows = splitCsv(csv);
        return parse(out -> { for (String[] r : rows) out.row(r); });
    }

    /** Streams rows from any source (CSV list, .xlsx sheet, …) into records. */
    public static ParseResult parse(RowSource source) {
        Acc acc = new Acc();
        source.supply(acc);
        return acc.result();
    }

    // ------------------------------------------------------------ accumulator

    private static final class Acc implements RowConsumer {
        int cAirport = -1, cDate = -1, cTime = -1, cTod = -1, cPhase = -1,
            cSpecies = -1, cSize = -1, cQty = -1, cDamage = -1, cSky = -1,
            cPrecip = -1, cOperator = -1, cAircraft = -1;

        final List<StrikeRecord> records = new ArrayList<>(400_000);
        final TreeSet<String> airports = new TreeSet<>();
        /** canonical field -> header text actually found (or "-"), for UI reporting. */
        final Map<String, String> columns = new LinkedHashMap<>();
        int skipped, minYear = Integer.MAX_VALUE, maxYear = Integer.MIN_VALUE;
        boolean headerSeen;

        @Override public void row(String[] f) {
            if (!headerSeen) { mapHeader(f); headerSeen = true; return; }
            if (f.length < 2) { skipped++; return; }

            String airport = field(f, cAirport).toUpperCase(Locale.ROOT);
            if (airport.isEmpty() || airport.length() > 8) { skipped++; return; }

            String dateStr = field(f, cDate);
            // Excel serial dates arrive as bare numbers (e.g. 33105.5)
            if (dateStr.matches("\\d{4,5}(\\.\\d+)?")) {
                dateStr = serialToIso(dateStr);
            }
            ParsedDate pd = parseDate(dateStr);
            if (pd == null) { skipped++; return; }

            int hour = -1;
            if (pd.hour >= 0) hour = pd.hour;
            if (hour < 0 && cTime >= 0) hour = parseClock(field(f, cTime));
            String tod = field(f, cTod);
            if (hour < 0 && !tod.isEmpty()) hour = hourFromBucket(tod);

            records.add(new StrikeRecord(airport, pd.date, hour,
                    tod.isEmpty() ? null : tod,
                    field(f, cPhase), field(f, cSpecies), field(f, cSize),
                    parseQty(field(f, cQty)), isDamaged(field(f, cDamage)),
                    field(f, cSky), field(f, cPrecip),
                    field(f, cOperator), field(f, cAircraft)));
            airports.add(airport);
            minYear = Math.min(minYear, pd.date.getYear());
            maxYear = Math.max(maxYear, pd.date.getYear());
        }

        ParseResult result() {
            if (records.isEmpty()) minYear = maxYear = 0;
            return new ParseResult(records, skipped, new ArrayList<>(airports),
                    minYear, maxYear, columns);
        }

        private void mapHeader(String[] header) {
            int speciesFb = -1;
            for (int i = 0; i < header.length; i++) {
                String h = norm(header[i]);
                if (cAirport < 0 && (h.equals("airport id") || h.equals("airport code"))) cAirport = i;
                if (cDate < 0 && (h.contains("incident date") || h.equals("flight date") || h.equals("date"))) cDate = i;
                if (cTime < 0 && h.equals("time")) cTime = i;
                if (cTod < 0 && h.contains("time of day")) cTod = i;
                if (cPhase < 0 && h.contains("phase")) cPhase = i;
                if (cSpecies < 0 && h.equals("species")) cSpecies = i;
                if (speciesFb < 0 && h.contains("species")) speciesFb = i;
                if (cSize < 0 && h.equals("size")) cSize = i;
                if (cQty < 0 && (h.contains("quantity") || h.contains("number struck")
                        || h.contains("num struck"))) cQty = i;
                if (cDamage < 0 && (h.contains("damage level") || h.contains("indicated damage")
                        || h.equals("damage") || h.equals("damaged"))) cDamage = i;
                if (cSky < 0 && h.contains("sky")) cSky = i;
                if (cPrecip < 0 && h.contains("precip")) cPrecip = i;
                if (cOperator < 0 && h.contains("operator")) cOperator = i;
                if (cAircraft < 0 && h.contains("aircraft")) cAircraft = i;
            }
            if (cAirport < 0) { // last resort: any *airport* column
                for (int i = 0; i < header.length; i++)
                    if (norm(header[i]).contains("airport")) { cAirport = i; break; }
            }
            if (cSpecies < 0) cSpecies = speciesFb;
            // remember the raw header text behind each field so the UI can report it
            recordMapping(header, "airport", cAirport);
            recordMapping(header, "date", cDate);
            recordMapping(header, "time", cTime);
            recordMapping(header, "time of day", cTod);
            recordMapping(header, "phase of flight", cPhase);
            recordMapping(header, "species", cSpecies);
            recordMapping(header, "size", cSize);
            recordMapping(header, "number struck", cQty);
            recordMapping(header, "damage", cDamage);
            recordMapping(header, "sky", cSky);
            recordMapping(header, "precipitation", cPrecip);
            recordMapping(header, "operator", cOperator);
            recordMapping(header, "aircraft", cAircraft);
            if (cAirport < 0 || cDate < 0) {
                throw new IllegalArgumentException(
                        "Could not find AIRPORT_ID and INCIDENT_DATE columns in the header. "
                        + "This does not look like a FAA Wildlife Strike Database export.");
            }
        }

        private void recordMapping(String[] header, String canonical, int idx) {
            columns.put(canonical, idx >= 0 && idx < header.length ? header[idx].trim() : "-");
        }
    }

    // --------------------------------------------------------------- helpers

    private record ParsedDate(LocalDate date, int hour) {}

    private static String field(String[] f, int idx) {
        return idx >= 0 && idx < f.length ? f[idx].trim() : "";
    }

    /** "33105.5" -> "1990-09-09 12:00" (Excel serial, epoch 1899-12-30). */
    private static String serialToIso(String s) {
        try {
            double v = Double.parseDouble(s);
            LocalDate d = LocalDate.of(1899, 12, 30).plusDays((long) Math.floor(v));
            double frac = v - Math.floor(v);
            LocalTime t = LocalTime.MIN.plusSeconds((long) (frac * 86400));
            return d + (frac > 0 ? " " + t : "");
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private static ParsedDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.replace('T', ' ').trim();
        int hour = -1;
        // a clock time may ride along in the date string ("6/25/2022 14:50")
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,2}):(\\d{2})").matcher(s);
        if (m.find()) {
            hour = Integer.parseInt(m.group(1));
            if (hour < 0 || hour > 23) hour = -1;
        }
        String datePart = s.replaceAll(",\\s*", " ")
                .replaceAll("\\s+\\d{1,2}:\\d{2}(:\\d{2})?\\s*(AM|PM|am|pm)?", "")
                .trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                LocalDate d = LocalDate.parse(datePart, fmt);
                if (d.getYear() < 100) d = d.withYear(2000 + d.getYear());
                return new ParsedDate(d, hour);
            } catch (Exception ignore) { /* try next format */ }
        }
        return null;
    }

    /** "14:50", "7:05", "1425" -> hour, or -1. */
    private static int parseClock(String s) {
        if (s == null || s.isBlank()) return -1;
        s = s.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{1,2}):(\\d{2})").matcher(s);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            return (h >= 0 && h <= 23) ? h : -1;
        }
        if (s.matches("\\d{4}")) {
            int h = Integer.parseInt(s.substring(0, 2));
            return (h <= 23) ? h : -1;
        }
        return -1;
    }

    private static int hourFromBucket(String tod) {
        return switch (tod.trim().toUpperCase(Locale.ROOT)) {
            case "DAWN" -> 6;
            case "DAY" -> 12;
            case "DUSK" -> 18;
            case "NIGHT" -> 23;
            default -> -1;
        };
    }

    private static int parseQty(String s) {
        if (s == null || s.isBlank()) return 1;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "over 100" -> 150;
            case "11-100" -> 55;
            case "2-10" -> 6;
            default -> {
                try { yield Math.max(1, Integer.parseInt(s.trim())); }
                catch (NumberFormatException e) { yield 1; }
            }
        };
    }

    private static boolean isDamaged(String s) {
        if (s == null) return false;
        String v = s.trim().toLowerCase(Locale.ROOT);
        // INDICATED_DAMAGE arrives as Yes/No in some exports and 1/0 in others;
        // DAMAGE_LEVEL uses the single-letter codes N/M/S/D (none is NOT damage)
        return v.contains("substantial") || v.contains("destroyed") || v.contains("minor")
                || v.equals("yes") || v.equals("true") || v.equals("y") || v.equals("1")
                || v.equals("m") || v.equals("s") || v.equals("d");
    }

    private static String norm(String h) {
        String s = h == null ? "" : h;
        s = s.replace("\uFEFF", "").toLowerCase(Locale.ROOT).trim();
        return s.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Minimal RFC-4180 CSV splitting (quoted fields, embedded commas/newlines). */
    private static List<String[]> splitCsv(String csv) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else cur.append(c);
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(cur.toString()); cur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(cur.toString()); cur.setLength(0);
                rows.add(row.toArray(new String[0])); row = new ArrayList<>();
            } else cur.append(c);
        }
        if (cur.length() > 0 || !row.isEmpty()) {
            row.add(cur.toString());
            rows.add(row.toArray(new String[0]));
        }
        return rows;
    }
}

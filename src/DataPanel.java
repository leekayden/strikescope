import java.awt.BorderLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/** Data & Import screen: table view, FAA CSV import, demo loader. */
final class DataPanel extends JPanel {

    private final AppContext ctx;
    private final Runnable onChanged;
    private final BiConsumer<Boolean, String> busy;
    private final JLabel summary = new JLabel("No dataset loaded.");
    private final RecordModel tableModel = new RecordModel();
    private final AirportSummaryModel airportModel = new AirportSummaryModel();

    DataPanel(AppContext ctx, Runnable onChanged, BiConsumer<Boolean, String> busy) {
        this.ctx = ctx;
        this.onChanged = onChanged;
        this.busy = busy;
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        JPanel top = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel();
        JButton importBtn = new JButton("Import FAA data (Excel / CSV)…");
        JButton demoBtn = new JButton("Load demo dataset");
        JButton formatBtn = new JButton("FAA file format…");
        buttons.add(importBtn);
        buttons.add(demoBtn);
        buttons.add(formatBtn);
        top.add(buttons, BorderLayout.EAST);
        top.add(summary, BorderLayout.WEST);
        add(top, BorderLayout.NORTH);

        JTable table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setAutoCreateRowSorter(true);
        JTable airportsTable = new JTable(airportModel);
        airportsTable.setRowHeight(22);
        airportsTable.setAutoCreateRowSorter(true);
        JScrollPane airportsScroll = new JScrollPane(airportsTable);
        airportsScroll.setBorder(BorderFactory.createTitledBorder(
                "Airports in this dataset (the other screens list every airport with ≥ 50 records)"));
        JScrollPane tableScroll = new JScrollPane(table);
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT, tableScroll, airportsScroll);
        split.setResizeWeight(0.62);
        add(split, BorderLayout.CENTER);

        JLabel help = new JLabel(
                "<html><body style='width:880px'><b>How to get real data (free, legal):</b> the bulk "
                + "download at wildlife.faa.gov comes as <b>Excel (.xlsx)</b> or Access — pick Excel, "
                + "then import the file here directly; nothing to convert. The FAA search UI can also "
                + "export per-airport results. The full Excel export is ~160 MB on disk and takes a "
                + "minute or two to stream in. Press <b>FAA file format…</b> for the exact column "
                + "layout this app expects. Data: FAA National Wildlife Strike Database, "
                + "US public domain.</body></html>");
        help.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        add(help, BorderLayout.SOUTH);

        importBtn.addActionListener(e -> importCsv());
        demoBtn.addActionListener(e -> loadDemo());
        formatBtn.addActionListener(e -> showFormatDialog());
    }

    void refresh() {
        if (ctx.data == null) {
            summary.setText("No dataset loaded.");
            tableModel.set(List.of());
            airportModel.set(null);
        } else {
            summary.setText(String.format("%,d records · %d airports · demo=%b",
                    ctx.data.size(), ctx.data.airportIds().size(), ctx.data.isDemo()));
            tableModel.set(ctx.data.allRecords());
            airportModel.set(ctx.data);
        }
    }

    private void loadDemo() {
        ctx.set(DemoData.generate());
        afterLoad("Demo dataset loaded (SYNTHETIC numbers, real-world-shaped patterns).");
    }

    private void importCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "FAA strike data (Excel or CSV)", "xlsx", "csv", "txt"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        boolean xlsx = p.toString().toLowerCase().endsWith(".xlsx");
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        busy.accept(true, "Importing FAA data… (large exports take a minute or two)");
        new SwingWorker<FaaCsvParser.ParseResult, Integer>() {
            @Override protected FaaCsvParser.ParseResult doInBackground() {
                try {
                    if (xlsx) {
                        return FaaCsvParser.parse(consumer -> {
                            long[] n = {0};
                            FaaXlsxReader.read(p, row -> {
                                consumer.row(row);
                                if (++n[0] % 25_000 == 0) publish((int) n[0]);
                            });
                        });
                    }
                    return FaaCsvParser.parse(Files.readString(p, StandardCharsets.UTF_8));
                } catch (Exception ex) {
                    throw new RuntimeException(ex.getMessage(), ex);
                }
            }
            @Override protected void process(List<Integer> chunks) {
                summary.setText(String.format("Importing… %,d rows read", chunks.get(chunks.size() - 1)));
            }
            @Override protected void done() {
                busy.accept(false, "");
                setCursor(java.awt.Cursor.getDefaultCursor());
                try {
                    FaaCsvParser.ParseResult r = get();
                    if (r == null || r.records().isEmpty()) {
                        JOptionPane.showMessageDialog(DataPanel.this,
                                "No usable rows found — check that this is a FAA Wildlife "
                                + "Strike Database export (Excel or CSV). See the "
                                + "'FAA file format…' dialog for the expected layout.");
                        return;
                    }
                    ctx.set(new StrikeData(r.records(), false));
                    afterLoad(String.format(
                            "Imported %,d records · skipped %,d rows · airports: %s · years %d–%d · "
                                    + "columns matched: %s",
                            r.records().size(), r.skipped(), r.airports().size(),
                            r.minYear(), r.maxYear(), describeColumns(r.columns())));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(DataPanel.this,
                            "Import failed: " + rootMessage(ex));
                }
            }
            private String rootMessage(Throwable t) {
                while (t.getCause() != null) t = t.getCause();
                return t.getMessage();
            }
        }.execute();
    }

    private void afterLoad(String msg) {
        summary.setText(msg);
        onChanged.run();
        refresh();
    }

    /** "AIRPORT_ID, INCIDENT_DATE, TIME_OF_DAY, … (12 of 13 fields found)". */
    private static String describeColumns(java.util.Map<String, String> cols) {
        if (cols == null || cols.isEmpty()) return "-";
        List<String> found = new java.util.ArrayList<>();
        int missing = 0;
        for (var e : cols.entrySet()) {
            if ("-".equals(e.getValue()) || e.getValue().isBlank()) missing++;
            else found.add(e.getValue());
        }
        return String.join(", ", found) + String.format("  (%d of %d fields found)",
                found.size(), cols.size());
    }

    // ------------------------------------------------- FAA NWSD file format

    /**
     * Dialog spelling out the exact layout of the FAA National Wildlife Strike
     * Database bulk Excel export (verified against data/Public.xlsx, header
     * row 1, 102 columns A–CX) and which of those columns this app reads.
     */
    private void showFormatDialog() {
        String html =
            "<html><body style='width:640px; line-height:1.4'>"
            + "<h2>FAA National Wildlife Strike Database — file format</h2>"
            + "<p>The bulk download from <b>wildlife.faa.gov</b> (Download Database → Excel) is a "
            + "workbook typically named <code>Public.xlsx</code>: one worksheet, the header in "
            + "<b>row 1</b>, <b>102 columns (A–CX)</b>, one row per strike report — about 360,000 "
            + "rows covering 1990–2026, ~160 MB on disk. Dates are stored as ISO text "
            + "(<code>1996-06-22</code>); serial-number dates in other exports are handled too.</p>"
            + "<h3>Columns StrikeScope reads</h3>"
            + "<table cellpadding=3 style='border-collapse:collapse'>"
            + "<tr><th align=left>Column</th><th align=left>Example values</th><th align=left>Used for</th></tr>"
            + "<tr><td><code>AIRPORT_ID</code></td><td>KDEN, KSMF, VIDP…</td><td>airport key (required)</td></tr>"
            + "<tr><td><code>INCIDENT_DATE</code></td><td>1996-06-22</td><td>date, month &amp; year model (required)</td></tr>"
            + "<tr><td><code>TIME</code></td><td>19:05 (or 0700, or blank)</td><td>hour-of-day model</td></tr>"
            + "<tr><td><code>TIME_OF_DAY</code></td><td>Dawn / Day / Dusk / Night</td><td>hour weights when TIME is blank</td></tr>"
            + "<tr><td><code>PHASE_OF_FLIGHT</code></td><td>Take-off Run, Climb, Approach, Landing Roll…</td><td>profile chart</td></tr>"
            + "<tr><td><code>SPECIES</code></td><td>Horned lark, Unknown bird - medium…</td><td>species chart</td></tr>"
            + "<tr><td><code>SIZE</code></td><td>Small / Medium / Large</td><td>profile table</td></tr>"
            + "<tr><td><code>NUM_STRUCK</code></td><td>1, 2-10, 11-100, Over 100</td><td>record table</td></tr>"
            + "<tr><td><code>INDICATED_DAMAGE</code></td><td>Yes / No (or TRUE/FALSE, 1/0)</td><td>damage charts</td></tr>"
            + "<tr><td><code>DAMAGE_LEVEL</code></td><td>N / M / S / D = none / minor / substantial / destroyed</td><td>damage fallback</td></tr>"
            + "<tr><td><code>SKY</code> / <code>PRECIPITATION</code></td><td>No Cloud, Overcast / None, Rain…</td><td>stored (future weather work)</td></tr>"
            + "<tr><td><code>OPERATOR</code> / <code>AIRCRAFT</code></td><td>UNITED AIRLINES / B-737-300</td><td>record table</td></tr>"
            + "</table>"
            + "<p>Headers are matched case-insensitively and tolerantly, so the CSV exports from "
            + "the FAA search UI (which use the same names) also import. After each import the "
            + "status line reports exactly which columns were matched.</p>"
            + "<h3>Full column list (102)</h3>"
            + "<p style='font-size:11px'>INDEX_NR, INCIDENT_DATE, INCIDENT_MONTH, INCIDENT_YEAR, "
            + "TIME, TIME_OF_DAY, AIRPORT_ID, AIRPORT, LATITUDE, LONGITUDE, RUNWAY, STATE, "
            + "FAAREGION, LOCATION, OPID, OPERATOR, REG, FLT, AIRCRAFT, AMA, AMO, EMA, EMO, "
            + "AC_CLASS, AC_MASS, TYPE_ENG, NUM_ENGS, ENG_1_POS…ENG_4_POS, PHASE_OF_FLIGHT, "
            + "HEIGHT, SPEED, DISTANCE, SKY, PRECIPITATION, AOS, COST_REPAIRS, COST_OTHER, "
            + "COST_REPAIRS_INFL_ADJ, COST_OTHER_INFL_ADJ, INGESTED_OTHER, INDICATED_DAMAGE, "
            + "DAMAGE_LEVEL, STR_/DAM_ component columns (radar, windshield, nose, engines 1–4, "
            + "prop, wing/rotor, fuselage, landing gear, tail, lights, other), OTHER_SPECIFY, "
            + "EFFECT, EFFECT_OTHER, BIRD_BAND_NUMBER, SPECIES_ID, SPECIES, "
            + "OUT_OF_RANGE_SPECIES, REMARKS, REMAINS_COLLECTED, REMAINS_SENT, WARNED, NUM_SEEN, "
            + "NUM_STRUCK, SIZE, ENROUTE_STATE, NR_INJURIES, NR_FATALITIES, COMMENTS, IMAGE, "
            + "REPORTED_NAME, REPORTED_TITLE, SOURCE, PERSON, LUPDATE, TRANSFER. "
            + "(Names reported as REDACTED protect reporter identity.)</p>"
            + "</body></html>";
        javax.swing.JEditorPane ep = new javax.swing.JEditorPane("text/html", html);
        ep.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(ep),
                "FAA NWSD Excel format", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Simple read-only table model over the records (capped for responsiveness). */
    private static final class RecordModel extends AbstractTableModel {
        private List<FaaCsvParser.StrikeRecord> rows = List.of();
        private static final String[] COLS = { "Airport", "Date", "Time", "Phase of flight",
                "Species", "Size", "Qty", "Damaged" };
        private static final int CAP = 8000;

        void set(List<FaaCsvParser.StrikeRecord> all) {
            this.rows = all.size() > CAP ? all.subList(0, CAP) : all;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Class<?> getColumnClass(int c) { return String.class; }

        @Override public Object getValueAt(int r, int c) {
            FaaCsvParser.StrikeRecord s = rows.get(r);
            return switch (c) {
                case 0 -> s.airportId();
                case 1 -> s.date();
                case 2 -> s.hourText();
                case 3 -> s.phase();
                case 4 -> s.species();
                case 5 -> s.size();
                case 6 -> s.quantity();
                default -> s.damaged() ? "Yes" : "No";
            };
        }
    }

    /** One row per airport: volume, year coverage, damage and time-of-day coverage. */
    private static final class AirportSummaryModel extends AbstractTableModel {
        private static final String[] COLS = { "Airport", "Records", "Years", "Damage %",
                "Reports with time" };
        private List<Object[]> rows = List.of();

        void set(StrikeData data) {
            if (data == null) { rows = List.of(); fireTableDataChanged(); return; }
            List<Object[]> out = new java.util.ArrayList<>();
            for (String id : data.airportIds()) {
                var recs = data.recordsFor(id);
                int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, dmg = 0, withTime = 0;
                for (var r : recs) {
                    int y = r.date().getYear();
                    if (y < min) min = y;
                    if (y > max) max = y;
                    if (r.damaged()) dmg++;
                    if (r.hour() >= 0 || (r.timeOfDay() != null && !r.timeOfDay().isBlank())) withTime++;
                }
                out.add(new Object[] { id, recs.size(), min + "–" + max,
                        String.format("%.1f%%", 100.0 * dmg / Math.max(1, recs.size())),
                        String.format("%.0f%%", 100.0 * withTime / Math.max(1, recs.size())) });
            }
            out.sort((a, b) -> Integer.compare((int) b[1], (int) a[1]));
            rows = out;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Class<?> getColumnClass(int c) {
            return c == 1 ? Integer.class : String.class;
        }
        @Override public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
    }
}

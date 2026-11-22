import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;

/**
 * Cross-airport comparison heatmap: one row per airport, one column per month,
 * each row scaled to that airport's own peak (100). Answers "which months are
 * peak for EVERY airport at a glance" — the per-airport screens only show one
 * airport at a time. Sorted by reported-strike volume, best first.
 */
final class AirportsPanel extends JPanel {

    private final AppContext ctx;
    private final BiConsumer<Boolean, String> busy;
    private final JComboBox<String> minRecords = new JComboBox<>(
            new String[] { "any (1+)", "10+ records", "50+ records", "100+ records",
                           "250+ records", "500+ records" });
    private final Charts.Heatmap heat = new Charts.Heatmap(new double[0][12],
            new String[0], RiskModel.MONTHS, "airport", "month");
    private final JLabel note = new JLabel(" ");

    private StrikeData builtFor;      // dataset the cache was built from
    private int builtMin = -1;
    private int generation;           // cancels stale async rebuilds

    AirportsPanel(AppContext ctx, BiConsumer<Boolean, String> busy) {
        this.ctx = ctx;
        this.busy = busy;
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        JPanel top = new JPanel();
        top.add(new JLabel("Show airports with: "));
        top.add(minRecords);
        top.add(javax.swing.Box.createHorizontalStrut(14));
        top.add(new JLabel("(rows sorted by strike volume)"));
        add(top, BorderLayout.NORTH);

        heat.setNormMode(Charts.Heatmap.Norm.NONE);   // every row is peak-normalised already
        heat.setRowHeightHint(14);
        heat.setRowLabelWidth(118);
        add(new JScrollPane(heat), BorderLayout.CENTER);

        note.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        add(note, BorderLayout.SOUTH);

        minRecords.addActionListener(e -> rebuild());
    }

    void refresh() {
        builtFor = null;   // dataset changed; force rebuild
        rebuild();
    }

    private int minRecordsValue() {
        return switch (minRecords.getSelectedIndex()) {
            case 0 -> 1; case 1 -> 10; case 2 -> 50; case 3 -> 100; case 4 -> 250; default -> 500;
        };
    }

    private void rebuild() {
        if (ctx.data == null) {
            heat.setValues(new double[0][12], new String[0]);
            return;
        }
        if (ctx.data == builtFor && minRecordsValue() == builtMin) return;   // cached
        final int min = minRecordsValue();
        final StrikeData data = ctx.data;

        List<String> ids = new ArrayList<>();
        for (String id : data.airportIds()) {
            if (data.recordsFor(id).size() >= min) ids.add(id);
        }
        ids.sort((a, b) -> Integer.compare(data.recordsFor(b).size(), data.recordsFor(a).size()));

        busy.accept(true, "Building airport comparison heatmap (" + ids.size() + " airports)…");
        final int gen = ++generation;
        new SwingWorker<double[][], Void>() {
            @Override protected double[][] doInBackground() {
                double[][] vals = new double[ids.size()][];
                int done = 0;
                for (int i = 0; i < ids.size(); i++) {
                    RiskModel m = new RiskModel(data.recordsFor(ids.get(i)), ids.get(i));
                    double[] row = new double[12];
                    for (int mo = 1; mo <= 12; mo++) {
                        double best = 0;
                        for (int h = 0; h < 24; h++) best = Math.max(best, m.riskIndex(mo, h));
                        row[mo - 1] = best;
                    }
                    vals[i] = row;
                    if (++done % 200 == 0) busy.accept(true,
                            "Airport comparison… " + done + "/" + ids.size());
                }
                return vals;
            }
            @Override protected void done() {
                if (gen != generation) return;   // a newer rebuild superseded this one
                busy.accept(false, "");
                try {
                    double[][] vals = get();
                    String[] labels = new String[ids.size()];
                    for (int i = 0; i < ids.size(); i++) {
                        labels[i] = String.format("%s · %,d", ids.get(i),
                                data.recordsFor(ids.get(i)).size());
                    }
                    heat.setValues(vals, labels);
                    note.setText(noteText(ids.size(), min));
                } catch (Exception ex) {
                    Throwable t = ex;
                    while (t.getCause() != null) t = t.getCause();
                    note.setText("<html><body style='width:880px'>Could not build comparison: "
                            + t.getMessage() + "</body></html>");
                }
            }
        }.execute();
        builtFor = data;
        builtMin = min;
    }

    private static String noteText(int shown, int min) {
        return "<html><body style='width:880px'>Each row is one airport; each cell is that "
                + "airport's peak risk index for the month (its own busiest month+hour = 100, so "
                + "every row is directly readable). Rows are sorted by the number of REPORTED "
                + "strikes — reporting effort differs wildly between airports, so a row's "
                + "brightness measures when strikes cluster, not how dangerous the airport is. "
                + "<code>ZZZZ</code> is the FAA's code for strikes reported without an "
                + "identifiable airport, so it usually tops the list. "
                + "Hover any cell for the airport, month and index. "
                + String.format("<b>Shown: %d airports</b> (records ≥ %d).</body></html>",
                        shown, min);
    }
}

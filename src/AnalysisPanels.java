import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;

/**
 * The three analysis screens: month x hour explorer, airport profile, and the
 * train/test backtest view.
 */
final class AnalysisPanels {

    private AnalysisPanels() {}

    // ------------------------------------------------------------- explorer

    static final class Explorer extends JPanel {
        private final AppContext ctx;
        private final JComboBox<String> airports = new JComboBox<>();
        private final JCheckBox damageOnly = new JCheckBox("Weight by damaging-strike frequency");
        private final Charts.Heatmap heat = new Charts.Heatmap(new double[12][24],
                RiskModel.MONTHS, hours(), "month", "hour");
        private final Charts.VBars monthBars = new Charts.VBars();
        private final Charts.VBars hourBars = new Charts.VBars();

        Explorer(AppContext ctx) {
            this.ctx = ctx;
            setLayout(new BorderLayout(0, 10));
            setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

            JPanel top = new JPanel();
            top.add(new JLabel("Airport: "));
            top.add(airports);
            top.add(javax.swing.Box.createHorizontalStrut(14));
            top.add(damageOnly);
            add(top, BorderLayout.NORTH);

            heat.setRowHeightHint(34);
            heat.setNormMode(Charts.Heatmap.Norm.NONE);   // cells are a 0-100 index already
            add(heat, BorderLayout.CENTER);

            JPanel strips = new JPanel(new GridLayout(1, 2, 12, 0));
            strips.add(wrap("Reported strikes per day, by month", monthBars));
            strips.add(wrap("Hour-of-day activity weight (busiest hour = 24)", hourBars));
            strips.setPreferredSize(new java.awt.Dimension(100, 190));
            add(strips, BorderLayout.SOUTH);

            JLabel note = new JLabel(
                    "<html><body style='width:640px'>Each cell is the model's relative risk index for that "
                    + "month and hour (hover for values). Dawn and dusk columns usually dominate: bird "
                    + "movement concentrates around sunrise and sunset, and strike reports follow that "
                    + "rhythm. Damaging strikes cluster by species size, so the damage-weighted view "
                    + "shifts towards the months when larger birds are present.</body></html>");
            note.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            add(note, BorderLayout.PAGE_END);

            airports.addActionListener(e -> {
                String id = (String) airports.getSelectedItem();
                if (id != null) ctx.selectAirport(id);
                update();
            });
            damageOnly.addActionListener(e -> update());
        }

        private static JPanel wrap(String title, javax.swing.JComponent c) {
            JPanel p = new JPanel(new BorderLayout());
            p.setBorder(BorderFactory.createTitledBorder(title));
            p.add(c, BorderLayout.CENTER);
            return p;
        }

        /** Called when the airport was changed on another page. */
        void syncAirport() {
            String sel = ctx.selectedAirport;
            if (sel == null) return;
            for (int i = 0; i < airports.getItemCount(); i++) {
                if (airports.getItemAt(i).equals(sel)) {
                    if (!sel.equals(airports.getSelectedItem())) airports.setSelectedIndex(i);
                    return;
                }
            }
        }

        private static String[] hours() {
            String[] h = new String[24];
            for (int i = 0; i < 24; i++) h[i] = String.format("%02d", i);
            return h;
        }

        void refresh() {
            airports.removeAllItems();
            if (ctx.data == null) return;
            for (String id : ctx.data.modelableAirports()) airports.addItem(id);
            syncAirport();
            if (airports.getSelectedItem() == null && airports.getItemCount() > 0) airports.setSelectedIndex(0);
            update();
        }

        private void update() {
            String id = (String) airports.getSelectedItem();
            if (id == null || ctx.data == null) return;
            double[][] vals = new double[12][24];
            RiskModel m = ctx.models.get(id);
            if (m == null) return;
            Map<Integer, Double> dmgRate = damageOnly.isSelected() ? damageRates(ctx.data, id) : Map.of();
            for (int mo = 1; mo <= 12; mo++) {
                double f = dmgRate.isEmpty() ? 1.0
                        : dmgRate.getOrDefault(mo, 0.05) * 20;   // scale so ~1.0 is typical
                for (int h = 0; h < 24; h++) vals[mo - 1][h] = m.riskIndex(mo, h) * f;
            }
            // absolute colours for the plain 0-100 index; rescaled when the damage
            // factor pushes values above 100 so the grid stays readable
            heat.setNormMode(dmgRate.isEmpty() ? Charts.Heatmap.Norm.NONE
                                               : Charts.Heatmap.Norm.GRID);
            heat.setValues(vals);

            // side profiles (same data the web prototype shows as bar charts)
            double[] monthVals = new double[12];
            String[] monthLabels = new String[12];
            String[] monthTips = new String[12];
            for (int mo = 1; mo <= 12; mo++) {
                monthVals[mo - 1] = m.monthRate(mo);
                monthLabels[mo - 1] = RiskModel.MONTHS[mo - 1];
                monthTips[mo - 1] = String.format("%s — %.2f reported strikes/day",
                        RiskModel.MONTHS[mo - 1], m.monthRate(mo));
            }
            monthBars.setValues(monthVals, monthLabels, monthTips);
            double[] hourVals = new double[24];
            String[] hourLabels = new String[24];
            String[] hourTips = new String[24];
            for (int h = 0; h < 24; h++) {
                hourVals[h] = m.hourWeightOf(h);
                hourLabels[h] = String.format("%02d", h);
                hourTips[h] = String.format("%02d:00 local — weight %.2f (busiest hour = 24)",
                        h, m.hourWeightOf(h));
            }
            hourBars.setValues(hourVals, hourLabels, hourTips);
        }

        /** Damage frequency by month, from records that carry damage info. */
        private static Map<Integer, Double> damageRates(StrikeData data, String airport) {
            Map<Integer, Integer> total = new HashMap<>();
            Map<Integer, Integer> dmg = new HashMap<>();
            for (var r : data.recordsFor(airport)) {
                int mo = r.date().getMonthValue();
                total.merge(mo, 1, Integer::sum);
                if (r.damaged()) dmg.merge(mo, 1, Integer::sum);
            }
            Map<Integer, Double> out = new HashMap<>();
            for (var e : total.entrySet()) {
                out.put(e.getKey(), dmg.getOrDefault(e.getKey(), 0) / (double) e.getValue());
            }
            return out;
        }
    }

    // ------------------------------------------------------- airport profile

    static final class AirportProfile extends JPanel {
        private final AppContext ctx;
        private final JComboBox<String> airports = new JComboBox<>();
        private final Charts.HBars species = new Charts.HBars();
        private final Charts.HBars phase = new Charts.HBars();
        private final Charts.Donut damage = new Charts.Donut("Damage",
                new String[] { "No damage", "Damaged" });
        private final Charts.Line yearly = new Charts.Line();
        private final JLabel stats = new JLabel(" ");

        AirportProfile(AppContext ctx) {
            this.ctx = ctx;
            setLayout(new BorderLayout(0, 10));
            setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

            JPanel top = new JPanel();
            top.add(new JLabel("Airport: "));
            top.add(airports);
            add(top, BorderLayout.NORTH);

            JPanel charts = new JPanel(new GridLayout(2, 2, 14, 10));
            charts.add(wrap("Top species (all years)", species));
            charts.add(wrap("Phase of flight", phase));
            charts.add(wrap("Damage", damage));
            charts.add(wrap("Reported strikes per year", yearly));
            add(charts, BorderLayout.CENTER);
            add(stats, BorderLayout.SOUTH);

            airports.addActionListener(e -> {
                String id = (String) airports.getSelectedItem();
                if (id != null) ctx.selectAirport(id);
                update();
            });
        }

        /** Called when the airport was changed on another page. */
        void syncAirport() {
            String sel = ctx.selectedAirport;
            if (sel == null) return;
            for (int i = 0; i < airports.getItemCount(); i++) {
                if (airports.getItemAt(i).equals(sel)) {
                    if (!sel.equals(airports.getSelectedItem())) airports.setSelectedIndex(i);
                    return;
                }
            }
        }

        private JPanel wrap(String title, javax.swing.JComponent c) {
            JPanel p = new JPanel(new BorderLayout());
            p.setBorder(BorderFactory.createTitledBorder(title));
            p.add(c, BorderLayout.CENTER);
            return p;
        }

        void refresh() {
            airports.removeAllItems();
            if (ctx.data == null) return;
            for (String id : ctx.data.modelableAirports()) airports.addItem(id);
            syncAirport();
            if (airports.getSelectedItem() == null && airports.getItemCount() > 0) airports.setSelectedIndex(0);
            update();
        }

        private void update() {
            String id = (String) airports.getSelectedItem();
            if (id == null || ctx.data == null) return;
            StrikeData.Profile p = ctx.data.profile(id);
            species.setItems(p.topSpecies(10));
            phase.setItems(p.topPhase(8));
            damage.setValues(new int[] { p.total - p.damaged, p.damaged });
            yearly.setYearly(p.perYear, "strikes/yr");
            stats.setText(String.format(
                    "<html><body style='width:900px'>%s — %d reported strikes in the dataset, "
                    + "%.1f%% involving damage. Remember: these are REPORTED strikes; comparison "
                    + "across airports mostly measures reporting effort, not just risk.</body></html>",
                    id, p.total, 100.0 * p.damaged / Math.max(1, p.total)));
        }
    }

    // -------------------------------------------------------------- validate

    static final class Validation extends JPanel {
        private final AppContext ctx;
        private final BiConsumer<Boolean, String> busy;
        private final JComboBox<String> airports = new JComboBox<>();
        private final JSpinner cutoff = new JSpinner(new SpinnerNumberModel(2018, 1990, 2030, 1));
        private final JButton run = new JButton("Run backtest");
        private final Charts.Line curve = new Charts.Line();
        private final JLabel result = new JLabel(" ");

        Validation(AppContext ctx, BiConsumer<Boolean, String> busy) {
            this.ctx = ctx;
            this.busy = busy;
            setLayout(new BorderLayout(0, 10));
            setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

            JPanel top = new JPanel();
            top.add(new JLabel("Airport: "));
            top.add(airports);
            top.add(new JLabel("   Train on years up to:"));
            top.add(cutoff);
            top.add(run);
            add(top, BorderLayout.NORTH);

            add(curve, BorderLayout.CENTER);

            JLabel note = new JLabel(
                    "<html><body style='width:640px'>Days in the test years are ranked by predicted "
                    + "risk. The dashed diagonal is what a useless model achieves. If the solid curve "
                    + "bulges above it, risk does concentrate where the model says — the numbers below "
                    + "quantify that with the top-10% / top-20% capture rates.</body></html>");
            note.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            add(note, BorderLayout.SOUTH);

            JPanel res = new JPanel(new BorderLayout());
            res.add(result, BorderLayout.CENTER);
            add(res, BorderLayout.EAST);

            airports.addActionListener(e -> {
                String id = (String) airports.getSelectedItem();
                run.setEnabled(ctx.data != null && id != null);
                if (id != null) ctx.selectAirport(id);
            });
            run.addActionListener(e -> runBacktest());
        }

        /** Called when the airport was changed on another page. */
        void syncAirport() {
            String sel = ctx.selectedAirport;
            if (sel == null) return;
            for (int i = 0; i < airports.getItemCount(); i++) {
                if (airports.getItemAt(i).equals(sel)) {
                    if (!sel.equals(airports.getSelectedItem())) airports.setSelectedIndex(i);
                    return;
                }
            }
        }

        void refresh() {
            airports.removeAllItems();
            if (ctx.data == null) return;
            for (String id : ctx.data.modelableAirports()) airports.addItem(id);
            syncAirport();
            if (airports.getSelectedItem() == null && airports.getItemCount() > 0) airports.setSelectedIndex(0);
            run.setEnabled(ctx.data != null && airports.getSelectedItem() != null);
        }

        private void runBacktest() {
            String id = (String) airports.getSelectedItem();
            if (id == null || ctx.data == null) {
                result.setText("<html><body style='width:260px'>Load a dataset first "
                        + "(Data &amp; Import), then pick an airport.</body></html>");
                return;
            }
            final String airport = id;
            final int cut = (int) cutoff.getValue();
            run.setEnabled(false);
            busy.accept(true, "Running backtest for " + airport + "…");
            new SwingWorker<RiskModel.Validation, Void>() {
                @Override protected RiskModel.Validation doInBackground() {
                    return RiskModel.validate(ctx.data.recordsFor(airport), cut);
                }
                @Override protected void done() {
                    busy.accept(false, "");
                    run.setEnabled(true);
                    try {
                        var v = get();
                        curve.setSeries(v.captureCurve(), "% of days (risk-ranked)", "strikes captured");
                        if (v.testStrikes() == 0) {
                            result.setText(String.format(
                                    "<html><body style='width:260px'>%s has no records after %d, "
                                    + "so there is nothing to test against. Lower the cutoff year.</body></html>",
                                    airport, cut));
                            return;
                        }
                        result.setText(String.format(
                                "<html><body style='width:260px'><b>Test years after %d</b><br>"
                                + "Strikes in test set: %d<br><br>"
                                + "Top 10%% of days captured<br><b>%.1f%%</b> of strikes (base 10%%)<br><br>"
                                + "Top 20%% of days captured<br><b>%.1f%%</b> of strikes (base 20%%)<br><br>"
                                + "Day-level correlation r = %.2f</body></html>",
                                cut, v.testStrikes(), v.capture10() * 100, v.capture20() * 100,
                                v.correlation()));
                    } catch (Exception ex) {
                        Throwable t = ex;
                        while (t.getCause() != null) t = t.getCause();
                        result.setText("<html><body style='width:260px'>Backtest failed: "
                                + t.getMessage() + "</body></html>");
                    }
                }
            }.execute();
        }
    }
}

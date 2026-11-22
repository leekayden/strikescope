import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerDateModel;
import javax.swing.SwingUtilities;

/**
 * Landing screen: pick airport + date + hour, read the relative risk index,
 * scan the 7-day x 24-hour outlook, and export a briefing.
 */
final class DashboardPanel extends JPanel {

    private final AppContext ctx;
    private final JComboBox<String> airports = new JComboBox<>();
    private final JSpinner date = new JSpinner(
            new SpinnerDateModel(new java.util.Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JSlider hour = new JSlider(0, 23, 7);
    private final JLabel hourLabel = new JLabel("07L", SwingUtilities.CENTER);
    private final Charts.Gauge gauge = new Charts.Gauge();
    private final Charts.Heatmap week = new Charts.Heatmap(new double[7][24],
            new String[7], hours(), "day", "hour");
    private final JTextArea briefing = new JTextArea();
    private final JLabel peakLabel = new JLabel(" ");
    private final JLabel stateBanner = new JLabel(" ");

    DashboardPanel(AppContext ctx) {
        this.ctx = ctx;
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        // values are already a 0-100 index relative to the airport's own peak:
        // colour cells absolutely so a quiet week stays green instead of being
        // rescaled to look red (same behaviour as the web prototype)
        week.setNormMode(Charts.Heatmap.Norm.NONE);

        // --- controls ---
        JPanel controls = new JPanel();
        controls.add(new JLabel("Airport:"));
        controls.add(airports);
        controls.add(javax.swing.Box.createHorizontalStrut(10));
        controls.add(new JLabel("Date:"));
        JSpinner.DateEditor de = new JSpinner.DateEditor(date, "EEE dd MMM yyyy");
        date.setEditor(de);
        controls.add(date);
        controls.add(javax.swing.Box.createHorizontalStrut(10));
        controls.add(new JLabel("Hour (local):"));
        hour.setPreferredSize(new Dimension(220, 30));
        controls.add(hour);
        hourLabel.setPreferredSize(new Dimension(44, 30));
        controls.add(hourLabel);

        JPanel ctlWrap = new JPanel(new BorderLayout());
        ctlWrap.add(controls, BorderLayout.CENTER);
        stateBanner.setOpaque(true);
        stateBanner.setBackground(new java.awt.Color(255, 179, 0));
        stateBanner.setForeground(new java.awt.Color(40, 30, 0));
        stateBanner.setFont(stateBanner.getFont().deriveFont(java.awt.Font.BOLD, 12.5f));
        stateBanner.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        stateBanner.setVisible(false);
        JPanel north = new JPanel(new BorderLayout());
        north.add(stateBanner, BorderLayout.NORTH);
        north.add(ctlWrap, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        // --- center: gauge + week outlook ---
        JPanel gaugeCard = new JPanel(new BorderLayout());
        gaugeCard.add(gauge, BorderLayout.CENTER);
        peakLabel.setHorizontalAlignment(SwingUtilities.CENTER);
        gaugeCard.add(peakLabel, BorderLayout.SOUTH);

        JPanel weekCard = new JPanel(new BorderLayout());
        weekCard.add(new JLabel("7-day outlook — relative strike-risk index by hour"),
                BorderLayout.NORTH);
        JScrollPane weekScroll = new JScrollPane(week);
        weekScroll.setBorder(null);
        weekCard.add(weekScroll, BorderLayout.CENTER);

        JPanel center = new JPanel(new GridLayout(1, 2, 14, 0));
        center.add(gaugeCard);
        center.add(weekCard);
        add(center, BorderLayout.CENTER);

        // --- bottom: briefing ---
        briefing.setEditable(false);
        briefing.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12).deriveFont(12.5f));
        JScrollPane sp = new JScrollPane(briefing);
        sp.setPreferredSize(new Dimension(100, 190));
        sp.setBorder(BorderFactory.createTitledBorder("Risk briefing (auto-generated)"));
        JButton export = new JButton("Export briefing as .txt");
        export.addActionListener(e -> exportBriefing());
        JPanel south = new JPanel(new BorderLayout());
        south.add(sp, BorderLayout.CENTER);
        JPanel exportRow = new JPanel(new BorderLayout());
        exportRow.add(new JLabel(" "), BorderLayout.CENTER);
        JPanel eb = new JPanel();
        eb.add(export);
        exportRow.add(eb, BorderLayout.EAST);
        south.add(exportRow, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        airports.addActionListener(e -> {
            String id = (String) airports.getSelectedItem();
            if (id != null) ctx.selectAirport(id);
            update();
        });
        date.addChangeListener(e -> update());
        hour.addChangeListener(e -> { hourLabel.setText(String.format("%02dL", hour.getValue())); update(); });
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
        if (ctx.data == null) {
            stateBanner.setVisible(true);
            stateBanner.setText("  No dataset loaded — open 'Data & Import', import the FAA Excel file "
                    + "(or load the demo dataset), then come back here.  ");
            return;
        }
        stateBanner.setVisible(false);
        for (String id : ctx.data.modelableAirports()) airports.addItem(id);
        syncAirport();
        if (airports.getSelectedItem() == null && airports.getItemCount() > 0) airports.setSelectedIndex(0);
        update();
    }

    private RiskModel model() {
        String id = (String) airports.getSelectedItem();
        return id == null ? null : ctx.models.get(id);
    }

    private void update() {
        RiskModel m = model();
        if (m == null) {
            gauge.setValue(0);
            briefing.setText("Load a dataset first (Data & Import).");
            return;
        }
        java.util.Date d = (java.util.Date) date.getValue();
        LocalDate day = d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        int h = hour.getValue();

        gauge.setValue(m.riskIndex(day.getMonthValue(), h));
        peakLabel.setText(String.format("Hourly peak: %02d:00 local   ·   busiest month: %s   ·   data years: %s",
                m.peakHour(), RiskModel.MONTHS[m.peakMonth()], m.yearRange()));

        String[] rowLabels = new String[7];
        double[][] vals = new double[7][24];
        for (int i = 0; i < 7; i++) {
            LocalDate day7 = day.plusDays(i);
            rowLabels[i] = day7.format(DateTimeFormatter.ofPattern("EEE dd MMM"));
            for (int hh = 0; hh < 24; hh++) vals[i][hh] = m.riskIndex(day7.getMonthValue(), hh);
        }
        week.setValues(vals, rowLabels);

        briefing.setText(buildBriefing(airports.getSelectedItem().toString(), m, day, h));
        briefing.setCaretPosition(0);
    }

    static String buildBriefing(String id, RiskModel m, LocalDate day, int hour) {
        StringBuilder b = new StringBuilder();
        b.append("STRIKESCOPE RISK BRIEFING\n");
        b.append(String.format("AIRPORT : %s      DATE: %s%n", id, day));
        b.append(String.format("RISK    : %d/100 (%s) for %02d:00 local — RELATIVE to this airport's own climatology%n",
                m.riskIndex(day.getMonthValue(), hour),
                Charts.riskLabel(m.riskIndex(day.getMonthValue(), hour)), hour));
        List<Integer> topHours = topHours(m, day.getMonthValue(), 3);
        b.append("PEAK HRS: ").append(fmtHours(topHours)).append(" local\n");
        b.append(String.format("SEASON  : %.2f reported strikes/day avg in %s vs %.2f airport-wide peak (%s)%n",
                m.monthRate(day.getMonthValue()), RiskModel.MONTHS[day.getMonthValue() - 1],
                m.monthRate(m.peakMonth() + 1), RiskModel.MONTHS[m.peakMonth()]));
        b.append("CAVEATS : based on REPORTED strikes (reporting bias); relative risk only;\n");
        b.append("          supports but does not replace the airport wildlife hazard mgt plan.\n");
        return b.toString();
    }

    private static List<Integer> topHours(RiskModel m, int month, int n) {
        List<int[]> list = new ArrayList<>();
        for (int h = 0; h < 24; h++) list.add(new int[] { m.riskIndex(month, h), h });
        list.sort((a, b2) -> b2[0] - a[0]);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n && i < list.size(); i++) out.add(list.get(i)[1]);
        return out;
    }

    private static String fmtHours(List<Integer> hours) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < hours.size(); i++) {
            if (i > 0) s.append(", ");
            s.append(String.format("%02d:00", hours.get(i)));
        }
        return s.toString();
    }

    private void exportBriefing() {
        if (ctx.data == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("strikescope-briefing.txt"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        try {
            Files.writeString(p, briefing.getText(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            javax.swing.JOptionPane.showMessageDialog(this, "Could not write file: " + ex.getMessage());
        }
    }
}

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JEditorPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JLabel;

/**
 * Main window: sidebar navigation + card panels + a demo-data banner.
 */
final class MainFrame extends javax.swing.JFrame {

    private static final String[] NAV = {
            "Dashboard", "Risk Explorer", "Airports", "Airport Profile", "Validation",
            "Data & Import", "About & Ethics"
    };

    private final AppContext ctx = new AppContext();
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JList<String> nav = new JList<>(NAV);
    private final JLabel banner = new JLabel(" ");
    private javax.swing.JLabel statusBar;
    private javax.swing.JProgressBar progressBar;

    private final DashboardPanel dashboard = new DashboardPanel(ctx);
    private final AnalysisPanels.Explorer explorer = new AnalysisPanels.Explorer(ctx);
    private final AirportsPanel airportsPanel = new AirportsPanel(ctx, this::setBusy);
    private final AnalysisPanels.AirportProfile profile = new AnalysisPanels.AirportProfile(ctx);
    private final AnalysisPanels.Validation validation = new AnalysisPanels.Validation(ctx, this::setBusy);
    private final DataPanel dataPanel = new DataPanel(ctx, this::refreshAll, this::setBusy);

    MainFrame() {
        setTitle("StrikeScope — Bird Strike Risk Explorer (FAA National Wildlife Strike Database)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1240, 820);
        setMinimumSize(new Dimension(1024, 700));
        setLocationRelativeTo(null);

        nav.setSelectedIndex(0);
        nav.setFixedCellHeight(44);
        nav.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        nav.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                Component c = super.getListCellRendererComponent(list, value, index, selected, focus);
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
                setFont(getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN, 13.5f));
                return c;
            }
        });
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(190, 0));
        sidebar.add(nav, BorderLayout.NORTH);
        sidebar.add(Box.createVerticalGlue(), BorderLayout.CENTER);
        JLabel brand = new JLabel(
                "<html><div style='padding:10px'><b>STRIKESCOPE</b> v" + AppVersion.VERSION
                + "<br><span style='color:#8aa'>"
                + "bird strike risk explorer<br>student project AY2026/27</span></div></html>");
        sidebar.add(brand, BorderLayout.SOUTH);
        nav.addListSelectionListener(e -> {
            String v = nav.getSelectedValue();
            if (v != null) {
                cards.show(content, v);
                updateStatusText();
            }
        });

        content.add(dashboard, NAV[0]);
        content.add(explorer, NAV[1]);
        content.add(airportsPanel, NAV[2]);
        content.add(profile, NAV[3]);
        content.add(validation, NAV[4]);
        content.add(dataPanel, NAV[5]);
        content.add(aboutPanel(), NAV[6]);

        banner.setOpaque(true);
        banner.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        banner.setBackground(new Color(255, 179, 0));
        banner.setForeground(new Color(40, 30, 0));
        banner.setVisible(false);
        banner.setFont(banner.getFont().deriveFont(Font.BOLD, 12.5f));

        javax.swing.JLabel statusBar = new javax.swing.JLabel("  Current page: Dashboard");
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 14));
        statusBar.setForeground(new java.awt.Color(150, 160, 175));
        this.statusBar = statusBar;

        // updater progress surfaces here; the app updates itself silently otherwise
        UpdateManager.statusSink = msg ->
                javax.swing.SwingUtilities.invokeLater(() -> statusBar.setText("  " + msg));

        javax.swing.JProgressBar progressBar = new javax.swing.JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(160, 14));
        this.progressBar = progressBar;

        JPanel north = new JPanel(new BorderLayout());
        north.add(banner, BorderLayout.NORTH);
        add(north, BorderLayout.NORTH);
        add(sidebar, BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(statusBar, BorderLayout.CENTER);
        south.add(progressBar, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        ctx.onAirportChange = () -> {
            dashboard.syncAirport();
            explorer.syncAirport();
            profile.syncAirport();
            validation.syncAirport();
        };

        // start empty: the Data & Import screen explains both options
        nav.setSelectedIndex(5);
        cards.show(content, NAV[5]);
    }

    private void refreshAll() {
        dashboard.refresh();
        explorer.refresh();
        airportsPanel.refresh();
        profile.refresh();
        validation.refresh();
        dataPanel.refresh();
        updateStatusText();
        boolean demo = ctx.data != null && ctx.data.isDemo();
        banner.setVisible(demo);
        banner.setText("  DEMO DATA — synthetic numbers shaped like real patterns (3 airports: "
                + "KDEN, VIDP, WSSS). Import the official FAA Excel export (Data & Import) for "
                + "real analysis with 2,800+ airports.  ");
    }

    private void updateStatusText() {
        String page = nav.getSelectedValue() == null ? "-" : nav.getSelectedValue();
        statusBar.setText("  Current page: " + page + (ctx.data == null
                ? "   ·   no dataset loaded"
                : "   ·   " + String.format("%,d", ctx.data.size()) + " records loaded"
                        + (ctx.selectedAirport != null ? "   ·   airport: " + ctx.selectedAirport : "")));
    }

    /** Called by long-running tasks (import, backtest): shows the progress bar. */
    private void setBusy(Boolean busy, String message) {
        UpdateManager.appBusy = busy;
        progressBar.setVisible(busy);
        if (busy) statusBar.setText("  " + message);
        else updateStatusText();
    }

    private JScrollPane aboutPanel() {
        JEditorPane ep = new JEditorPane("text/html",
                "<html><body style='width:860px; padding:14px; line-height:1.45'>"
                + "<p style='color:#7a8a99'><b>Version " + AppVersion.VERSION + "</b> — this app "
                + "checks GitHub Releases in the background and updates itself silently; "
                + "progress appears on the status bar when an update lands.</p>"
                + "<h2>What this tool does</h2>"
                + "<p>StrikeScope turns an airport's own strike history into a <b>relative risk "
                + "index by month and hour of day</b>, so a fixed-size wildlife-control team can be "
                + "pre-positioned in the windows where strikes historically cluster. It does not "
                + "detect birds and it does not promise zero strikes — it answers <i>when</i>, not "
                + "<i>whether</i>.</p>"
                + "<p>Every heatmap in the app (the 7-day outlook, the month×hour explorer and the "
                + "cross-airport <b>Airports</b> screen) is coloured by that index; the Airports "
                + "screen shows one row per airport so peak months can be compared across the "
                + "whole database at once.</p>"
                + "<h2>Model (deliberately simple and explainable)</h2>"
                + "<p><code>risk(month, hour) = mean reported strikes per day in that month × "
                + "hour-of-day activity weight</code>, normalised to the airport's own peak (0–100). "
                + "Hour weights blend two sources: strikes with a recorded clock time, and the "
                + "Dawn/Day/Dusk/Night bucket of the remaining reports. No black box: every number "
                + "can be traced back to a count.</p>"
                + "<h2>Honest limitations (they matter for the engineering report)</h2>"
                + "<ul>"
                + "<li><b>Reporting bias.</b> The database counts REPORTED strikes. Reporting "
                + "improved massively since 1990, and airports with active wildlife programmes "
                + "report more. Cross-airport comparisons mostly measure reporting effort.</li>"
                + "<li><b>Relative, not absolute.</b> 80/100 does not mean '80% chance of a strike'.</li>"
                + "<li><b>Traffic exposure.</b> Busier years have more strikes; a serious version "
                + "normalises per aircraft movement (available from FAA/BTS).</li>"
                + "<li><b>Weather not modelled yet.</b> The index is climatology; the next step is "
                + "conditioning on wind, visibility and season from METAR archives.</li>"
                + "</ul>"
                + "<h2>Data & sources</h2>"
                + "<p>FAA National Wildlife Strike Database — wildlife.faa.gov (US public domain). "
                + "Species names in the demo dataset are plausible for each airport but the demo "
                + "numbers are synthetic. Verification of all facts and figures was done against "
                + "the FAA's own annual report series.</p>"
                + "<h2>Where this could go next</h2>"
                + "<p>Weather-conditioned risk (METAR archive), damage-severity modelling by "
                + "species mass, per-runway risk from ADS-B approach tracks, and a patrol-schedule "
                + "optimiser that converts the risk grid into staff rosters.</p>"
                + "</body></html>");
        ep.setEditable(false);
        ep.setBorder(null);
        return new JScrollPane(ep);
    }
}

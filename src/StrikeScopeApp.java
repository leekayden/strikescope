import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * StrikeScope - Bird Strike Risk Explorer for aviation students.
 *
 * Built on the FAA National Wildlife Strike Database (wildlife.faa.gov).
 * Student project, AY2026/27.
 */
public final class StrikeScopeApp {

    public static void main(String[] args) {
        // Headless self-test: java StrikeScopeApp --selftest
        for (String a : args) {
            if ("--selftest".equals(a)) {
                selfTest();
                return;
            }
        }

        // A staged update from a previous session? Swap it in and relaunch
        // silently before any UI appears.
        if (UpdateManager.applyPendingBeforeUi()) {
            return;
        }

        UIManager.put("defaultFont", new javax.swing.plaf.FontUIResource("Segoe UI", 0, 13));
        com.formdev.flatlaf.FlatDarkLaf.setup();
        UIManager.put("TitlePane.unifiedBackground", Boolean.TRUE);

        // Any uncaught exception (incl. on the Event Dispatch Thread) lands in
        // strikescope-error.log next to the jar instead of vanishing silently.
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of("strikescope-error.log"),
                        java.time.LocalDateTime.now() + " [" + t.getName() + "]\n" + sw + "\n\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignore) { }
        });

        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));

        // Silent self-update: checks GitHub Releases now and every 6 h.
        UpdateManager.backgroundCheck();
    }

    /** Non-GUI sanity check: data -> model -> validation pipeline. */
    private static void selfTest() {
        System.out.println("StrikeScope self-test");
        StrikeData data = DemoData.generate();
        System.out.println("demo rows: " + data.size() + ", airports: " + data.airportIds());
        RiskModel.Validation v = RiskModel.validate(data.recordsFor("KDEN"), 2018);
        System.out.printf("validation: top10%%=%.1f%% top20%%=%.1f%% r=%.3f%n",
                v.capture10() * 100, v.capture20() * 100, v.correlation());
        System.out.println("SELFTEST OK");
    }
}

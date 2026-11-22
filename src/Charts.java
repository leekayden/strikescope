import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;

/**
 * Hand-painted chart widgets (no chart library) so the whole app runs with a
 * single dependency: FlatLaf. All widgets derive their colors from the current
 * look-and-feel and use one shared risk color scale.
 */
public final class Charts {

    private Charts() {}

    public static final Color RISK_LOW = new Color(67, 160, 71);
    public static final Color RISK_MID = new Color(255, 179, 0);
    public static final Color RISK_HIGH = new Color(229, 57, 53);

    /** green -> amber -> red, t in [0,1] */
    public static Color riskColor(double t) {
        t = Math.max(0, Math.min(1, t));
        return t < 0.5 ? blend(RISK_LOW, RISK_MID, t * 2)
                       : blend(RISK_MID, RISK_HIGH, (t - 0.5) * 2);
    }

    public static String riskLabel(int score) {
        if (score < 25) return "LOW";
        if (score < 50) return "MODERATE";
        if (score < 75) return "HIGH";
        return "SEVERE";
    }

    public static Color riskTextColor(int score) {
        return switch (riskLabel(score)) {
            case "LOW" -> new Color(129, 199, 132);
            case "MODERATE" -> new Color(255, 213, 79);
            case "HIGH" -> new Color(239, 108, 108);
            default -> new Color(255, 138, 128);
        };
    }

    private static Color blend(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return new Color(r, g, bl);
    }

    private static Color fg() { return UIManager2.fg(); }
    private static Color sub() { return UIManager2.sub(); }
    private static Color grid() { return UIManager2.grid(); }

    private static final class UIManager2 {
        static Color fg() {
            Color c = javax.swing.UIManager.getColor("Panel.foreground");
            return c != null ? c : Color.WHITE;
        }
        static Color sub() { return blend(fg(), javax.swing.UIManager.getColor("Panel.background"), 0.45); }
        static Color grid() { return blend(fg(), javax.swing.UIManager.getColor("Panel.background"), 0.8); }
    }

    // ------------------------------------------------------------------ gauge

    /** 260-degree arc gauge, 0-100, with needle and category label. */
    public static final class Gauge extends JPanel {
        private int value;

        public Gauge() {
            setOpaque(false);
            setPreferredSize(new Dimension(260, 190));
        }

        public void setValue(int v) { value = Math.max(0, Math.min(100, v)); repaint(); }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int r = Math.min(w / 2, h) - 28;
            int cx = w / 2, cy = h - 24;
            double start = -220, sweep = 260;

            // background track and colored segments
            g.setStroke(new BasicStroke(14, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL));
            java.awt.geom.Arc2D.Double seg = new java.awt.geom.Arc2D.Double();
            for (int i = 0; i < 50; i++) {
                seg.setFrame(cx - r, cy - r, 2.0 * r, 2.0 * r);
                seg.setAngleStart(220 - 2.6 * (i + 1));    // gauge runs clockwise from lower-left
                seg.setAngleExtent(2.6);
                seg.setArcType(java.awt.geom.Arc2D.OPEN);
                g.setColor(riskColor((i + 0.5) / 50.0));
                g.draw(seg);
            }

            // needle
            double ang = Math.toRadians(220 - 2.6 * value);
            int nx = cx + (int) Math.round((r - 24) * Math.cos(ang));
            int ny = cy - (int) Math.round((r - 24) * Math.sin(ang));
            g.setColor(fg());
            g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(cx, cy, nx, ny);
            g.fillOval(cx - 5, cy - 5, 10, 10);

            g.setFont(getFont().deriveFont(Font.BOLD, 30f));
            g.setColor(riskTextColor(value));
            String s = String.valueOf(value);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(s, cx - fm.stringWidth(s) / 2, cy - 34);
            g.setFont(getFont().deriveFont(Font.BOLD, 13f));
            g.setColor(sub());
            String cat = riskLabel(value) + " RISK";
            fm = g.getFontMetrics();
            g.drawString(cat, cx - fm.stringWidth(cat) / 2, cy - 14);
        }
    }

    // ---------------------------------------------------------------- heatmap

    /** Rows x cols heat grid with labels and hover tooltips. */
    public static final class Heatmap extends JPanel {

        /** How cell values map onto the colour scale. */
        public enum Norm { GRID, ROW, NONE }

        private String[] rowLabels;
        private final String[] colLabels;
        private double[][] values;
        private final String rowName, colName;
        private int hoverRow = -1, hoverCol = -1;
        private Norm normMode = Norm.GRID;
        private int lblW = 44;

        public Heatmap(double[][] values, String[] rowLabels, String[] colLabels,
                       String rowName, String colName) {
            this.rowLabels = rowLabels;
            this.colLabels = colLabels;
            this.rowName = rowName;
            this.colName = colName;
            setValues(values);
            setOpaque(false);
            setToolTipText(" ");   // registers the component with ToolTipManager
            addMouseMotionListener(new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    hoverRow = rowAt(e.getY());
                    hoverCol = colAt(e.getX());
                    repaint();
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override public void mouseExited(MouseEvent e) {
                    hoverRow = hoverCol = -1; repaint();
                }
            });
        }

        public void setValues(double[][] v) {
            if (v == null) v = new double[0][0];
            this.values = v;
            norm = new double[v.length][];
            switch (normMode) {
                case GRID -> {
                    double max = 0;
                    for (double[] row : v) for (double x : row) max = Math.max(max, x);
                    fill(v, norm, max);
                }
                case ROW -> {
                    for (int i = 0; i < v.length; i++) {
                        double max = 0;
                        for (double x : v[i]) max = Math.max(max, x);
                        norm[i] = new double[v[i].length];
                        fill(new double[][] { v[i] }, new double[][] { norm[i] }, max);
                    }
                }
                case NONE -> fill(v, norm, 100.0);
            }
            revalidate();
            repaint();
        }

        private static void fill(double[][] v, double[][] n, double max) {
            for (int i = 0; i < v.length; i++) {
                n[i] = new double[v[i].length];
                for (int j = 0; j < v[i].length; j++)
                    n[i][j] = max > 0 ? v[i][j] / max : 0;
            }
        }

        /** Updates values AND row labels together (labels must match v.length). */
        public void setValues(double[][] v, String[] rowLabels) {
            if (rowLabels != null && rowLabels.length == v.length) this.rowLabels = rowLabels;
            setValues(v);
        }

        /** GRID (default): colour relative to the whole grid's peak. NONE: absolute 0-100. */
        public void setNormMode(Norm m) { normMode = m == null ? Norm.GRID : m; setValues(values); }

        /** Pixel width reserved for row labels (widen for airport IDs). */
        public void setRowLabelWidth(int px) { this.lblW = px; }

        private double[][] norm;

        private static final int TOP_H = 22, PAD = 6;

        private int rowAt(int y) {
            int rh = (getHeight() - TOP_H - 4 - PAD) / Math.max(1, rowLabels.length);
            int r = (y - TOP_H) / Math.max(1, rh);
            return (r >= 0 && r < rowLabels.length) ? r : -1;
        }

        private int colAt(int x) {
            int cw = (getWidth() - lblW - PAD) / Math.max(1, colLabels.length);
            int c = (x - lblW) / Math.max(1, cw);
            return (c >= 0 && c < colLabels.length) ? c : -1;
        }

        @Override public String getToolTipText(MouseEvent e) {
            int r = rowAt(e.getY()), c = colAt(e.getX());
            if (r < 0 || c < 0 || values == null) return null;
            return rowLabels[r] + " " + colLabels[c] + " — index "
                    + (int) Math.round(values[r][c]) + "/100";
        }

        private int rowHeightHint = 22;

        /** Preferred pixel height of one heatmap row. */
        public void setRowHeightHint(int px) { this.rowHeightHint = px; }

        @Override public Dimension getPreferredSize() {
            int rh = Math.max(14, rowHeightHint);
            return new Dimension(720, TOP_H + 20 + rowLabels.length * rh);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int rows = rowLabels.length, cols = colLabels.length;
            int rh = (h - TOP_H - 4 - PAD) / Math.max(1, rows);
            int cw = (w - lblW - PAD) / Math.max(1, cols);

            g.setFont(getFont().deriveFont(10.5f));
            g.setColor(sub());
            for (int c = 0; c < cols; c += Math.max(1, cols / 12))
                if (colLabels[c] != null) g.drawString(colLabels[c], lblW + c * cw, TOP_H - 8);

            for (int r = 0; r < rows; r++) {
                if (rowLabels[r] != null) {
                    g.setColor(sub());
                    g.drawString(rowLabels[r], 4, TOP_H + r * rh + rh / 2 + 4);
                }
                for (int c = 0; c < cols; c++) {
                    double t = norm[r][c];
                    g.setColor(riskColor(t));
                    if (r == hoverRow && c == hoverCol) {
                        g.setColor(blend(riskColor(t), Color.WHITE, 0.35));
                    }
                    g.fillRect(lblW + c * cw, TOP_H + r * rh, Math.max(1, cw - 2),
                            Math.max(1, rh - 2));
                }
            }

            // legend
            int ly = h - 12;
            for (int x = 0; x <= 100; x += 2) {
                g.setColor(riskColor(x / 100.0));
                g.fillRect(lblW + x * (w - lblW - PAD) / 200, ly - 4, 2, 4);
            }
            g.setColor(sub());
            g.drawString("low", lblW, ly + 1);
            g.drawString(normMode == Norm.NONE
                            ? "high (0–100 index, relative to " + rowName + " peak)"
                            : "high (relative to " + rowName + " peak)",
                    lblW + 30, ly + 1);
        }
    }

    // -------------------------------------------------------------- bar chart

    /** Horizontal bars with labels and values. */
    public static final class HBars extends JPanel {
        private List<Map.Entry<String, Integer>> items = List.of();

        public HBars() { setOpaque(false); }

        public void setItems(List<Map.Entry<String, Integer>> items) {
            this.items = items;
            revalidate();
            repaint();
        }

        @Override public Dimension getPreferredSize() {
            return new Dimension(340, 26 * Math.max(1, items.size()) + 14);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (items.isEmpty()) return;
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int max = 1;
            for (var e : items) max = Math.max(max, e.getValue());
            int rowH = getHeight() / items.size();

            g.setFont(getFont().deriveFont(11.5f));
            for (int i = 0; i < items.size(); i++) {
                var e = items.get(i);
                int y = i * rowH;
                g.setColor(sub());
                g.drawString(ellipsize(e.getKey(), (int) (w * 0.55)), 2, y + rowH / 2 + 4);
                int barX = (int) (w * 0.58), barW = (int) ((w * 0.92 - barX)
                        * e.getValue() / (double) max);
                g.setColor(blend(RISK_MID, fg(), 0.25));
                g.fillRoundRect(barX, y + rowH / 2 - 6, Math.max(2, barW), 12, 6, 6);
                String v = String.valueOf(e.getValue());
                g.setColor(fg());
                g.drawString(v, Math.min(w - 24, barX + barW + 4), y + rowH / 2 + 4);
            }
        }

        private String ellipsize(String s, int px) {
            FontMetrics fm = getFontMetrics(getFont());
            if (fm.stringWidth(s) <= px) return s;
            while (s.length() > 3 && fm.stringWidth(s + "…") > px) s = s.substring(0, s.length() - 1);
            return s + "…";
        }
    }

    // ----------------------------------------------------------------- vbars

    /** Vertical bars (months, hours) with sparse tick labels and hover tooltips. */
    public static final class VBars extends JPanel {
        private double[] values = new double[0];
        private String[] labels = new String[0];
        private String[] tips = new String[0];
        private int hover = -1;

        public VBars() {
            setOpaque(false);
            setToolTipText(" ");
            addMouseMotionListener(new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int n = Math.max(1, values.length);
                    int bw = (getWidth() - 10) / n;
                    hover = bw > 0 ? Math.min(n - 1, Math.max(-1, (e.getX() - 5) / bw)) : -1;
                    repaint();
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
            });
        }

        /** labels: x-axis ticks (drawn sparsely); tips: one tooltip per bar. */
        public void setValues(double[] v, String[] labels, String[] tips) {
            this.values = v.clone();
            this.labels = labels.clone();
            this.tips = tips.clone();
            revalidate();
            repaint();
        }

        @Override public String getToolTipText(java.awt.event.MouseEvent e) {
            int n = Math.max(1, values.length);
            int bw = (getWidth() - 10) / n;
            int i = bw > 0 ? (e.getX() - 5) / bw : -1;
            return (i >= 0 && i < tips.length && tips[i] != null) ? tips[i] : null;
        }

        @Override public Dimension getPreferredSize() {
            return new Dimension(320, 120);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            int n = values.length;
            if (n == 0) return;
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            double max = 0;
            for (double v : values) max = Math.max(max, v);
            int base = h - 18;
            int bw = (w - 10) / n;

            g.setFont(getFont().deriveFont(10f));
            int tickEvery = Math.max(1, n / 12);
            for (int i = 0; i < n; i++) {
                int bh = max > 0 ? (int) ((base - 6) * values[i] / max) : 0;
                int x = 5 + i * bw;
                g.setColor(i == hover ? blend(riskColor(0.6), Color.WHITE, 0.3)
                                      : blend(RISK_MID, fg(), 0.25));
                g.fillRoundRect(x, base - bh, Math.max(1, bw - 2), Math.max(1, bh), 4, 4);
                g.setColor(sub());
                if (labels[i] != null && i % tickEvery == 0)
                    g.drawString(labels[i], x, h - 4);
            }
            g.setColor(grid());
            g.drawLine(4, base, w - 4, base);
        }
    }

    // ------------------------------------------------------------- line chart

    /** Simple labelled line chart for capture curves and yearly trends. */
    public static final class Line extends JPanel {
        private double[][] series;   // list of {x, y} in [0,1]
        private String xLabel = "", yLabel = "";
        private double yMaxOverride = 1.0;

        public Line() { setOpaque(false); setPreferredSize(new Dimension(560, 220)); }

        public void setSeries(List<double[]> pts, String xLabel, String yLabel) {
            this.series = pts.toArray(new double[0][]);
            this.xLabel = xLabel;
            this.yLabel = yLabel;
            this.yMaxOverride = 1.0;
            repaint();
        }

        public void setYearly(Map<Integer, Integer> perYear, String yLabel) {
            if (perYear.isEmpty()) { series = new double[0][]; repaint(); return; }
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, vMax = 1;
            for (var e : perYear.entrySet()) {
                min = Math.min(min, e.getKey()); max = Math.max(max, e.getKey());
                vMax = Math.max(vMax, e.getValue());
            }
            double[][] pts = new double[perYear.size()][];
            int i = 0;
            for (var e : perYear.entrySet()) {
                pts[i++] = new double[] {
                        (e.getKey() - min) / (double) Math.max(1, max - min),
                        e.getValue() / (double) vMax };
            }
            series = pts;
            xLabel = String.valueOf(min) + "–" + max;
            this.yLabel = yLabel;
            yMaxOverride = vMax;
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int l = 46, r = 12, t = 14, b = 30;
            int cw = w - l - r, ch = h - t - b;

            g.setColor(grid());
            g.setStroke(new BasicStroke(1));
            for (int i = 0; i <= 4; i++) {
                int y = t + ch * i / 4;
                g.drawLine(l, y, w - r, y);
            }
            // diagonal reference on capture curves
            if ("strikes captured".equals(yLabel)) {
                g.setColor(sub());
                g.setStroke(new BasicStroke(1, 0, 0, 0, new float[] { 4, 4 }, 0));
                g.drawLine(l, t + ch, l + cw, t);
            }

            g.setColor(sub());
            g.drawString(xLabel, l, h - 10);
            g.drawString(yMaxOverride == 1.0 ? "0" : String.valueOf(yMaxOverride), 4, t + 10);
            g.drawString(yMaxOverride == 1.0 ? "1" : "0", 4, t + ch);

            if (series == null || series.length == 0) {
                g.setColor(sub());
                g.drawString("no data", l + cw / 2 - 24, t + ch / 2);
                return;
            }
            g.setColor(blend(RISK_MID, fg(), 0.15));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int px = 0, py = 0;
            for (int i = 0; i < series.length; i++) {
                int x = l + (int) (series[i][0] * cw);
                int y = t + ch - (int) (series[i][1] * ch);
                if (i > 0) g.drawLine(px, py, x, y);
                px = x; py = y;
            }
        }
    }

    // ------------------------------------------------------------------ donut

    public static final class Donut extends JPanel {
        private final String title;
        private final String[] labels;
        private int[] parts = new int[0];
        private final Color[] palette = {
                new Color(67, 160, 71), new Color(255, 179, 0),
                new Color(229, 57, 53), new Color(121, 134, 203) };

        public Donut(String title, String[] labels) {
            this.title = title;
            this.labels = labels.clone();
            setOpaque(false);
        }

        public void setValues(int[] v) { parts = v; repaint(); }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int size = Math.min(h, w) - 30;
            int cx = w / 2 - 40, cy = h / 2;
            int total = 0;
            for (int p : parts) total += p;

            g.setColor(fg());
            g.setFont(getFont().deriveFont(Font.BOLD, 12f));
            g.drawString(title, 8, 16);

            if (total == 0) {
                g.setColor(sub());
                g.drawString("no data", cx - 20, cy);
                return;
            }
            double ang = 90;
            int rOut = size / 2, rIn = (int) (rOut * 0.58);
            for (int i = 0; i < parts.length; i++) {
                double sweep = -360.0 * parts[i] / total;
                g.setColor(palette[i % palette.length]);
                g.fill(new java.awt.geom.Arc2D.Double(cx - rOut, cy - rOut, size, size,
                        ang + sweep, -sweep, java.awt.geom.Arc2D.PIE));
                g.setColor(javax.swing.UIManager.getColor("Panel.background"));
                g.fill(new java.awt.geom.Ellipse2D.Double(cx - rIn, cy - rIn, 2 * rIn, 2 * rIn));
                ang += sweep;
            }
            // legend
            g.setFont(getFont().deriveFont(11.5f));
            int ly = cy - parts.length * 10 + 4;
            for (int i = 0; i < parts.length; i++) {
                g.setColor(palette[i % palette.length]);
                g.fillRect(cx + rOut + 14, ly + i * 20 - 8, 10, 10);
                g.setColor(fg());
                String l = i < labels.length ? labels[i] : "part " + (i + 1);
                g.drawString(l + "  " + parts[i], cx + rOut + 30, ly + i * 20);
            }
        }
    }
}

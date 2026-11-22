import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * Streaming reader for .xlsx worksheets with zero external dependencies
 * (JDK zip + StAX only). Rows are pushed to a callback one at a time, so a
 * 1.4 GB sheet (the full FAA database export) never needs to be held in
 * memory.
 *
 * Supports: shared strings, inline strings, r="C12" cell addressing (so
 * missing/empty cells keep their column position), boolean cells.
 */
final class FaaXlsxReader {

    static void read(Path xlsx, FaaCsvParser.RowConsumer sink) {
        try (ZipFile zip = new ZipFile(xlsx.toFile())) {
            List<String> shared = readSharedStrings(zip);

            ZipEntry sheet = zip.getEntry("xl/worksheets/sheet1.xml");
            if (sheet == null) {
                for (ZipEntry e : Collections.list(zip.entries())) {
                    if (e.getName().matches("xl/worksheets/sheet\\d+\\.xml")) { sheet = e; break; }
                }
            }
            if (sheet == null) {
                throw new IllegalArgumentException("No worksheet found in the workbook.");
            }

            XMLInputFactory f = XMLInputFactory.newInstance();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_COALESCING, true);

            try (InputStream in = zip.getInputStream(sheet)) {
                XMLStreamReader r = f.createXMLStreamReader(in);
                List<String> row = null;
                StringBuilder sb = new StringBuilder();
                int colIdx = -1;
                char cellType = 'n';
                String cur = null;

                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        String name = r.getLocalName();
                        if (name.equals("row")) {
                            row = new ArrayList<>();
                        } else if (name.equals("c") && row != null) {
                            colIdx = 0;
                            cellType = 'n';
                            sb.setLength(0);
                            for (int i = 0; i < r.getAttributeCount(); i++) {
                                String an = r.getAttributeLocalName(i);
                                if (an.equals("r")) colIdx = lettersToIndex(r.getAttributeValue(i));
                                else if (an.equals("t")) cellType = r.getAttributeValue(i).charAt(0);
                            }
                        }
                        cur = name;
                    } else if (ev == XMLStreamConstants.CHARACTERS) {
                        // text of <v> (shared/numeric) or <t> (inline strings)
                        if (row != null && cur != null && (cur.equals("v") || cur.equals("t"))) {
                            sb.append(r.getText());
                        }
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        String name = r.getLocalName();
                        if (name.equals("v") || name.equals("t") || name.equals("is")) {
                            cur = "c";  // still inside the cell, but no longer in a value node
                        } else if (name.equals("c") && row != null) {
                            String val;
                            try {
                                val = switch (cellType) {
                                    case 's' -> shared.get(Integer.parseInt(sb.toString().trim()));
                                    case 'b' -> sb.toString().trim().equals("1") ? "Yes" : "No";
                                    default -> sb.toString();
                                };
                            } catch (RuntimeException badIndex) {
                                val = sb.toString();
                            }
                            if (val == null) val = "";
                            while (row.size() <= colIdx) row.add("");
                            row.set(colIdx, val);
                            sb.setLength(0);
                        } else if (name.equals("row") && row != null) {
                            sink.row(row.toArray(new String[0]));
                            row = null;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not read Excel file: " + e.getMessage(), e);
        }
    }

    /** All shared strings, in order. 608k entries for the FAA export. */
    private static List<String> readSharedStrings(ZipFile zip) throws Exception {
        ZipEntry e = zip.getEntry("xl/sharedStrings.xml");
        if (e == null) return List.of();
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        List<String> out = new ArrayList<>(700_000);
        try (InputStream in = zip.getInputStream(e)) {
            XMLStreamReader r = f.createXMLStreamReader(in);
            StringBuilder sb = new StringBuilder();
            boolean inSi = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    if (r.getLocalName().equals("si")) { inSi = true; sb.setLength(0); }
                } else if (ev == XMLStreamConstants.CHARACTERS && inSi) {
                    sb.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("si")) {
                    out.add(sb.toString());
                    inSi = false;
                }
            }
        }
        return out;
    }

    /** "CX12" -> 75 (0-based column index of CX). */
    private static int lettersToIndex(String ref) {
        int idx = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') idx = idx * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') idx = idx * 26 + (c - 'a' + 1);
            else break;
        }
        return Math.max(0, idx - 1);   // bijective base-26: A=1 -> index 0
    }
}

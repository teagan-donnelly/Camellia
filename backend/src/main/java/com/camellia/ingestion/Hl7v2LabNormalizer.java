package com.camellia.ingestion;

import com.camellia.model.LabResult;
import com.camellia.model.RecordType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Minimal HL7 v2.5.1 ORU^R01 parser for the simulated Northgate laboratory feed (MSH, PID, OBR, OBX, NTE).
 * Each OBX becomes a LAB_RESULTS row plus a CLINICAL_RECORDS row; the raw message is preserved.
 */
public class Hl7v2LabNormalizer {

    public static final String FORMAT = "HL7V2_ORU";

    public NormalizedBatch normalize(String feed, String orgId, SourceContext ctx) {
        NormalizedBatch out = new NormalizedBatch();
        for (String message : splitMessages(feed)) {
            parseMessage(message, orgId, ctx, out);
        }
        return out;
    }

    static List<String> splitMessages(String feed) {
        List<String> messages = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : feed.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (line.startsWith("MSH|") && !cur.isEmpty()) {
                messages.add(cur.toString().trim());
                cur.setLength(0);
            }
            if (!line.isBlank()) cur.append(line).append('\n');
        }
        if (!cur.isEmpty()) messages.add(cur.toString().trim());
        return messages;
    }

    private void parseMessage(String message, String orgId, SourceContext ctx, NormalizedBatch out) {
        String pid = null;
        String accession = null;
        String orderingProvider = null;
        String panelName = null;
        PendingObx pending = null;

        for (String seg : message.split("\n")) {
            String[] f = seg.split("\\|", -1);
            switch (f[0]) {
                case "PID" -> {
                    String mrn = comp(field(f, 3), 0);
                    pid = ctx.patientFor(orgId, mrn);
                    if (pid == null) {
                        out.warnings.add("Unmatched HL7 patient identity " + orgId + "/" + mrn + " — message skipped");
                        return;
                    }
                }
                case "OBR" -> {
                    flush(pending, pid, orgId, ctx, out, message);
                    pending = null;
                    accession = field(f, 3);
                    panelName = comp(field(f, 4), 1);
                    String xcn = field(f, 16);
                    orderingProvider = xcn.isEmpty() ? null
                            : ("Dr. " + titleCase(comp(xcn, 2)) + " " + titleCase(comp(xcn, 1))).trim();
                }
                case "OBX" -> {
                    flush(pending, pid, orgId, ctx, out, message);
                    pending = new PendingObx(f, accession, panelName, orderingProvider);
                }
                case "NTE" -> {
                    if (pending != null) pending.notes.add(field(f, 3));
                }
                default -> { /* MSH/ORC and others carry no clinical values here */ }
            }
        }
        flush(pending, pid, orgId, ctx, out, message);
    }

    private void flush(PendingObx obx, String pid, String orgId, SourceContext ctx, NormalizedBatch out, String rawMessage) {
        if (obx == null || pid == null) return;
        String[] f = obx.fields;
        String setId = field(f, 1);
        String code = comp(field(f, 3), 0);
        String name = comp(field(f, 3), 1);
        String rawValue = field(f, 5);
        Double value = Json.parseDouble(rawValue);
        String unit = field(f, 6);
        String range = field(f, 7);
        Double low = null, high = null;
        if (range.matches("\\s*[0-9.]+\\s*-\\s*[0-9.]+\\s*")) {
            String[] parts = range.split("-");
            low = Double.valueOf(parts[0].trim());
            high = Double.valueOf(parts[1].trim());
        }
        String flag = field(f, 8);
        LocalDateTime when = ctx.dateTime(field(f, 14));
        String sourceId = obx.accession + "-" + setId;
        String recId = SourceContext.id("REC", orgId, sourceId);
        LabResult lab = new LabResult(SourceContext.id("LAB", orgId, sourceId), recId, pid, orgId, code, name, value,
                value == null ? rawValue : null, unit, low, high, range.isBlank() ? null : range,
                flag.isBlank() ? null : flag, when);
        out.labs.add(lab);
        String content = LabText.describe(lab)
                + (obx.panel == null ? "" : " Panel: " + obx.panel + ".")
                + (obx.notes.isEmpty() ? "" : " Lab comment: " + String.join(" ", obx.notes))
                + " Accession " + obx.accession + ".";
        Set<String> tags = LabText.tags(lab);
        out.records.add(ctx.record(recId, pid, orgId, null, RecordType.LAB_RESULT, name, when,
                obx.orderingProvider == null ? "Northgate Diagnostic Laboratories" : "Ordered by " + obx.orderingProvider,
                "Laboratory", content, FORMAT, sourceId, rawMessage, tags));
    }

    private static final class PendingObx {
        final String[] fields;
        final String accession;
        final String panel;
        final String orderingProvider;
        final List<String> notes = new ArrayList<>();

        PendingObx(String[] fields, String accession, String panel, String orderingProvider) {
            this.fields = fields;
            this.accession = accession;
            this.panel = panel;
            this.orderingProvider = orderingProvider;
        }
    }

    static String field(String[] f, int i) {
        return i < f.length ? f[i] : "";
    }

    static String comp(String field, int i) {
        String[] c = field.split("\\^", -1);
        return i < c.length ? c[i] : "";
    }

    static String titleCase(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.charAt(0) + s.substring(1).toLowerCase();
    }
}

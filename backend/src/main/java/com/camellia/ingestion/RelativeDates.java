package com.camellia.ingestion;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synthetic source files express dates relative to a demo anchor date ("@D-42", "@D+12T07:00", "@D-43T0815")
 * so that "upcoming encounter" scenarios still work whenever the demo is run. {@link #render} resolves the
 * tokens in a whole source document before parsing, so stored raw payloads contain real dates.
 */
public final class RelativeDates {

    private static final Pattern TOKEN = Pattern.compile("@D([+-]\\d+)(?:T(\\d{2}):?(\\d{2}))?");
    private static final DateTimeFormatter HL7_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter ISO_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private RelativeDates() {}

    /** Replaces every relative token. HL7 sources get HL7 timestamps, everything else ISO-8601. */
    public static String render(String source, LocalDate anchor, boolean hl7) {
        Matcher m = TOKEN.matcher(source);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            LocalDate d = anchor.plusDays(Long.parseLong(m.group(1)));
            String replacement;
            if (m.group(2) == null) {
                replacement = hl7 ? d.format(DateTimeFormatter.BASIC_ISO_DATE) : d.toString();
            } else {
                LocalDateTime dt = LocalDateTime.of(d, LocalTime.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
                replacement = hl7 ? dt.format(HL7_TS) : dt.format(ISO_MINUTES);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Parses relative tokens, ISO dates/date-times, and HL7 TS values (yyyyMMdd[HHmm[ss]]). */
    public static LocalDateTime dateTime(String value, LocalDate anchor) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        Matcher m = TOKEN.matcher(v);
        if (m.matches()) {
            return LocalDateTime.of(LocalDate.parse(render(v.replaceFirst("T.*", ""), anchor, false)),
                    m.group(2) == null ? LocalTime.of(9, 0)
                            : LocalTime.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
        }
        if (v.matches("\\d{8}")) return LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay();
        if (v.matches("\\d{12}(\\d{2})?")) return LocalDateTime.parse(v.substring(0, 12), HL7_TS);
        if (v.length() == 10) return LocalDate.parse(v).atStartOfDay();
        // ISO-8601 with an offset or Z (real FHIR exports): stored as UTC like every other timestamp.
        if (v.matches(".*T.*(Z|[+-]\\d{2}:?\\d{2})$")) {
            return java.time.OffsetDateTime.parse(v.replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2"))
                    .withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        }
        return LocalDateTime.parse(v.length() > 19 ? v.substring(0, 19) : v);
    }

    public static LocalDate date(String value, LocalDate anchor) {
        LocalDateTime dt = dateTime(value, anchor);
        return dt == null ? null : dt.toLocalDate();
    }
}

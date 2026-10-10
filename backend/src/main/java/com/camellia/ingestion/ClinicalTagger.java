package com.camellia.ingestion;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Deterministic, explainable concept tagging applied at ingestion. Tags power concept search
 * ("show every fracture") and rule evaluation; they are keyword-based and intentionally simple.
 */
public final class ClinicalTagger {

    /** Concept tag -> terms. A term matches at the start of a word (so "pregnan" matches "pregnancy"). */
    public static final Map<String, List<String>> CONCEPT_TERMS;
    /** Symptom categories, applied to encounter reasons / chief complaints only. */
    public static final Map<String, List<String>> SYMPTOM_TERMS;

    static {
        Map<String, List<String>> c = new LinkedHashMap<>();
        c.put("FRACTURE", List.of("fracture"));
        c.put("PLATELET", List.of("platelet", "thrombocytopenia"));
        c.put("LOW_PLATELETS", List.of("thrombocytopenia", "low platelet"));
        c.put("HEMATOLOGY", List.of("thrombocytopenia", "platelet", "hematology", "anemia", "hemoglobin", "coagul",
                "bleeding disorder", "von willebrand", "enoxaparin", "heparin", "deep vein thrombosis", "dvt", "anticoag"));
        c.put("ANESTHESIA", List.of("anesthesi", "epidural", "neuraxial", "intubation", "airway", "sedation"));
        c.put("OBSTETRIC", List.of("pregnan", "obstetric", "gestation", "prenatal", "cesarean", "induction of labor",
                "labor ", "labor & delivery", "postpartum", "maternal-fetal", "mfm"));
        c.put("DELIVERY", List.of("cesarean", "induction of labor", "labor & delivery", "labor and delivery", "delivery admission"));
        c.put("CARDIAC", List.of("cardio", "cardiac", "ejection fraction", "lvef", "echocardiogram", "heart", "nt-probnp"));
        c.put("ONCOLOGY", List.of("lymphoma", "oncology", "chemotherapy", "cancer", "malignan", "mediastinal radiation",
                "abvd", "doxorubicin"));
        c.put("CALCIUM", List.of("calcium", "pth", "parathyroid"));
        c.put("ENDOCRINE", List.of("diabetes", "a1c", "thyroid", "tsh", "levothyroxine", "metformin"));
        c.put("RENAL", List.of("kidney", "renal", "creatinine", "egfr", "ureteral", "nephrolith", "hydronephrosis"));
        c.put("NEUROLOGY", List.of("headache", "migraine", "neurolog"));
        c.put("BONE_HEALTH", List.of("osteopenia", "osteoporosis", "bone density", "dxa", "vitamin d", "hydroxyvitamin d"));
        c.put("RESPIRATORY", List.of("copd", "asthma", "inhal", "spirometer", "sleep apnea"));
        c.put("EMERGENCY", List.of("emergency department", "emergency medicine"));
        c.put("SPINE", List.of("spinal fusion", "scoliosis", "spine"));
        CONCEPT_TERMS = Collections.unmodifiableMap(c);

        Map<String, List<String>> s = new LinkedHashMap<>();
        s.put("HEADACHE", List.of("headache", "migraine"));
        s.put("ABDOMINAL_PAIN", List.of("abdominal pain"));
        s.put("CHEST_PAIN", List.of("chest pain"));
        s.put("SYNCOPE", List.of("syncope", "fainting", "passed out"));
        s.put("PALPITATIONS", List.of("palpitations"));
        s.put("DYSPNEA", List.of("shortness of breath", "dyspnea"));
        s.put("BACK_PAIN", List.of("back pain"));
        s.put("DIZZINESS", List.of("dizziness", "lightheaded"));
        SYMPTOM_TERMS = Collections.unmodifiableMap(s);
    }

    private ClinicalTagger() {}

    public static Set<String> concepts(String text) {
        Set<String> tags = new TreeSet<>();
        if (text == null) return tags;
        String t = text.toLowerCase(Locale.ROOT);
        CONCEPT_TERMS.forEach((tag, terms) -> {
            if (terms.stream().anyMatch(term -> containsTerm(t, term))) tags.add(tag);
        });
        return tags;
    }

    public static String symptomCategory(String reason) {
        if (reason == null) return null;
        String t = reason.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> e : SYMPTOM_TERMS.entrySet()) {
            if (e.getValue().stream().anyMatch(term -> containsTerm(t, term))) return e.getKey();
        }
        return null;
    }

    public static boolean fractureRelated(String text) {
        return text != null && containsTerm(text.toLowerCase(Locale.ROOT), "fracture");
    }

    /** "|A|B|" storage format so a single LIKE '%|A|%' finds a tag without partial matches. */
    public static String encode(Collection<String> tags) {
        if (tags.isEmpty()) return "|";
        return "|" + String.join("|", new TreeSet<>(tags)) + "|";
    }

    public static boolean containsTerm(String lowerText, String term) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(term)).matcher(lowerText).find();
    }
}

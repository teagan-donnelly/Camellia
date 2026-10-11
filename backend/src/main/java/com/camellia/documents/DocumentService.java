package com.camellia.documents;

import com.camellia.config.CamelliaProperties;
import com.camellia.model.Encounter;
import com.camellia.repository.ClinicalDataRepository;
import com.camellia.repository.DocumentRepository;
import com.camellia.repository.DocumentRepository.DocumentMeta;
import com.camellia.repository.SupportRepository;
import com.camellia.security.AuthorizationService;
import com.camellia.security.DemoUser;
import com.camellia.security.Permission;
import com.camellia.web.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Uploaded patient documents (PDF, DOCX, DOC). Type and size are validated here, on the backend, from the file's
 * content (not only its name). Files are stored under a generated name outside any public directory and served only
 * through {@link #file}, which re-checks the caller's access to the patient on every request. Extracted text is used
 * only for authorized clinical search; it is never sent to Gemini.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    public enum FileType {
        PDF("pdf", "application/pdf"),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        DOC("doc", "application/msword");

        final String extension;
        final String contentType;

        FileType(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }
    }

    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private final DocumentRepository documents;
    private final ClinicalDataRepository clinical;
    private final SupportRepository support;
    private final AuthorizationService auth;
    private final CamelliaProperties.Documents cfg;

    public DocumentService(DocumentRepository documents, ClinicalDataRepository clinical, SupportRepository support,
                           AuthorizationService auth, CamelliaProperties props) {
        this.documents = documents;
        this.clinical = clinical;
        this.support = support;
        this.auth = auth;
        this.cfg = props.getDocuments();
    }

    public record DocumentView(String documentId, String patientId, String encounterId, String title, String fileName,
                               String fileType, long sizeBytes, String textStatus, Integer textChars, Integer pageCount,
                               LocalDateTime uploadedAt, String uploadedBy, String duplicateOf) {}

    public record StoredFile(String fileName, String contentType, byte[] bytes, boolean inlineAllowed) {}

    // ------------------------------------------------------------------ upload

    public DocumentView upload(DemoUser user, String patientId, String encounterId, String title, MultipartFile file) {
        auth.requirePermission(user, Permission.UPLOAD_DOCUMENTS);
        auth.requirePatient(user, patientId);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Choose a PDF or Word document to upload.");
        long max = cfg.getMaxUploadMb() * 1024L * 1024L;
        if (file.getSize() > max) {
            throw new ApiException(org.springframework.http.HttpStatus.CONTENT_TOO_LARGE, "DOCUMENT_TOO_LARGE",
                    "The file is larger than the " + cfg.getMaxUploadMb() + " MB limit.");
        }
        String fileName = cleanFileName(file.getOriginalFilename());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The file could not be read. Try uploading it again.");
        }
        FileType type = detect(fileName, bytes);
        String enc = blankToNull(encounterId);
        if (enc != null) {
            Encounter e = clinical.findEncounter(enc).orElse(null);
            if (e == null || !e.patientId().equals(patientId)) {
                throw ApiException.badRequest("That encounter does not belong to this patient.");
            }
        }

        Extraction text = extract(type, bytes);
        String sha = sha256(bytes);
        String duplicateOf = documents.existingWithHash(patientId, sha).orElse(null);
        String id = SupportRepository.newId("DOC");
        String storageKey = id + "." + type.extension;
        store(storageKey, bytes);
        String cleanTitle = blankToNull(title) == null ? stripExtension(fileName) : clip(title.trim(), 200);
        DocumentMeta meta = new DocumentMeta(id, patientId, enc, cleanTitle, fileName, type.name(), type.contentType,
                bytes.length, sha, storageKey, text.status(), text.text() == null ? null : text.text().length(), text.pages(),
                LocalDateTime.now(), user.id(), user.name());
        try {
            documents.insert(meta, text.text());
        } catch (RuntimeException e) {
            deleteQuietly(storageKey);   // never leave an orphaned file behind a failed insert
            throw e;
        }
        support.logActivity("DOCUMENT", id, patientId, "DOCUMENT_UPLOADED",
                type.name() + " document \"" + clip(fileName, 120) + "\" uploaded" + (enc == null ? "" : " to encounter " + enc), user.name());
        return view(meta, duplicateOf);
    }

    /**
     * Determines the type from the file's content and requires the extension to agree. Package-visible for tests.
     */
    static FileType detect(String fileName, byte[] bytes) {
        String ext = extension(fileName);
        FileType byName = switch (ext) {
            case "pdf" -> FileType.PDF;
            case "docx" -> FileType.DOCX;
            case "doc" -> FileType.DOC;
            default -> throw ApiException.badRequest("Only PDF (.pdf) and Word (.docx, .doc) documents can be uploaded.");
        };
        FileType byContent = sniff(bytes);
        if (byContent == null) {
            throw ApiException.badRequest("The file content is not a valid " + ext.toUpperCase(Locale.ROOT) + " document.");
        }
        if (byContent != byName) {
            throw ApiException.badRequest("The file content does not match its ." + ext + " extension.");
        }
        return byContent;
    }

    static FileType sniff(byte[] b) {
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
            // A ZIP: only a Word document (main part word/document.xml) without macros qualifies.
            try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(b))) {
                boolean word = !pkg.getPartsByName(java.util.regex.Pattern.compile("/word/document\\.xml")).isEmpty();
                boolean macros = !pkg.getPartsByName(java.util.regex.Pattern.compile("/word/vbaProject\\.bin")).isEmpty();
                return word && !macros ? FileType.DOCX : null;
            } catch (Exception e) {
                return null;
            }
        }
        if (startsWith(b, OLE2_MAGIC)) {
            // An OLE2 container: only a Word 97-2003 document (WordDocument stream) without a macro storage qualifies.
            try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(b))) {
                return fs.getRoot().hasEntry("WordDocument") && !fs.getRoot().hasEntry("Macros") ? FileType.DOC : null;
            } catch (Exception e) {
                return null;
            }
        }
        // PDF readers accept the header anywhere in the first 1024 bytes.
        String head = new String(b, 0, Math.min(b.length, 1024), StandardCharsets.ISO_8859_1);
        return head.contains("%PDF-") ? FileType.PDF : null;
    }

    private static boolean startsWith(byte[] b, byte[] prefix) {
        if (b.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (b[i] != prefix[i]) return false;
        return true;
    }

    record Extraction(String status, String text, Integer pages) {}

    /** Text extraction for search. Failures never block the upload; they are recorded as the text status. */
    Extraction extract(FileType type, byte[] bytes) {
        try {
            String raw;
            Integer pages = null;
            switch (type) {
                case PDF -> {
                    try (PDDocument pdf = Loader.loadPDF(bytes)) {
                        pages = pdf.getNumberOfPages();
                        raw = new PDFTextStripper().getText(pdf);
                    }
                }
                case DOCX -> {
                    try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(bytes));
                         XWPFWordExtractor ex = new XWPFWordExtractor(docx)) {
                        raw = ex.getText();
                    }
                }
                default -> {
                    try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
                         WordExtractor ex = new WordExtractor(doc)) {
                        raw = ex.getText();
                    }
                }
            }
            String text = raw == null ? "" : raw.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", " ")
                    .replaceAll("[ \\t\\u00A0]+", " ").replaceAll("\\s*\\n\\s*", "\n").trim();
            if (text.isEmpty()) return new Extraction("NO_TEXT", null, pages);
            return new Extraction("EXTRACTED", clip(text, cfg.getMaxExtractedChars()), pages);
        } catch (Exception | LinkageError e) {
            log.warn("Text extraction failed for an uploaded {} document: {}", type, e.toString());
            return new Extraction("FAILED", null, null);
        }
    }

    // ------------------------------------------------------------------ read

    public List<DocumentView> forPatient(DemoUser user, String patientId) {
        auth.requirePermission(user, Permission.VIEW_PATIENTS);
        auth.requirePatient(user, patientId);
        return documents.forPatient(patientId).stream().map(d -> view(d, null)).toList();
    }

    /** Unknown ids and documents of patients outside the caller's scope get the same 403. */
    public StoredFile file(DemoUser user, String documentId) {
        auth.requirePermission(user, Permission.VIEW_PATIENTS);
        DocumentMeta d = documents.find(documentId).orElseThrow(() -> AuthorizationService.denied(null));
        auth.requirePatient(user, d.patientId());
        Path p = resolve(d.storageKey());
        try {
            return new StoredFile(d.originalFilename(), d.contentType(), Files.readAllBytes(p), FileType.PDF.name().equals(d.fileType()));
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "DOCUMENT_FILE_MISSING",
                    "The stored file for this document is no longer available on this server.");
        }
    }

    // ------------------------------------------------------------------ internals

    private DocumentView view(DocumentMeta d, String duplicateOf) {
        return new DocumentView(d.documentId(), d.patientId(), d.encounterId(), d.title(), d.originalFilename(), d.fileType(),
                d.sizeBytes(), d.textStatus(), d.textChars(), d.pageCount(), d.uploadedAt(), d.uploadedBy(), duplicateOf);
    }

    private Path dir() {
        return Path.of(cfg.getStorageDir()).toAbsolutePath().normalize();
    }

    private Path resolve(String storageKey) {
        Path p = dir().resolve(storageKey).normalize();
        if (!p.startsWith(dir())) throw AuthorizationService.denied(null);
        return p;
    }

    /** CREATE_NEW: an existing upload is never overwritten. */
    private void store(String storageKey, byte[] bytes) {
        try {
            Files.createDirectories(dir());
            Files.write(resolve(storageKey), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the uploaded document", e);
        }
    }

    private void deleteQuietly(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** The original name for display only: no path, no control characters, at most 200 characters. */
    static String cleanFileName(String original) {
        String n = original == null ? "" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|]", "").trim();
        if (n.isEmpty()) n = "document";
        if (n.length() > 200) {
            String ext = extension(n);
            n = n.substring(0, 200 - ext.length() - 1) + "." + ext;
        }
        return n;
    }

    private static String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}

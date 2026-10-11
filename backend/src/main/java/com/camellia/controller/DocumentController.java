package com.camellia.controller;

import com.camellia.documents.DocumentService;
import com.camellia.documents.DocumentService.DocumentView;
import com.camellia.documents.DocumentService.StoredFile;
import com.camellia.security.DemoUser;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Uploaded patient documents. Every request re-checks the caller's access to the document's patient, so a copied
 * file URL never bypasses authorization; responses are never cached.
 */
@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/patients/{patientId}/documents")
    public List<DocumentView> list(DemoUser user, @PathVariable String patientId) {
        return documents.forPatient(user, patientId);
    }

    @PostMapping(value = "/patients/{patientId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentView upload(DemoUser user, @PathVariable String patientId,
                               @RequestParam("file") MultipartFile file,
                               @RequestParam(required = false) String encounterId,
                               @RequestParam(required = false) String title) {
        return documents.upload(user, patientId, encounterId, title, file);
    }

    /** PDF opens inline unless {@code download=true}; Word documents always download. */
    @GetMapping("/documents/{documentId}/file")
    public ResponseEntity<byte[]> file(DemoUser user, @PathVariable String documentId,
                                       @RequestParam(defaultValue = "false") boolean download) {
        StoredFile f = documents.file(user, documentId);
        ContentDisposition cd = (download || !f.inlineAllowed() ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(f.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(f.bytes());
    }
}

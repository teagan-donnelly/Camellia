package com.camellia.controller;

import com.camellia.ingestion.ImportService;
import com.camellia.ingestion.ImportService.*;
import com.camellia.repository.ImportRepository.ImportBatch;
import com.camellia.security.DemoUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Patient record import (synthetic files only). Requires the IMPORT_RECORDS permission. */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/preview")
    public Preview preview(DemoUser user, @RequestBody ImportRequest req) {
        return imports.preview(user, req);
    }

    @PostMapping("/confirm")
    public ConfirmResult confirm(DemoUser user, @RequestBody ConfirmRequest req) {
        return imports.confirm(user, req);
    }

    @GetMapping("/samples")
    public List<Sample> samples(DemoUser user) {
        return imports.samples(user);
    }

    /** Download one sample file. */
    @GetMapping("/samples/{file}")
    public ResponseEntity<String> sample(DemoUser user, @PathVariable String file) {
        String body = imports.sample(user, file);
        return ResponseEntity.ok()
                .contentType(file.endsWith(".csv") ? MediaType.parseMediaType("text/csv") : MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .body(body);
    }

    @GetMapping("/history")
    public List<ImportBatch> history(DemoUser user) {
        return imports.history(user);
    }
}

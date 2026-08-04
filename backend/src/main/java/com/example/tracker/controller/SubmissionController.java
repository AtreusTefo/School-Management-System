package com.example.tracker.controller;

import com.example.tracker.dto.FileDownload;
import com.example.tracker.dto.SubmissionView;
import com.example.tracker.service.SubmissionService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CONTROLLER (PRESENTATION) LAYER for handing work in and marking it.
 *
 * The upload and download endpoints are the only place in this application that
 * deals in bytes rather than JSON, and they are the clearest illustration of
 * what this layer is for: unwrapping HTTP, and wrapping it back up again.
 *
 * Note what does NOT happen here. Nothing decides whether a file is acceptable,
 * who may read it, or whether the work can still be changed. The controller
 * turns a MultipartFile into a filename and an array of bytes and hands them
 * over; every rule about them lives in SubmissionService, where it can be tested
 * without a web server and cannot be bypassed by a second caller.
 */
@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    private final SubmissionService submissions;

    public SubmissionController(SubmissionService submissions) {
        this.submissions = submissions;
    }

    /** GET /api/submissions - a student's own work, or a teacher's marking queue. */
    @GetMapping
    public List<SubmissionView> list() {
        return submissions.listSubmissions();
    }

    /**
     * POST /api/submissions/{id}/file - attach or replace the PDF.
     *
     * Multipart rather than JSON, because base64 inside a JSON body inflates a
     * binary by about a third and forces the whole thing into memory as a string
     * before anything can look at it.
     *
     * getBytes() can throw IOException if the upload is truncated mid-transfer.
     * That is a genuine transport failure rather than a bad request, so it is
     * translated into one clear message instead of being allowed to surface as
     * an unexplained 500.
     */
    @PostMapping("/{id}/file")
    public SubmissionView upload(@PathVariable Long id,
                                 @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose a PDF file to upload.");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "The upload did not complete. Please try again.", e);
        }

        return submissions.uploadFile(
                id, file.getOriginalFilename(), file.getContentType(), content);
    }

    /** PUT /api/submissions/{id}/submit - hand it in. */
    @PutMapping("/{id}/submit")
    public SubmissionView submit(@PathVariable Long id) {
        return submissions.submit(id);
    }

    /** PUT /api/submissions/{id}/unsubmit - reopen it. Teacher only. */
    @PutMapping("/{id}/unsubmit")
    public SubmissionView unsubmit(@PathVariable Long id) {
        return submissions.unsubmit(id);
    }

    /**
     * GET /api/submissions/{id}/file - download the PDF.
     *
     * ATTACHMENT, NOT INLINE. Serving a user-uploaded document inline invites
     * the browser to render it in the context of this site, which is how an
     * uploaded file becomes a route to running script against our own origin.
     * "attachment" tells the browser to save it instead.
     *
     * ContentDisposition builds the header rather than string concatenation.
     * A filename is user-supplied text - the service has already stripped path
     * separators and control characters, and this handles the quoting and the
     * non-ASCII encoding correctly rather than approximately.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        FileDownload file = submissions.download(id);

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.content().length)
                .body(new ByteArrayResource(file.content()));
    }
}

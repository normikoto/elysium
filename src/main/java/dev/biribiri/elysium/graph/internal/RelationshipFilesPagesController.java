package dev.biribiri.elysium.graph.internal;

import dev.biribiri.elysium.graph.InvalidRelationshipFileException;
import dev.biribiri.elysium.graph.RelationshipFiles;
import dev.biribiri.elysium.graph.RelationshipKind;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Lets someone import and export the accounts they follow, block, and mute, as CSV files. There's no API for this, as
 * there isn't in Mastodon. Every page under {@code /settings} needs a login and a CSRF token, through the security
 * configuration.
 */
@Controller
class RelationshipFilesPagesController {

    private static final String IMPORTS_PATH = "/settings/imports";
    private static final String EXPORTS_PATH = "/settings/exports";
    private static final String PAGE = "relationship-files";

    private final RelationshipFiles files;

    RelationshipFilesPagesController(RelationshipFiles files) {
        this.files = files;
    }

    @GetMapping(IMPORTS_PATH)
    String show(@RequestParam(required = false) String started, Authentication authentication, Model model) {
        model.addAttribute("started", started != null);
        return page(accountId(authentication), RelationshipKind.FOLLOWS.key(), model);
    }

    @PostMapping(IMPORTS_PATH)
    String start(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) MultipartFile file,
            Authentication authentication,
            Model model,
            HttpServletResponse response) throws IOException {
        var accountId = accountId(authentication);
        try {
            var chosen = Arrays.stream(RelationshipKind.values())
                    .filter(candidate -> candidate.key().equals(kind))
                    .findFirst()
                    .orElseThrow(() -> new InvalidRelationshipFileException("Choose what the file lists"));
            if (file == null || file.isEmpty()) {
                throw new InvalidRelationshipFileException("Choose a file");
            }
            // Before it's read, since Spring takes files as large as a video.
            if (file.getSize() > RelationshipFiles.MAX_FILE_SIZE) {
                throw new InvalidRelationshipFileException("The file is too large to be a list of accounts");
            }
            files.start(accountId, chosen, file.getBytes());
        } catch (InvalidRelationshipFileException e) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("started", false);
            model.addAttribute("error", e.getMessage());
            return page(accountId, kind, model);
        }
        return "redirect:" + IMPORTS_PATH + "?started";
    }

    @GetMapping(EXPORTS_PATH + "/follows.csv")
    void exportFollows(Authentication authentication, HttpServletResponse response) throws IOException {
        export(RelationshipKind.FOLLOWS, authentication, response);
    }

    @GetMapping(EXPORTS_PATH + "/blocks.csv")
    void exportBlocks(Authentication authentication, HttpServletResponse response) throws IOException {
        export(RelationshipKind.BLOCKS, authentication, response);
    }

    @GetMapping(EXPORTS_PATH + "/mutes.csv")
    void exportMutes(Authentication authentication, HttpServletResponse response) throws IOException {
        export(RelationshipKind.MUTES, authentication, response);
    }

    /**
     * Written while the request waits, rather than as a StreamingResponseBody, whose async timeout could cut off a long
     * one partway.
     */
    private void export(RelationshipKind kind, Authentication authentication, HttpServletResponse response)
            throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(kind.fileName()).build().toString());
        files.export(accountId(authentication), kind, response.getOutputStream());
    }

    private String page(UUID accountId, String kind, Model model) {
        model.addAttribute("kinds", List.of(RelationshipKind.values()));
        model.addAttribute("kind", kind);
        model.addAttribute("maxRows", RelationshipFiles.MAX_ROWS);
        model.addAttribute("progress", files.latest(accountId).orElse(null));
        return PAGE;
    }

    /**
     * A logged in user's name is their actor ID.
     */
    private static UUID accountId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

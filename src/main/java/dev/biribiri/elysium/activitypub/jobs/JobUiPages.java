package dev.biribiri.elysium.activitypub.jobs;

import no.bekk.dbscheduler.ui.controller.IndexHtmlController;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the job UI's page for the routes in its bundle that the library doesn't map itself, so that they can be reloaded
 * and linked to. The library's fallback for these fails with a 500 on Spring 7, because the resource it returns doesn't
 * support the last-modified check that Spring's resource handler makes. Check these when upgrading the UI. It maps the
 * front page and {@code history/all}, and adding those here would make them ambiguous. Task names can't contain a dot, so
 * that {@code favicon.svg} still gets the file.
 */
@Controller
class JobUiPages {

    private final IndexHtmlController ui;

    JobUiPages(IndexHtmlController ui) {
        this.ui = ui;
    }

    @GetMapping(
            path = {
                    "/admin/db-scheduler/history/{taskName}/{taskInstance}",
                    "/admin/db-scheduler/{taskName:[^.]+}",
                    "/admin/db-scheduler/{taskName:[^.]+}/page/{page}"
            },
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String page() {
        return ui.indexHtml();
    }
}

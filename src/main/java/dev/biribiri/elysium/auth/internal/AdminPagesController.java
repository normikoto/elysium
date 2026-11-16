package dev.biribiri.elysium.auth.internal;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The admin front page, which links to the others. It's here rather than with any one of them because they're spread
 * across modules, and this module is the one that limits {@code /admin} to admins.
 */
@Controller
class AdminPagesController {

    static final String PATH = "/admin";

    @GetMapping(PATH)
    String index() {
        return "admin";
    }
}

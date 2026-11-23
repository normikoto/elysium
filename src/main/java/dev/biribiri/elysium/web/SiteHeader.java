package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;

/**
 * What the bar at the top of every page shows. This applies to every module's controllers, since they all render their
 * pages with the same layout.
 */
@ControllerAdvice
class SiteHeader {

    private final InstanceProperties instance;

    SiteHeader(InstanceProperties instance) {
        this.instance = instance;
    }

    @ModelAttribute
    void addTo(Model model, Principal principal) {
        model.addAttribute("siteDomain", instance.accountDomain());
        model.addAttribute("signedIn", principal != null);
    }
}

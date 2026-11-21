package dev.biribiri.elysium.announcement.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.announcement.Announcement;
import dev.biribiri.elysium.announcement.Announcements;
import dev.biribiri.elysium.announcement.InvalidAnnouncementException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The admin page for announcements. Everything under {@code /admin} is limited to admins and needs a CSRF token, through
 * the security configuration.
 *
 * <p>Times are entered and shown in UTC, since the server doesn't know the admin's time zone, and the page says so.
 */
@Controller
class AnnouncementPagesController {

    private static final String PATH = "/admin/announcements";

    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final Announcements announcements;

    AnnouncementPagesController(Announcements announcements) {
        this.announcements = announcements;
    }

    @GetMapping(PATH)
    String list(Model model) {
        model.addAttribute("form", new Form("", "", "", false, true));
        return page(model);
    }

    @PostMapping(PATH)
    String add(
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String startsAt,
            @RequestParam(required = false) String endsAt,
            @RequestParam(required = false) String allDay,
            @RequestParam(required = false) String publish,
            Model model,
            HttpServletResponse response) {
        try {
            announcements.create(text, time(startsAt), time(endsAt), allDay != null, publish != null);
            return "redirect:" + PATH;
        } catch (InvalidAnnouncementException e) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("error", e.getMessage());
            model.addAttribute("form", new Form(text, startsAt, endsAt, allDay != null, publish != null));
            return page(model);
        }
    }

    /**
     * An announcement that someone else has removed meanwhile is left removed. An edit that can't be saved shows the page
     * with the announcement as it was, since the form for each one is filled from what's stored.
     */
    @PostMapping(PATH + "/{id}")
    String change(
            @PathVariable String id,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String startsAt,
            @RequestParam(required = false) String endsAt,
            @RequestParam(required = false) String allDay,
            Model model,
            HttpServletResponse response) {
        try {
            var parsed = Ids.parse(id);
            if (parsed.isPresent()) {
                announcements.update(parsed.get(), text, time(startsAt), time(endsAt), allDay != null);
            }
            return "redirect:" + PATH;
        } catch (InvalidAnnouncementException e) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("error", e.getMessage());
            model.addAttribute("form", new Form("", "", "", false, true));
            return page(model);
        }
    }

    @PostMapping(PATH + "/{id}/publish")
    String publish(@PathVariable String id) {
        Ids.parse(id).ifPresent(announcements::publish);
        return "redirect:" + PATH;
    }

    @PostMapping(PATH + "/{id}/unpublish")
    String unpublish(@PathVariable String id) {
        Ids.parse(id).ifPresent(announcements::unpublish);
        return "redirect:" + PATH;
    }

    /**
     * Someone else may have removed it already, which is the outcome that was asked for.
     */
    @PostMapping(PATH + "/{id}/delete")
    String remove(@PathVariable String id) {
        Ids.parse(id).ifPresent(announcements::delete);
        return "redirect:" + PATH;
    }

    private String page(Model model) {
        model.addAttribute("announcements", announcements.list().stream().map(Row::of).toList());
        model.addAttribute("maxTextLength", Announcements.MAX_TEXT_LENGTH);
        return "admin-announcements";
    }

    /**
     * @return null for an empty field
     * @throws InvalidAnnouncementException if it isn't a date and time as the browser's field sends one
     */
    private static Instant time(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.strip()).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new InvalidAnnouncementException("Enter times as a date and a time, such as 2026-12-31T18:00.");
        }
    }

    private static String input(Instant time) {
        return time == null ? "" : INPUT.format(time.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES));
    }

    /**
     * What the form for a new announcement holds, kept as it was entered when it's refused.
     */
    record Form(String text, String startsAt, String endsAt, boolean allDay, boolean publish) {
    }

    record Row(UUID id, String text, String startsAt, String endsAt, boolean allDay, boolean published) {

        static Row of(Announcement announcement) {
            return new Row(announcement.id(), announcement.text(), input(announcement.startsAt()),
                    input(announcement.endsAt()), announcement.allDay(), announcement.isPublished());
        }
    }
}

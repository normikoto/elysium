package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.InvalidProfileException;
import dev.biribiri.elysium.actor.Profile;
import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.actor.ProfileService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lets someone change their own profile and account settings. Applications can change the same ones through the API, with
 * the same limits.
 */
@Controller
class SettingsController {

    private final ActorService actors;
    private final ProfileService profiles;
    private final InstanceProperties instance;

    SettingsController(ActorService actors, ProfileService profiles, InstanceProperties instance) {
        this.actors = actors;
        this.profiles = profiles;
        this.instance = instance;
    }

    /**
     * A row on the form for a field. Verification is only shown for a field as it's saved.
     */
    record FieldRow(String name, String value, boolean verified) {
    }

    @GetMapping(SecurityConfig.SETTINGS_PATH)
    String form(@RequestParam(required = false) String saved, Authentication authentication, Model model) {
        var accountId = accountId(authentication);
        var profile = profiles.find(accountId);
        var rows = profile.fields().stream()
                .map(field -> new FieldRow(field.name(), field.value(), field.verifiedAt() != null))
                .toList();
        show(model, accountId, profile, profile.displayName(), profile.note(), rows, actors.hidesCollections(accountId));
        model.addAttribute("saved", saved != null);
        return "settings";
    }

    /**
     * The form is read as a whole, rather than as a list for each field's names and values, because Spring would split a
     * single value on its commas. A checkbox that isn't ticked isn't sent, so a missing value means off.
     */
    @PostMapping(SecurityConfig.SETTINGS_PATH)
    String save(
            @RequestParam MultiValueMap<String, String> form,
            @RequestParam(required = false) MultipartFile avatar,
            @RequestParam(required = false) MultipartFile header,
            Authentication authentication,
            Model model,
            HttpServletResponse response) throws IOException {
        var accountId = accountId(authentication);
        var displayName = form.getFirst("displayName");
        var note = form.getFirst("note");
        var names = form.getOrDefault("fieldName", List.of());
        var values = form.getOrDefault("fieldValue", List.of());
        var fields = ProfileChange.NewField.zip(names, values);
        var hideCollections = "true".equals(form.getFirst("hideCollections"));
        // Only what the form has a control for is changed, as a form with no rows for fields has none to send.
        var change = ProfileChange.NONE
                .withAvatar(image(avatar, form.containsKey("removeAvatar")))
                .withHeader(image(header, form.containsKey("removeHeader")));
        if (displayName != null) {
            change = change.withDisplayName(displayName);
        }
        if (note != null) {
            change = change.withNote(note);
        }
        if (form.containsKey("fieldName") || form.containsKey("fieldValue")) {
            change = change.withFields(fields);
        }
        try {
            if (!profiles.update(accountId, change)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        } catch (InvalidProfileException e) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            var rows = fields.stream().map(field -> new FieldRow(field.name(), field.value(), false)).toList();
            var saved = profiles.find(accountId);
            show(model, accountId, saved, change.displayName().orElse(saved.displayName()), change.note().orElse(saved.note()),
                    rows, hideCollections);
            model.addAttribute("error", e.getMessage().replaceFirst("^Validation failed: ", ""));
            return "settings";
        }
        if (!actors.setHidesCollections(accountId, hideCollections)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "redirect:" + SecurityConfig.SETTINGS_PATH + "?saved";
    }

    /**
     * The form always has a row for each field the account is allowed, plus one for any it has beyond that, since a lower
     * limit doesn't remove fields.
     */
    private void show(
            Model model,
            UUID accountId,
            Profile saved,
            String displayName,
            String note,
            List<FieldRow> fields,
            boolean hideCollections) {
        var rows = new ArrayList<>(fields);
        while (rows.size() < profiles.maxFields()) {
            rows.add(new FieldRow("", "", false));
        }
        model.addAttribute("username", actors.findLocalById(accountId).orElseThrow().username());
        model.addAttribute("displayName", displayName);
        model.addAttribute("note", note);
        model.addAttribute("fields", rows);
        model.addAttribute("maxFields", profiles.maxFields());
        model.addAttribute("avatar", saved.avatar() == null ? null : instance.resolve(saved.avatar().staticPath()));
        model.addAttribute("header", saved.header() == null ? null : instance.resolve(saved.header().staticPath()));
        model.addAttribute("maxDisplayName", ProfileService.MAX_DISPLAY_NAME);
        model.addAttribute("maxNote", ProfileService.MAX_NOTE);
        model.addAttribute("maxFieldName", ProfileService.MAX_FIELD_NAME);
        model.addAttribute("maxFieldValue", ProfileService.MAX_FIELD_VALUE);
        model.addAttribute("hideCollections", hideCollections);
    }

    /**
     * A file input left empty is sent with no content. Choosing a new image wins over ticking "remove".
     */
    private static ProfileChange.Image image(MultipartFile file, boolean remove) throws IOException {
        if (file != null && !file.isEmpty()) {
            return ProfileChange.Image.upload(file.getBytes());
        }
        return remove ? ProfileChange.Image.REMOVE : ProfileChange.Image.KEEP;
    }

    /**
     * A logged in user's name is their actor ID: see {@link LocalAccounts#loadUserByUsername}. The account is active, or
     * {@link ActiveAccountFilter} would have logged them out.
     */
    private UUID accountId(Authentication authentication) {
        var id = UUID.fromString(authentication.getName());
        if (actors.findLocalById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return id;
    }
}

package dev.biribiri.elysium;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ElysiumController {

    @GetMapping("/")
    public String index() {
        return "Hello, Elysium!";
    }
}

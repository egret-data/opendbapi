package io.opendbapi;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Hands client-side routes (spec §17.3) to the React app. Listed explicitly rather than
 * as a catch-all so that unknown {@code /admin/api/**} and serving paths keep returning
 * their own 404s instead of an HTML page.
 */
@Controller
public class SpaForwardingController {

    @GetMapping({
            "/login",
            "/account/**",
            "/w/**",
            "/admin/workspaces/**",
            "/admin/users/**",
            "/admin/identity-providers/**",
            "/admin/audit/**"
    })
    public String spa() {
        return "forward:/index.html";
    }
}

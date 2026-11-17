package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

/** Every top-level route of the React app must reach index.html when loaded directly (bookmarks, refresh). */
class SpaRoutesTest {

    @Test
    void everyTopLevelClientRouteIsForwardedToTheApp() throws Exception {
        // Arrange
        String app = Files.readString(Path.of("..", "..", "frontend", "src", "App.tsx"));
        List<String> clientRoutes = Pattern.compile("path=\"(/[a-z][^\"]*)\"").matcher(app).results().map(m -> m.group(1))
                .filter(r -> !r.equals("/")).toList();
        List<String> forwarded = Arrays.asList(SpaForwardingController.class.getMethod("spa").getAnnotation(GetMapping.class).value());

        // Act
        List<String> missing = clientRoutes.stream()
                .filter(r -> forwarded.stream().noneMatch(f -> f.equals(r) || (f.endsWith("/**") && (r + "/").startsWith(f.substring(0, f.length() - 2)))))
                .filter(r -> !r.equals("/admin") && !r.equals("/w/:ws"))
                .toList();

        // Assert
        assertThat(clientRoutes).isNotEmpty();
        assertThat(missing).as("client routes without a forward in SpaForwardingController").isEmpty();
    }
}

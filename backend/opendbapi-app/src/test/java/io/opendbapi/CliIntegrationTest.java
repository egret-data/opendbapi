package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendbapi.cli.Cli;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The opendbapi command line against a running instance (spec §15.2). */
class CliIntegrationTest extends AppTestSupport {

    @TempDir
    Path dir;

    private String ws;
    private Map<String, String> env;

    private record Run(int exit, String out, String err) {
    }

    private Run cli(Map<String, String> environment, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit = new Cli(environment, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run(args);
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void arrange() throws Exception {
        ApiClient admin = signedInAdmin();
        ws = "cli-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "CLI " + ws)).expect(201);
        admin.post("/admin/api/ws/" + ws + "/secrets", Map.of("name", "db", "value", POSTGRES.getPassword())).expect(201);
        Files.writeString(dir.resolve("10-all.yaml"),
                ConfigIntegrationTest.FILE.formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getUsername()));
        env = Map.of("OPENDBAPI_URL", "http://localhost:" + port, "OPENDBAPI_WORKSPACE", ws,
                "OPENDBAPI_USER", "admin", "OPENDBAPI_PASSWORD", ADMIN_PASSWORD);
    }

    @Test
    void planApplyAndExportFromTheCommandLine() throws Exception {
        // Act
        Run planned = cli(env, "plan", "--dir", dir.toString(), "--publish", "--detailed-exitcode");
        Run applied = cli(env, "apply", "--dir", dir.toString(), "--publish");
        Run again = cli(env, "plan", "--dir", dir.toString(), "--publish", "--detailed-exitcode");
        Path exported = dir.resolve("export.out");
        Run export = cli(env, "export", "--out", exported.toString());

        // Assert
        assertThat(planned.exit()).as(planned.err()).isEqualTo(2);
        assertThat(planned.out()).contains("+ Endpoint GET /numbers  (publish)").contains("6 to create");
        assertThat(applied.exit()).as(applied.err()).isZero();
        assertThat(applied.out()).contains("GET /numbers: published").contains("Applied.");
        assertThat(again.exit()).isZero();
        assertThat(again.out()).contains("No changes.");
        assertThat(export.exit()).isZero();
        assertThat(Files.readString(exported)).contains("kind: Endpoint").contains("passwordRef: secret:db");
    }

    @Test
    void anApiTokenReplacesThePassword() {
        // Arrange
        String secret = signedInAdmin().post("/admin/api/me/tokens", Map.of("name", "cli", "workspace", ws)).expect(201).json()
                .path("secret").asText();
        Map<String, String> tokenEnv = Map.of("OPENDBAPI_URL", env.get("OPENDBAPI_URL"), "OPENDBAPI_WORKSPACE", ws,
                "OPENDBAPI_TOKEN", secret);

        // Act
        Run applied = cli(tokenEnv, "apply", "--dir", dir.toString(), "--publish");

        // Assert
        assertThat(applied.exit()).as(applied.err()).isZero();
        assertThat(applied.out()).contains("GET /numbers: published");
    }

    @Test
    void errorsExitWithOneAndExplainWhy() {
        // Act
        Run wrongPassword = cli(Map.of("OPENDBAPI_URL", env.get("OPENDBAPI_URL"), "OPENDBAPI_WORKSPACE", ws,
                "OPENDBAPI_USER", "admin", "OPENDBAPI_PASSWORD", "nope"), "plan", "--dir", dir.toString());
        Run noFiles = cli(env, "plan");
        Run unknownEnv = cli(env, "plan", "--dir", dir.toString(), "--env", "missing");

        // Assert
        assertThat(wrongPassword.exit()).isEqualTo(1);
        assertThat(wrongPassword.err()).contains("HTTP 401");
        assertThat(noFiles.exit()).isEqualTo(1);
        assertThat(noFiles.err()).contains("plan needs --file or --dir");
        assertThat(unknownEnv.exit()).isEqualTo(1);
        assertThat(unknownEnv.err()).contains("Environment 'missing' does not exist");
    }
}

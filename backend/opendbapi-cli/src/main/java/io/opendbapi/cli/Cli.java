package io.opendbapi.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code opendbapi export|plan|apply}: configuration as code against a running OpenDBAPI
 * (spec §15.2). Signs in as a local user ({@code OPENDBAPI_USER}/{@code OPENDBAPI_PASSWORD}),
 * then calls the workspace's export and config endpoints.
 *
 * <p>Exit codes: 0 success; 1 error, or endpoints that failed to publish; 2 with
 * {@code --detailed-exitcode} when a plan has changes.
 */
public final class Cli {

    static final String USAGE = """
            Usage: opendbapi <command> [options]

            Commands:
              export   Write the workspace as YAML (to --out, or standard output)
              plan     Show what apply would change
              apply    Make the workspace match the files

            Options:
              --url URL              OpenDBAPI base URL            (or OPENDBAPI_URL)
              --workspace SLUG       workspace                     (or OPENDBAPI_WORKSPACE)
              --file PATH            a YAML file (repeatable)
              --dir PATH             every *.yaml / *.yml in a directory
              --out PATH             export: write here instead of standard output
              --env NAME             environment mapping logical datasource names
              --prune                delete what the files do not list
              --publish              publish endpoints marked live (after their tests), disable those marked disabled
              --expected-digest D    apply: refuse unless the plan still has this digest
              --detailed-exitcode    plan: exit 2 when there are changes

            Signs in with OPENDBAPI_USER and OPENDBAPI_PASSWORD (a local user who is admin of the workspace).
            """;

    private final Map<String, String> env;
    private final PrintStream out;
    private final PrintStream err;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(15)).build();

    public Cli(Map<String, String> env, PrintStream out, PrintStream err) {
        this.env = env;
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new Cli(System.getenv(), System.out, System.err).run(args));
    }

    /** Parsed command line. */
    record Options(String command, String url, String workspace, List<String> files, String dir, String out, String environment,
                   boolean prune, boolean publish, String expectedDigest, boolean detailedExitCode) {
    }

    static final class UsageError extends RuntimeException {
        UsageError(String message) {
            super(message);
        }
    }

    public int run(String[] args) {
        Options o;
        try {
            o = parse(args, env);
        } catch (UsageError e) {
            err.println("opendbapi: " + e.getMessage());
            err.println();
            err.print(USAGE);
            return 1;
        }
        if (o == null) {
            out.print(USAGE);
            return 0;
        }
        try {
            signIn(o.url());
            return switch (o.command()) {
                case "export" -> export(o);
                case "plan", "apply" -> config(o);
                default -> throw new UsageError("unknown command " + o.command());
            };
        } catch (Failure e) {
            err.println("opendbapi: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("opendbapi: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    static Options parse(String[] args, Map<String, String> env) {
        if (args.length == 0 || List.of("-h", "--help", "help").contains(args[0])) {
            return null;
        }
        String command = args[0];
        if (!List.of("export", "plan", "apply").contains(command)) {
            throw new UsageError("unknown command '" + command + "'");
        }
        String url = env.get("OPENDBAPI_URL");
        String workspace = env.get("OPENDBAPI_WORKSPACE");
        List<String> files = new ArrayList<>();
        String dir = null;
        String outPath = null;
        String environment = null;
        String digest = null;
        boolean prune = false;
        boolean publish = false;
        boolean detailed = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--prune" -> prune = true;
                case "--publish" -> publish = true;
                case "--detailed-exitcode" -> detailed = true;
                case "--url", "--workspace", "--file", "--dir", "--out", "--env", "--expected-digest" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageError(a + " needs a value");
                    }
                    String v = args[++i];
                    switch (a) {
                        case "--url" -> url = v;
                        case "--workspace" -> workspace = v;
                        case "--file" -> files.add(v);
                        case "--dir" -> dir = v;
                        case "--out" -> outPath = v;
                        case "--env" -> environment = v;
                        default -> digest = v;
                    }
                }
                default -> throw new UsageError("unknown option '" + a + "'");
            }
        }
        if (url == null || url.isBlank()) {
            throw new UsageError("give --url or OPENDBAPI_URL");
        }
        if (workspace == null || workspace.isBlank()) {
            throw new UsageError("give --workspace or OPENDBAPI_WORKSPACE");
        }
        if (!"export".equals(command) && files.isEmpty() && dir == null) {
            throw new UsageError(command + " needs --file or --dir");
        }
        return new Options(command, url.replaceAll("/+$", ""), workspace, files, dir, outPath, environment, prune, publish, digest,
                detailed);
    }

    private int export(Options o) throws IOException, InterruptedException {
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri(o.url(), "/admin/api/ws/" + o.workspace() + "/export")).GET());
        if (o.out() == null) {
            out.print(r.body());
        } else {
            Files.writeString(Path.of(o.out()), r.body());
            err.println("Wrote " + o.out());
        }
        return 0;
    }

    private int config(Options o) throws IOException, InterruptedException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("format", "text");
        if (o.environment() != null) {
            query.put("environment", o.environment());
        }
        query.put("prune", String.valueOf(o.prune()));
        query.put("publish", String.valueOf(o.publish()));
        if ("apply".equals(o.command()) && o.expectedDigest() != null) {
            query.put("expectedDigest", o.expectedDigest());
        }
        StringBuilder q = new StringBuilder();
        query.forEach((k, v) -> q.append(q.isEmpty() ? "?" : "&").append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri(o.url(), "/admin/api/ws/" + o.workspace() + "/config:" + o.command() + q))
                .header("Content-Type", "application/yaml")
                .POST(HttpRequest.BodyPublishers.ofString(readFiles(o))));
        out.print(r.body());
        if ("apply".equals(o.command())) {
            return "ok".equals(r.headers().firstValue("X-OpenDBAPI-Result").orElse("ok")) ? 0 : 1;
        }
        boolean changes = Boolean.parseBoolean(r.headers().firstValue("X-OpenDBAPI-Changes").orElse("false"));
        return changes && o.detailedExitCode() ? 2 : 0;
    }

    private String readFiles(Options o) throws IOException {
        List<Path> paths = new ArrayList<>();
        o.files().forEach(f -> paths.add(Path.of(f)));
        if (o.dir() != null) {
            try (Stream<Path> s = Files.list(Path.of(o.dir()))) {
                s.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).sorted().forEach(paths::add);
            }
        }
        if (paths.isEmpty()) {
            throw new Failure("no YAML files found");
        }
        StringBuilder all = new StringBuilder();
        for (Path p : paths) {
            all.append(all.isEmpty() ? "" : "\n---\n").append(Files.readString(p));
        }
        return all.toString();
    }

    private void signIn(String url) throws IOException, InterruptedException {
        String user = env.get("OPENDBAPI_USER");
        String password = env.get("OPENDBAPI_PASSWORD");
        if (user == null || password == null) {
            throw new Failure("set OPENDBAPI_USER and OPENDBAPI_PASSWORD");
        }
        http.send(HttpRequest.newBuilder(uri(url, "/admin/api/auth/providers")).GET().build(), HttpResponse.BodyHandlers.discarding());
        String body = "{\"source\":\"local\",\"username\":" + quote(user) + ",\"password\":" + quote(password) + "}";
        send(HttpRequest.newBuilder(uri(url, "/admin/api/auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        for (HttpCookie c : cookies.getCookieStore().getCookies()) {
            if ("XSRF-TOKEN".equals(c.getName())) {
                request.header("X-XSRF-TOKEN", c.getValue());
            }
        }
        request.header("Accept", "text/plain, application/json, application/yaml").timeout(Duration.ofMinutes(5));
        HttpResponse<String> r = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() >= 400) {
            throw new Failure("HTTP " + r.statusCode() + ": " + detail(r.body()));
        }
        return r;
    }

    private static URI uri(String base, String path) {
        return URI.create(base + path);
    }

    /** The {@code detail} of an RFC 9457 problem, or the body. */
    static String detail(String body) {
        Matcher m = Pattern.compile("\"detail\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body == null ? "" : body);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\") : String.valueOf(body);
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }
}

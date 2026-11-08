package de.raindancer.core.platform.github;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubIssuesTest {

    private static final String REPO = "RaindanceMC/bugreports";

    /** A pretend GitHub: answers by method and path, and keeps every request. */
    private static final class FakeGitHub implements GitHubApp.Transport {
        final List<String> seen = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        final Map<String, Function<String, GitHubApp.Response>> routes = new java.util.LinkedHashMap<>();

        FakeGitHub route(String methodAndPath, int status, String json) {
            routes.put(methodAndPath, body -> new GitHubApp.Response(status, json));
            return this;
        }

        @Override
        public GitHubApp.Response send(String method, String url, Map<String, String> header, String body) {
            String path = url.replace("https://api.github.com", "");
            seen.add(method + " " + path);
            bodies.add(body);
            headers.add(header);
            for (var route : routes.entrySet()) {
                if ((method + " " + path).startsWith(route.getKey())) {
                    return route.getValue().apply(body);
                }
            }
            return new GitHubApp.Response(404, "{\"message\":\"Not Found\"}");
        }
    }

    private static String pem() throws IOException {
        try (InputStream in = GitHubIssuesTest.class.getResourceAsStream("test-app-key.pem")) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private static FakeGitHub signedIn() {
        return new FakeGitHub()
                .route("GET /repos/" + REPO + "/installation", 200, "{\"id\": 77}")
                .route("POST /app/installations/77/access_tokens", 201,
                        "{\"token\": \"ghs_test\", \"expires_at\": \"2099-01-01T00:00:00Z\"}");
    }

    private static GitHubIssues issues(FakeGitHub github) throws Exception {
        return new GitHubIssues(new GitHubApp(5668579, GitHubApp.readKey(pem()), github, () -> 1_700_000_000_000L));
    }

    @Test
    @DisplayName("GitHub's own key file, PKCS#1, is read; the app signs in with a JWT GitHub can check")
    void signsIn() throws Exception {
        var key = (RSAPrivateCrtKey) GitHubApp.readKey(pem());
        GitHubApp app = new GitHubApp(5668579, key, signedIn(), () -> 1_700_000_000_000L);
        String[] jwt = app.jwt().split("\\.");
        JsonObject claims = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(jwt[1]))).getAsJsonObject();
        assertThat(claims.get("iss").getAsString()).isEqualTo("5668579");
        assertThat(claims.get("exp").getAsLong() - claims.get("iat").getAsLong()).isLessThanOrEqualTo(600);

        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(java.security.KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent())));
        verify.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(Base64.getUrlDecoder().decode(jwt[2]))).isTrue();
    }

    @Test
    @DisplayName("an installation token is fetched once and used until it runs out")
    void tokenOnce() throws Exception {
        FakeGitHub github = signedIn().route("GET /repos/" + REPO + "/issues", 200, "[]");
        GitHubIssues issues = issues(github);
        issues.open(REPO, "area: Claims");
        issues.open(REPO, "area: Claims");
        assertThat(github.seen.stream().filter(s -> s.contains("access_tokens"))).hasSize(1);
        assertThat(github.headers.getLast().get("Authorization")).isEqualTo("Bearer ghs_test");
    }

    @Test
    @DisplayName("open issues of one area: real issues only, with how many others had them")
    void open() throws Exception {
        FakeGitHub github = signedIn().route("GET /repos/" + REPO + "/issues", 200, """
                [ {"number": 3, "title": "Claim lost", "html_url": "u3", "body": "x <!-- plus-ones: 4 -->",
                   "labels": [{"name": "area: Claims"}, {"name": "bug"}]},
                  {"number": 5, "title": "A PR", "html_url": "u5", "body": "", "labels": [], "pull_request": {}},
                  {"number": 6, "title": "Fresh", "html_url": "u6", "body": null, "labels": []} ]""");
        List<GitHubIssues.Issue> open = issues(github).open(REPO, "area: Claims");
        assertThat(open).extracting(GitHubIssues.Issue::number).containsExactly(3, 6);
        assertThat(open.getFirst().plusOnes()).isEqualTo(4);
        assertThat(open.getFirst().labels()).contains("bug");
        assertThat(open.get(1).plusOnes()).isZero();
        assertThat(github.seen.getLast()).contains("state=open").contains("labels=area%3A+Claims");
    }

    @Test
    @DisplayName("a new issue is filed with its labels and answers with its number")
    void create() throws Exception {
        FakeGitHub github = signedIn().route("POST /repos/" + REPO + "/issues", 201, "{\"number\": 12}");
        int number = issues(github).create(REPO, "Title", "Body", List.of("bug", "area: Claims"));
        assertThat(number).isEqualTo(12);
        JsonObject sent = JsonParser.parseString(github.bodies.getLast()).getAsJsonObject();
        assertThat(sent.get("title").getAsString()).isEqualTo("Title");
        assertThat(sent.getAsJsonArray("labels")).hasSize(2);
    }

    @Test
    @DisplayName("+1 adds a comment and counts it in the issue; the same player a second time adds nothing")
    void plusOne() throws Exception {
        FakeGitHub github = signedIn()
                .route("GET /repos/" + REPO + "/issues/3/comments", 200,
                        "[{\"body\": \"+1 — also happened to Ada <!-- by: abc -->\"}]")
                .route("GET /repos/" + REPO + "/issues/3", 200, "{\"body\": \"Text <!-- plus-ones: 1 -->\"}")
                .route("POST /repos/" + REPO + "/issues/3/comments", 201, "{}")
                .route("PATCH /repos/" + REPO + "/issues/3", 200, "{}");
        GitHubIssues issues = issues(github);

        assertThat(issues.plusOne(REPO, 3, "abc", "+1")).as("Ada again").isEqualTo(GitHubIssues.PlusOne.ALREADY);
        assertThat(issues.plusOne(REPO, 3, "def", "+1 — also happened to Bo")).isEqualTo(GitHubIssues.PlusOne.ADDED);
        String comment = github.bodies.get(github.seen.indexOf("POST /repos/" + REPO + "/issues/3/comments"));
        assertThat(comment).contains("also happened to Bo").contains("by: def");
        String patched = github.bodies.getLast();
        assertThat(patched).contains("plus-ones: 2").contains("Text");
    }

    @Test
    @DisplayName("GitHub saying no is an exception with its reason, not an empty answer")
    void refusal() throws Exception {
        FakeGitHub github = signedIn().route("POST /repos/" + REPO + "/issues", 403, "{\"message\": \"Forbidden\"}");
        GitHubIssues issues = issues(github);
        assertThatThrownBy(() -> issues.create(REPO, "T", "B", List.of()))
                .isInstanceOf(IOException.class).hasMessageContaining("403").hasMessageContaining("Forbidden");
    }
}

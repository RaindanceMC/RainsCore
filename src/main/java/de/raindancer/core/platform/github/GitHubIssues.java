package de.raindancer.core.platform.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Issues in a repository, as a bug report screen needs them: the open ones of a label, a new one, and "+1, this
 * happened to me too" — a comment, counted in a marker hidden in the issue's text, once per reporter.
 * Blocking — call it off the main thread.
 */
public final class GitHubIssues {

    private static final Pattern PLUS_ONES = Pattern.compile("<!-- plus-ones: (\\d+) -->");

    public record Issue(int number, String title, List<String> labels, int plusOnes, String url) {
    }

    public enum PlusOne { ADDED, ALREADY }

    private final GitHubApp app;

    public GitHubIssues(GitHubApp app) {
        this.app = app;
    }

    /** The open issues carrying {@code label}, newest first; pull requests left out. */
    public List<Issue> open(String repo, String label) throws IOException {
        String path = "/repos/" + repo + "/issues?state=open&per_page=50&labels="
                + URLEncoder.encode(label, StandardCharsets.UTF_8);
        JsonArray found = JsonParser.parseString(app.call(repo, "GET", path, null).body()).getAsJsonArray();
        List<Issue> issues = new ArrayList<>();
        for (JsonElement element : found) {
            JsonObject issue = element.getAsJsonObject();
            if (issue.has("pull_request")) {
                continue;
            }
            List<String> labels = new ArrayList<>();
            for (JsonElement l : issue.getAsJsonArray("labels")) {
                labels.add(l.getAsJsonObject().get("name").getAsString());
            }
            issues.add(new Issue(issue.get("number").getAsInt(), issue.get("title").getAsString(), List.copyOf(labels),
                    plusOnes(text(issue, "body")), text(issue, "html_url")));
        }
        return issues;
    }

    /** @return the new issue's number */
    public int create(String repo, String title, String body, List<String> labels) throws IOException {
        JsonObject issue = new JsonObject();
        issue.addProperty("title", title);
        issue.addProperty("body", body);
        JsonArray names = new JsonArray();
        labels.forEach(names::add);
        issue.add("labels", names);
        String made = app.call(repo, "POST", "/repos/" + repo + "/issues", issue.toString()).body();
        return JsonParser.parseString(made).getAsJsonObject().get("number").getAsInt();
    }

    /**
     * "This happened to me too." {@code who} is a key for the reporter that does not say who they are, so the same
     * one is never counted twice.
     */
    public PlusOne plusOne(String repo, int number, String who, String comment) throws IOException {
        String by = "<!-- by: " + who + " -->";
        JsonArray comments = JsonParser.parseString(app.call(repo, "GET",
                "/repos/" + repo + "/issues/" + number + "/comments?per_page=100", null).body()).getAsJsonArray();
        for (JsonElement existing : comments) {
            if (text(existing.getAsJsonObject(), "body").contains(by)) {
                return PlusOne.ALREADY;
            }
        }
        JsonObject added = new JsonObject();
        added.addProperty("body", comment + "\n\n" + by);
        app.call(repo, "POST", "/repos/" + repo + "/issues/" + number + "/comments", added.toString());

        JsonObject issue = JsonParser.parseString(app.call(repo, "GET", "/repos/" + repo + "/issues/" + number, null)
                .body()).getAsJsonObject();
        String body = text(issue, "body");
        int count = plusOnes(body) + 1;
        Matcher marker = PLUS_ONES.matcher(body);
        String counted = marker.find() ? marker.replaceFirst("<!-- plus-ones: " + count + " -->")
                : body + "\n\n<!-- plus-ones: " + count + " -->";
        JsonObject edit = new JsonObject();
        edit.addProperty("body", counted);
        app.call(repo, "PATCH", "/repos/" + repo + "/issues/" + number, edit.toString());
        return PlusOne.ADDED;
    }

    private static int plusOnes(String body) {
        Matcher marker = PLUS_ONES.matcher(body);
        return marker.find() ? Integer.parseInt(marker.group(1)) : 0;
    }

    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}

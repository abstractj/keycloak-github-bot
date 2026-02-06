package org.keycloak.gh.bot.jira;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

@ApplicationScoped
public class JiraAdapter {

    private static final Logger LOG = Logger.getLogger(JiraAdapter.class);

    @ConfigProperty(name = "jira.url")
    Optional<String> jiraUrl;

    @ConfigProperty(name = "jira.pat")
    Optional<String> jiraToken;

    private final HttpClient client;

    public JiraAdapter() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record JiraIssue(String key, String summary, String description) {}

    public Optional<JiraIssue> findIssueByCve(String cveId) {
        if (jiraUrl.isEmpty() || jiraToken.isEmpty()) {
            LOG.warn("Jira integration is disabled. Missing 'jira.url' or 'jira.pat' configuration.");
            return Optional.empty();
        }

        try {
            // 1. Construct JQL
            String jql = String.format(
                    "project IN (RHBK, RHSSO) AND status IN (Open, Reopened, New, Review) AND (summary ~ \"%s\" OR description ~ \"%s\")",
                    cveId, cveId
            );

            // 2. Build URL with maxResults=1
            String encodedJql = URLEncoder.encode(jql, StandardCharsets.UTF_8);
            URI uri = URI.create(jiraUrl.get() + "/rest/api/2/search?maxResults=1&fields=summary,description&jql=" + encodedJql);

            // 3. Send Request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", "Bearer " + jiraToken.get())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.errorf("Jira API error. Status: %d, Body: %s", response.statusCode(), response.body());
                return Optional.empty();
            }

            return parseResponse(response.body());

        } catch (IOException | InterruptedException e) {
            LOG.error("Failed to fetch issue from Jira", e);
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private Optional<JiraIssue> parseResponse(String jsonBody) {
        try {
            JsonObject root = JsonParser.parseString(jsonBody).getAsJsonObject();
            JsonArray issues = root.getAsJsonArray("issues");

            if (issues == null || issues.size() == 0) {
                return Optional.empty();
            }

            // We only requested 1 result, so take the first
            JsonObject issue = issues.get(0).getAsJsonObject();
            String key = issue.get("key").getAsString();

            JsonObject fields = issue.getAsJsonObject("fields");
            String summary = getSafeString(fields, "summary");
            String description = getSafeString(fields, "description");

            return Optional.of(new JiraIssue(key, summary, description));

        } catch (Exception e) {
            LOG.error("Failed to parse Jira JSON response", e);
            return Optional.empty();
        }
    }

    private String getSafeString(JsonObject obj, String memberName) {
        JsonElement element = obj.get(memberName);
        return (element != null && !element.isJsonNull()) ? element.getAsString() : "";
    }
}
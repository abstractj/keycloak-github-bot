package org.keycloak.gh.bot.security.jira;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.keycloak.gh.bot.jira.JiraAdapter.JiraIssue;
import org.keycloak.gh.bot.security.common.Constants;
import org.keycloak.gh.bot.security.common.GitHubAdapter;
import org.kohsuke.github.GHIssue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class JiraProcessor {

    private static final Logger LOG = Logger.getLogger(JiraProcessor.class);
    private static final Pattern CVE_PATTERN = Pattern.compile("CVE-\\d{4}-\\d+");
    private static final String DESCRIPTION_HEADER = "## Description\n\n";

    @Inject GitHubAdapter github;
    @Inject
    org.keycloak.gh.bot.jira.JiraAdapter jira;
    @Inject
    org.keycloak.gh.bot.jira.JiraIssueParser parser;

    public void processJiraUpdates() {
        if (github.isAccessDenied()) return;

        try {
            // 1. Get all Open Security Issues from GitHub
            List<GHIssue> openIssues = github.getOpenCveIssues();

            for (GHIssue issue : openIssues) {
                syncIssueWithJira(issue);
            }
        } catch (Exception e) {
            LOG.error("Failed to process Jira updates", e);
        }
    }

    private void syncIssueWithJira(GHIssue issue) {
        String title = issue.getTitle();
        if (title == null) return;

        // 2. Extract CVE ID from GitHub Title
        Matcher matcher = CVE_PATTERN.matcher(title);
        if (!matcher.find()) return; // Skip if no CVE ID found

        String cveId = matcher.group();
        LOG.debugf("Checking Jira for %s (Issue #%d)", cveId, issue.getNumber());

        // 3. Search Jira
        Optional<JiraIssue> jiraMatch = jira.findIssueByCve(cveId);

        if (jiraMatch.isPresent()) {
            JiraIssue jiraIssue = jiraMatch.get();
            try {
                updateGithubIssue(issue, cveId, jiraIssue);
            } catch (IOException e) {
                LOG.errorf(e, "Failed to update GitHub issue #%d with Jira data", issue.getNumber());
            }
        }
    }

    private void updateGithubIssue(GHIssue issue, String cveId, JiraIssue jiraIssue) throws IOException {
        // 4. Parse & Clean Data
        String newTitle = parser.parseTitle(jiraIssue.summary());
        String extractedDesc = parser.parseDescription(jiraIssue.description());

        // Apply Header
        String finalBody = DESCRIPTION_HEADER + extractedDesc;

        // Check for changes (handle potential nulls in existing issue body)
        String currentBody = issue.getBody() != null ? issue.getBody() : "";

        boolean titleChanged = !issue.getTitle().equals(newTitle) && !newTitle.isBlank();

        // Only update body if extraction succeeded (not blank) AND content is different
        boolean descChanged = !currentBody.equals(finalBody) && !extractedDesc.isBlank();

        if (titleChanged || descChanged) {
            // Update Title
            if (titleChanged) {
                issue.setTitle(newTitle);
            }

            // Update Body (Description)
            if (descChanged) {
                issue.setBody(finalBody);
            }

            // Ensure label exists
            issue.addLabels(Constants.KIND_CVE);

            LOG.infof("🔄 Synced #%d from Jira %s. TitleUpdated=%s, BodyUpdated=%s",
                    issue.getNumber(), jiraIssue.key(), titleChanged, descChanged);
        }
    }
}
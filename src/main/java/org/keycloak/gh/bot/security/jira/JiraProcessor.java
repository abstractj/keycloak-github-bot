package org.keycloak.gh.bot.security.jira;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.keycloak.gh.bot.jira.JiraAdapter.JiraIssue;
import org.keycloak.gh.bot.security.common.Constants;
import org.keycloak.gh.bot.security.common.GitHubAdapter;
import org.keycloak.gh.bot.utils.Labels;
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
            // Fetch only 'kind/cve' issues that are NOT yet synced
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

        Matcher matcher = CVE_PATTERN.matcher(title);
        if (!matcher.find()) return;

        String cveId = matcher.group();
        LOG.debugf("Checking Jira for %s (Issue #%d)", cveId, issue.getNumber());

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
        // Parse & Clean Data
        String newTitle = parser.parseTitle(jiraIssue.summary());
        String extractedDesc = parser.parseDescription(jiraIssue.description());

        // Add Markdown header
        String finalBody = DESCRIPTION_HEADER + extractedDesc;

        String currentBody = issue.getBody() != null ? issue.getBody() : "";

        // Detect changes
        boolean titleChanged = !issue.getTitle().equals(newTitle) && !newTitle.isBlank();
        boolean descChanged = !currentBody.equals(finalBody) && !extractedDesc.isBlank();

        if (titleChanged || descChanged) {
            if (titleChanged) {
                issue.setTitle(newTitle);
            }
            if (descChanged) {
                issue.setBody(finalBody);
            }

            // Ensure Kind/CVE label exists
            issue.addLabels(Constants.KIND_CVE);

            // Mark as Synced so we don't query it again (Optimization)
            issue.addLabels(Labels.STATUS_JIRA_SYNCED);

            LOG.infof("🔄 Synced #%d from Jira %s. TitleUpdated=%s, BodyUpdated=%s, Marked as %s",
                    issue.getNumber(), jiraIssue.key(), titleChanged, descChanged, Labels.STATUS_JIRA_SYNCED);
        }
    }
}
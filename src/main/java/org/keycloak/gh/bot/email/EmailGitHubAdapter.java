package org.keycloak.gh.bot.email;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.keycloak.gh.bot.GitHubInstallationProvider;
import org.jboss.logging.Logger;
import org.kohsuke.github.*;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class EmailGitHubAdapter {

    private static final Logger LOG = Logger.getLogger(EmailGitHubAdapter.class);

    @Inject
    GitHubInstallationProvider gitHubProvider;

    private GitHub getGitHub() {
        return gitHubProvider.getGitHub();
    }

    private GHRepository getRepository() throws IOException {
        String fullRepoName = gitHubProvider.getRepositoryFullName();
        if (fullRepoName == null) {
            throw new IllegalStateException("Repository name is null.");
        }
        return getGitHub().getRepository(fullRepoName);
    }

    public GHIssue createIssue(String subject, String body) {
        try {
            GHIssue issue = getRepository().createIssue(subject).body(body).create();
            LOG.infof("🆕 Created Issue #%d: %s", issue.getNumber(), subject);
            return issue;
        } catch (Exception e) {
            LOG.error("Failed to create issue", e);
            throw new RuntimeException("Failed to create GitHub issue", e);
        }
    }

    public void commentOnIssue(GHIssue issue, String commentBody) {
        try {
            issue.comment(commentBody);
            LOG.infof("💬 Added comment to Issue #%d", issue.getNumber());
        } catch (Exception e) {
            LOG.error("Failed to comment on issue", e);
        }
    }

    /**
     * Searches for an issue containing the Gmail Thread ID.
     * @return Optional containing the issue if found, empty otherwise.
     */
    public Optional<GHIssue> findIssueByThreadId(String threadId) {
        try {
            String repoName = gitHubProvider.getRepositoryFullName();
            if (repoName == null) return Optional.empty();

            String query = String.format("repo:%s \"%s\" in:comments type:issue", repoName, threadId);
            LOG.debugf("🔍 Searching for thread in comments: [%s]", query);

            PagedSearchIterable<GHIssue> issues = getGitHub().searchIssues().q(query).list();

            if (issues.getTotalCount() > 0) {
                return Optional.of(issues.iterator().next());
            }
        } catch (Exception e) {
            LOG.warnf("Search failed for thread %s: %s", threadId, e.getMessage());
        }
        return Optional.empty();
    }

    public List<GHIssue> getOpenIssues() {
        try {
            return getRepository().getIssues(GHIssueState.OPEN);
        } catch (Exception e) {
            LOG.error("Failed to fetch open issues", e);
            return Collections.emptyList();
        }
    }
}
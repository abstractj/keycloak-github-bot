package org.keycloak.gh.bot.email;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.keycloak.gh.bot.GitHubInstallationProvider;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.PagedSearchIterable;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class GitHubAdapter {

    private static final Logger LOG = Logger.getLogger(GitHubAdapter.class);

    @Inject
    GitHubInstallationProvider gitHubProvider;

    private GHRepository getRepository() throws IOException {
        String fullRepoName = gitHubProvider.getRepositoryFullName();
        if (fullRepoName == null) throw new IllegalStateException("Repository name is null.");
        return gitHubProvider.getGitHub().getRepository(fullRepoName);
    }

    public GHIssue createIssue(String subject, String body) {
        try {
            GHIssue issue = getRepository().createIssue(subject).body(body).create();
            LOG.infof("🆕 Created Issue #%d", issue.getNumber());
            return issue;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create GitHub issue", e);
        }
    }

    public void commentOnIssue(GHIssue issue, String commentBody) {
        try {
            issue.comment(commentBody);
            LOG.debugf("💬 Commented on Issue #%d", issue.getNumber());
        } catch (Exception e) {
            LOG.error("Failed to comment on issue", e);
        }
    }

    public Optional<GHIssue> findIssueByThreadId(String threadId) {
        try {
            String repoName = gitHubProvider.getRepositoryFullName();
            // Using search is expensive (rate limit ~30 req/min).
            // Since we batch email processing to max 20 per 60s, this is safe.
            String query = String.format("repo:%s \"%s\" in:comments type:issue", repoName, threadId);
            PagedSearchIterable<GHIssue> issues = gitHubProvider.getGitHub().searchIssues().q(query).list();
            if (issues.getTotalCount() > 0) {
                return Optional.of(issues.iterator().next());
            }
        } catch (Exception e) {
            LOG.warnf("Search failed for thread %s: %s", threadId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Efficiently fetches only issues that have changed since the provided date.
     * This saves API quota compared to fetching all open issues.
     */
    public List<GHIssue> getIssuesUpdatedSince(Date since) {
        try {
            return getRepository().queryIssues()
                    .state(GHIssueState.OPEN)
                    .since(since)
                    .list()
                    .toList();
        } catch (Exception e) {
            LOG.error("Failed to fetch updated issues", e);
            return List.of();
        }
    }
}
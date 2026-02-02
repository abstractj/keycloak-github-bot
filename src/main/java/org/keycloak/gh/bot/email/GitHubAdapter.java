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

/**
 * Wraps the GitHub API client to provide domain-specific methods for the bot.
 */
@ApplicationScoped
public class GitHubAdapter {

    private static final Logger LOG = Logger.getLogger(GitHubAdapter.class);

    @Inject
    GitHubInstallationProvider gitHubProvider;

    private GHRepository cachedRepository;

    private GHRepository getRepository() throws IOException {
        if (cachedRepository != null) {
            return cachedRepository;
        }
        String fullRepoName = gitHubProvider.getRepositoryFullName();
        if (fullRepoName == null) throw new IllegalStateException("Repository name is null.");

        cachedRepository = gitHubProvider.getGitHub().getRepository(fullRepoName);
        return cachedRepository;
    }

    public GHIssue createIssue(String subject, String body) throws IOException {
        GHIssue issue = getRepository().createIssue(subject).body(body).create();
        LOG.debugf("🆕 Created Issue #%d", issue.getNumber());
        return issue;
    }

    public void commentOnIssue(GHIssue issue, String commentBody) throws IOException {
        issue.comment(commentBody);
        LOG.debugf("💬 Commented on Issue #%d", issue.getNumber());
    }

    public Optional<GHIssue> findIssueByThreadId(String threadId) throws IOException {
        String repoName = gitHubProvider.getRepositoryFullName();
        String query = String.format("repo:%s \"%s\" in:comments type:issue", repoName, threadId);

        PagedSearchIterable<GHIssue> issues = gitHubProvider.getGitHub().searchIssues().q(query).list();
        if (issues.getTotalCount() > 0) {
            return Optional.of(issues.iterator().next());
        }
        return Optional.empty();
    }

    public List<GHIssue> getIssuesUpdatedSince(Date since) throws IOException {
        return getRepository().queryIssues()
                .state(GHIssueState.OPEN)
                .since(since)
                .list()
                .toList();
    }
}
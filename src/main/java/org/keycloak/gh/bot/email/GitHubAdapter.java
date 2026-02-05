package org.keycloak.gh.bot.email;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
 * Acts as a Security Gatekeeper to ensure operations only occur on the allowed repository.
 */
@ApplicationScoped
public class GitHubAdapter {

    private static final Logger LOG = Logger.getLogger(GitHubAdapter.class);

    @Inject
    GitHubInstallationProvider gitHubProvider;

    @ConfigProperty(name = "keycloak.security.repository")
    String allowedRepository;

    private GHRepository cachedRepository;

    /**
     * Ensure that the GitHub installation is NOT bound to the configured secure repository.
     * Prevents the leakage of information to the upstream.
     */
    public boolean isAccessDenied() {
        String currentRepo = gitHubProvider.getRepositoryFullName();
        boolean denied = currentRepo == null || !currentRepo.equalsIgnoreCase(allowedRepository);

        if (denied) {
            LOG.debugf("SECURITY: Access denied. Current repository '%s' does not match allowed repository '%s'.",
                    currentRepo, allowedRepository);
        }
        return denied;
    }

    private GHRepository getRepository() throws IOException {
        if (cachedRepository != null) {
            return cachedRepository;
        }

        if (isAccessDenied()) {
            throw new IllegalStateException("Operation aborted: Bot is not connected to the allowed repository.");
        }

        String fullRepoName = gitHubProvider.getRepositoryFullName();
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
        // Double check repository before searching
        if (isAccessDenied()) return Optional.empty();

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
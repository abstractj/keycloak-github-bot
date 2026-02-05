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
 * Provides scoped repository access and issue management.
 */
@ApplicationScoped
public class GitHubAdapter {

    private static final Logger LOG = Logger.getLogger(GitHubAdapter.class);

    @Inject
    GitHubInstallationProvider gitHubProvider;

    @ConfigProperty(name = "keycloak.security.repository")
    String allowedRepository;

    public boolean isAccessDenied() {
        String currentRepo = gitHubProvider.getRepositoryFullName();
        boolean denied = currentRepo == null || !currentRepo.equalsIgnoreCase(allowedRepository);
        if (denied) {
            LOG.debugf("SECURITY: Access denied. Repository mismatch: %s != %s", currentRepo, allowedRepository);
        }
        return denied;
    }

    private GHRepository getRepository() throws IOException {
        if (isAccessDenied()) {
            throw new IllegalStateException("Bot is not connected to the allowed repository.");
        }
        return gitHubProvider.getGitHub().getRepository(gitHubProvider.getRepositoryFullName());
    }

    public GHIssue createSecurityIssue(String subject) throws IOException {
        return createIssue(subject, EmailConstants.ISSUE_DESCRIPTION_TEMPLATE);
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
        if (isAccessDenied()) return Optional.empty();
        String repoName = gitHubProvider.getRepositoryFullName();
        String query = String.format("repo:%s \"%s\" in:comments type:issue", repoName, threadId);
        PagedSearchIterable<GHIssue> issues = gitHubProvider.getGitHub().searchIssues().q(query).list();
        for (GHIssue issue : issues) {
            return Optional.of(issue);
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
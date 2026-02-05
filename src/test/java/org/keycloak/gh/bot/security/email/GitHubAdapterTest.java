package org.keycloak.gh.bot.security.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.gh.bot.GitHubInstallationProvider;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueSearchBuilder;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterator;
import org.kohsuke.github.PagedSearchIterable;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verification for GitHub API search query construction.
 */
public class GitHubAdapterTest {

    org.keycloak.gh.bot.security.common.GitHubAdapter gitHubAdapter;
    GitHubInstallationProvider mockInstallationProvider;

    @BeforeEach
    public void setup() {
        gitHubAdapter = new org.keycloak.gh.bot.security.common.GitHubAdapter();
        mockInstallationProvider = mock(GitHubInstallationProvider.class);
        gitHubAdapter.gitHubProvider = mockInstallationProvider;
    }

    @Test
    public void testFindIssueQueryStructure() throws IOException {
        String repoName = "keycloak/keycloak-private";
        gitHubAdapter.allowedRepository = repoName;
        GitHub mockGitHub = mock(GitHub.class);
        GHIssueSearchBuilder mockSearch = mock(GHIssueSearchBuilder.class);
        PagedSearchIterable<GHIssue> mockIterable = mock(PagedSearchIterable.class);
        PagedIterator<GHIssue> mockIterator = mock(PagedIterator.class);

        when(mockInstallationProvider.getRepositoryFullName()).thenReturn(repoName);
        when(mockInstallationProvider.getGitHub()).thenReturn(mockGitHub);
        when(mockGitHub.searchIssues()).thenReturn(mockSearch);
        when(mockSearch.q(anyString())).thenReturn(mockSearch);
        when(mockSearch.list()).thenReturn(mockIterable);
        when(mockIterable.iterator()).thenReturn(mockIterator);
        when(mockIterator.hasNext()).thenReturn(false);

        gitHubAdapter.findIssueByThreadId("abc");

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockSearch).q(queryCaptor.capture());
        assertTrue(queryCaptor.getValue().contains("repo:" + repoName));
    }
}
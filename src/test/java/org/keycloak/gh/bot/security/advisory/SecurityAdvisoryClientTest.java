package org.keycloak.gh.bot.security.advisory;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.InstallationTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GitHub;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Unit tests for SecurityAdvisoryClient verifying HTTP request construction and response parsing. */
class SecurityAdvisoryClientTest {

    private SecurityAdvisoryClient client;
    private HttpClient httpClient;
    private GitHubClientProvider gitHubClientProvider;
    private InstallationTokenProvider installationTokenProvider;

    @BeforeEach
    void setUp() throws Exception {
        client = new SecurityAdvisoryClient();
        httpClient = mock(HttpClient.class);
        gitHubClientProvider = mock(GitHubClientProvider.class);
        installationTokenProvider = mock(InstallationTokenProvider.class);

        setField(client, "httpClient", httpClient);
        setField(client, "gitHubClientProvider", gitHubClientProvider);
        setField(client, "installationTokenProvider", installationTokenProvider);
        setField(client, "mainRepository", "keycloak-poc/keycloak");

        setupInstallationMocks();
    }

    @Test
    void createDraftAdvisory_returnsGhsaIdOnSuccess() throws Exception {
        mockHttpResponse(201, """
                {"ghsa_id": "GHSA-abcd-efgh-ijkl", "html_url": "https://github.com/keycloak-poc/keycloak/security/advisories/GHSA-abcd-efgh-ijkl"}
                """);

        Optional<SecurityAdvisoryClient.AdvisoryResult> result =
                client.createDraftAdvisory("XSS in admin console", 42, "keycloak-poc/keycloak-private");

        assertTrue(result.isPresent());
        assertEquals("GHSA-abcd-efgh-ijkl", result.get().ghsaId());
        assertEquals("https://github.com/keycloak-poc/keycloak/security/advisories/GHSA-abcd-efgh-ijkl", result.get().htmlUrl());
    }

    @Test
    void createDraftAdvisory_sendsCorrectAuthHeader() throws Exception {
        mockHttpResponse(201, """
                {"ghsa_id": "GHSA-1234-5678-abcd", "html_url": "https://example.com"}
                """);

        client.createDraftAdvisory("Test", 1, "keycloak-poc/keycloak-private");

        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());

        HttpRequest request = captor.getValue();
        assertTrue(request.headers().firstValue("Authorization").orElse("").startsWith("Bearer "));
        assertEquals("application/vnd.github+json", request.headers().firstValue("Accept").orElse(""));
    }

    @Test
    void createDraftAdvisory_sendsCorrectUrl() throws Exception {
        mockHttpResponse(201, """
                {"ghsa_id": "GHSA-1234-5678-abcd", "html_url": "https://example.com"}
                """);

        client.createDraftAdvisory("Test", 1, "keycloak-poc/keycloak-private");

        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());

        HttpRequest request = captor.getValue();
        assertEquals("https://api.github.com/repos/keycloak-poc/keycloak/security-advisories", request.uri().toString());
        assertEquals("POST", request.method());
    }

    @Test
    void createDraftAdvisory_returnsEmptyOnHttpError() throws Exception {
        mockHttpResponse(422, """
                {"message": "Validation Failed"}
                """);

        Optional<SecurityAdvisoryClient.AdvisoryResult> result =
                client.createDraftAdvisory("Test", 42, "keycloak-poc/keycloak-private");

        assertTrue(result.isEmpty());
    }

    @Test
    void createDraftAdvisory_returnsEmptyOnForbidden() throws Exception {
        mockHttpResponse(403, """
                {"message": "Resource not accessible by integration"}
                """);

        Optional<SecurityAdvisoryClient.AdvisoryResult> result =
                client.createDraftAdvisory("Test", 42, "keycloak-poc/keycloak-private");

        assertTrue(result.isEmpty());
    }

    @Test
    void createDraftAdvisory_returnsEmptyOnNetworkFailure() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new IOException("Connection refused"));

        Optional<SecurityAdvisoryClient.AdvisoryResult> result =
                client.createDraftAdvisory("Test", 42, "keycloak-poc/keycloak-private");

        assertTrue(result.isEmpty());
    }

    @Test
    void createDraftAdvisory_returnsEmptyOnMissingGhsaId() throws Exception {
        mockHttpResponse(201, """
                {"html_url": "https://example.com"}
                """);

        Optional<SecurityAdvisoryClient.AdvisoryResult> result =
                client.createDraftAdvisory("Test", 42, "keycloak-poc/keycloak-private");

        assertTrue(result.isEmpty());
    }

    @Test
    void createDraftAdvisory_returnsEmptyOnMalformedJson() throws Exception {
        mockHttpResponse(201, "not-json");

        Optional<SecurityAdvisoryClient.AdvisoryResult> result =
                client.createDraftAdvisory("Test", 42, "keycloak-poc/keycloak-private");

        assertTrue(result.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private void mockHttpResponse(int statusCode, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    private void setupInstallationMocks() throws Exception {
        GitHub gitHub = mock(GitHub.class);
        GHApp ghApp = mock(GHApp.class);
        GHAppInstallation ghAppInstallation = createGHAppInstallationWithId(12345L);

        when(gitHubClientProvider.getApplicationClient()).thenReturn(gitHub);
        when(gitHub.getApp()).thenReturn(ghApp);
        when(ghApp.getInstallationByRepository(anyString(), anyString())).thenReturn(ghAppInstallation);

        InstallationTokenProvider.InstallationToken token = mock(InstallationTokenProvider.InstallationToken.class);
        when(installationTokenProvider.getInstallationToken(anyLong())).thenReturn(token);
        when(token.token()).thenReturn("ghs_test_token_123");
    }

    private static GHAppInstallation createGHAppInstallationWithId(long id) throws Exception {
        GHAppInstallation installation = new GHAppInstallation();
        Field idField = org.kohsuke.github.GHObject.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.setLong(installation, id);
        return installation;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}

package org.keycloak.gh.bot.security.advisory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.InstallationTokenProvider;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates draft GitHub Security Advisories via the REST API.
 */
@ApplicationScoped
public class SecurityAdvisoryClient {

    private static final Logger LOGGER = Logger.getLogger(SecurityAdvisoryClient.class);
    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String ECOSYSTEM = "maven";
    private static final String PACKAGE_NAME = "org.keycloak:keycloak-core";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    GitHubClientProvider gitHubClientProvider;

    @Inject
    InstallationTokenProvider installationTokenProvider;

    @ConfigProperty(name = "repository.mainRepository")
    String mainRepository;

    HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newHttpClient();
    }

    public Optional<AdvisoryResult> createDraftAdvisory(String summary, int issueNumber,
            String sourceRepository, VulnerabilityReport report) {
        try {
            String token = resolveInstallationToken();

            Map<String, Object> payload = buildPayload(summary, issueNumber, sourceRepository, report);

            String body = MAPPER.writeValueAsString(payload);
            String url = "%s/repos/%s/security-advisories".formatted(GITHUB_API_BASE, mainRepository);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.errorf("Failed to create security advisory (HTTP %d): %s", response.statusCode(), response.body());
                return Optional.empty();
            }

            JsonNode node = MAPPER.readTree(response.body());
            String ghsaId = node.path("ghsa_id").asText(null);
            String htmlUrl = node.path("html_url").asText(null);

            if (ghsaId == null || ghsaId.isBlank()) {
                LOGGER.error("Security advisory created but response missing ghsa_id");
                return Optional.empty();
            }

            LOGGER.infof("Created draft security advisory %s for %s#%d: %s", ghsaId, sourceRepository, issueNumber, htmlUrl);
            return Optional.of(new AdvisoryResult(ghsaId, htmlUrl));
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to create security advisory for issue #%d", issueNumber);
            return Optional.empty();
        }
    }

    private Map<String, Object> buildPayload(String summary, int issueNumber,
            String sourceRepository, VulnerabilityReport report) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("summary", summary);

        String description = report != null && report.summary() != null
                ? report.summary()
                : "Migrated from %s#%d".formatted(sourceRepository, issueNumber);
        payload.put("description", description);

        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("ecosystem", ECOSYSTEM);
        pkg.put("name", report != null && report.packageName() != null ? report.packageName() : PACKAGE_NAME);

        Map<String, Object> vulnerability = new HashMap<>(Map.of("package", pkg));
        if (report != null && report.vulnerableVersionRange() != null) {
            vulnerability.put("vulnerable_version_range", report.vulnerableVersionRange());
        }
        payload.put("vulnerabilities", List.of(vulnerability));

        if (report != null && report.cvssVector() != null) {
            payload.put("cvss_vector_string", report.cvssVector());
        }

        if (report != null && report.cweIds() != null && !report.cweIds().isEmpty()) {
            payload.put("cwe_ids", report.cweIds());
        }

        return payload;
    }

    private String resolveInstallationToken() throws IOException {
        int slashIndex = mainRepository.indexOf('/');
        String owner = mainRepository.substring(0, slashIndex);
        String repo = mainRepository.substring(slashIndex + 1);

        long installationId = gitHubClientProvider.getApplicationClient()
                .getApp()
                .getInstallationByRepository(owner, repo)
                .getId();

        return installationTokenProvider.getInstallationToken(installationId).token();
    }

    public record AdvisoryResult(String ghsaId, String htmlUrl) {
    }
}

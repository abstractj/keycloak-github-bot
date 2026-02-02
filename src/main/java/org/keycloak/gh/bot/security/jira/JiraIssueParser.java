package org.keycloak.gh.bot.security.jira;

import jakarta.enterprise.context.ApplicationScoped;
import org.keycloak.gh.bot.security.common.Constants;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class JiraIssueParser {

    private static final Pattern BUILD_SUFFIX = Pattern.compile("\\[.*?\\]$");

    // Generic Prefix Regex:
    // Matches any sequence of chars (alphanumeric, dash, dot, slash) ending in a colon followed by whitespace.
    // Examples: "Keycloak: ", "rhbk/keycloak-rhel9: "
    private static final Pattern GENERIC_PREFIX = Pattern.compile("^([\\w\\-\\.\\/]+):\\s+");

    // Description extraction markers
    private static final Pattern FLAW_START = Pattern.compile("(?i)^Flaw(?: Description)?:?\\s*$");
    private static final Pattern FLAW_END = Pattern.compile("(?i)^(?:Additional notes|Mitigation|Statement|References).*");

    public String parseTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) return "";

        // 1. Extract CVE ID using centralized constant
        Matcher cveMatcher = Constants.CVE_PATTERN.matcher(rawTitle);
        String cveId = cveMatcher.find() ? cveMatcher.group() : "";

        // 2. Remove CVE ID and "EMBARGOED" noise
        String cleanText = rawTitle.replace(cveId, "")
                .replaceAll("(?i)EMBARGOED", "")
                .trim();

        // 3. Remove leading non-word chars (like ": " or "- ") left over
        cleanText = cleanText.replaceAll("^[\\W_]+", "");

        // --- Safeguard: Keep a backup of the text before aggressive stripping ---
        String backupText = cleanText;

        // 4. Recursively remove Product Prefixes
        Matcher prefixMatcher = GENERIC_PREFIX.matcher(cleanText);
        while (prefixMatcher.find()) {
            cleanText = prefixMatcher.replaceFirst("").trim();
            prefixMatcher = GENERIC_PREFIX.matcher(cleanText);
        }

        // Fallback: If stripping resulted in empty text, revert to the backup
        if (cleanText.isEmpty()) {
            cleanText = backupText;
        }

        // 5. Remove Suffixes like [rhbk-26.4]
        cleanText = BUILD_SUFFIX.matcher(cleanText).replaceAll("").trim();

        // Final fallback if suffix removal killed it (unlikely, but safe)
        if (cleanText.isEmpty()) {
            cleanText = backupText;
        }

        // 6. Reconstruct Title
        return cveId + " " + cleanText;
    }

    public String parseDescription(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) return "";

        String[] lines = rawDescription.split("\\R");
        StringBuilder flawContent = new StringBuilder();
        boolean insideFlaw = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (!insideFlaw && FLAW_START.matcher(trimmed).matches()) {
                insideFlaw = true;
                continue;
            }

            if (insideFlaw && FLAW_END.matcher(trimmed).matches()) {
                break;
            }

            if (insideFlaw) {
                // Heuristic: Skip the first line ONLY if it looks like a repeated title (Product: Title)
                // We use GENERIC_PREFIX to identify lines starting with "Keycloak: ", "RHBK: ", etc.
                if (flawContent.isEmpty() && GENERIC_PREFIX.matcher(trimmed).lookingAt()) {
                    continue;
                }
                flawContent.append(line).append("\n");
            }
        }

        return flawContent.toString().trim();
    }
}
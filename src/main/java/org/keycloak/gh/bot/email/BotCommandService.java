package org.keycloak.gh.bot.email;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.keycloak.gh.bot.GitHubInstallationProvider;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class BotCommandService {

    @Inject
    GitHubInstallationProvider gitHubProvider;

    private static final String CMD_REPLY_ALL = "reply-all";
    private static final String CMD_EMAIL_SECALERT = "e-mail-secalert";

    // Pattern to extract optional "Subject" in quotes and the rest as body
    private static final Pattern SUBJECT_BODY_PATTERN = Pattern.compile("^(?:\"([^\"]+)\")?\\s*(.*)", Pattern.DOTALL);

    public boolean isReplyAllCommand(String text) {
        return hasCommand(text, CMD_REPLY_ALL);
    }

    public boolean isEmailSecAlertCommand(String text) {
        return hasCommand(text, CMD_EMAIL_SECALERT);
    }

    public String extractReplyContent(String text) {
        return extractContent(text, CMD_REPLY_ALL);
    }

    public EmailRequest extractSecAlertData(String text) {
        String rawContent = extractContent(text, CMD_EMAIL_SECALERT);
        if (rawContent.isEmpty()) return null;

        Matcher matcher = SUBJECT_BODY_PATTERN.matcher(rawContent);
        if (matcher.find()) {
            return new EmailRequest(matcher.group(1), matcher.group(2).trim());
        }
        return new EmailRequest(null, rawContent.trim());
    }

    /**
     * Returns the "human-friendly" bot name.
     * If GitHub reports "anxiety42-bot[bot]", this returns "anxiety42-bot".
     */
    public String getBotName() {
        String login = gitHubProvider.getBotLogin();
        if (login != null && login.endsWith("[bot]")) {
            return login.replace("[bot]", "");
        }
        return login;
    }

    private boolean hasCommand(String text, String command) {
        if (text == null) return false;
        // Case-insensitive check for @botname command
        String pattern = "(?i)@" + Pattern.quote(getBotName()) + "\\s+" + Pattern.quote(command);
        return Pattern.compile(pattern).matcher(text).find();
    }

    private String extractContent(String text, String command) {
        // Matches the same pattern to find where the content starts
        String patternStr = "(?i)@" + Pattern.quote(getBotName()) + "\\s+" + Pattern.quote(command);
        Pattern p = Pattern.compile(patternStr);
        Matcher m = p.matcher(text);
        if (m.find()) {
            return text.substring(m.end()).trim();
        }
        return "";
    }

    public record EmailRequest(String subject, String body) {}
}
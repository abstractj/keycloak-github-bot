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

    public String getBotName() {
        return gitHubProvider.getBotLogin();
    }

    private boolean hasCommand(String text, String command) {
        return text != null && text.contains("@" + getBotName() + " " + command);
    }

    private String extractContent(String text, String command) {
        String trigger = "@" + getBotName() + " " + command;
        int index = text.indexOf(trigger);
        if (index == -1) return "";
        return text.substring(index + trigger.length()).trim();
    }

    public record EmailRequest(String subject, String body) {}
}
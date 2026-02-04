package org.keycloak.gh.bot.email;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.keycloak.gh.bot.GitHubInstallationProvider;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class CommandParser {

    @Inject
    GitHubInstallationProvider gitHubProvider;

    public enum CommandType {
        REPLY_KEYCLOAK_SECURITY,
        NEW_SECALERT,
        UNKNOWN
    }

    public record Command(CommandType type, Optional<String> subject, String body) {}

    public Optional<Command> parse(String text) {
        if (text == null || text.isBlank()) return Optional.empty();

        String botName = Pattern.quote(getBotName());
        String trimmedText = text.trim();

        // Check for bot mention at start of any line
        Pattern mentionStartOfLine = Pattern.compile("(?mi)^@" + botName + "\\b");
        if (!mentionStartOfLine.matcher(trimmedText).find()) {
            return Optional.empty();
        }

        // 1. /reply keycloak-security
        Pattern replySecurity = Pattern.compile("(?msi)^@" + botName + "\\s+/reply\\s+keycloak-security\\s*[\r\n]+(.*)");
        Matcher mSecurity = replySecurity.matcher(trimmedText);
        if (mSecurity.find()) {
            String body = mSecurity.group(1).trim();
            return body.isEmpty() ? Optional.empty() : Optional.of(new Command(CommandType.REPLY_KEYCLOAK_SECURITY, Optional.empty(), body));
        }

        // 2. /new secalert "Subject"
        Pattern newSecAlert = Pattern.compile("(?msi)^@" + botName + "\\s+/new\\s+secalert\\s+\"([^\"]+)\"\\s*[\r\n]+(.*)");
        Matcher mSecAlert = newSecAlert.matcher(trimmedText);
        if (mSecAlert.find()) {
            String subject = mSecAlert.group(1).trim();
            String body = mSecAlert.group(2).trim();
            if (subject.isEmpty() || body.isEmpty()) return Optional.empty();
            return Optional.of(new Command(CommandType.NEW_SECALERT, Optional.of(subject), body));
        }

        // 3. Fallback: Unknown
        return Optional.of(new Command(CommandType.UNKNOWN, Optional.empty(), trimmedText));
    }

    public String getBotName() {
        String login = gitHubProvider.getBotLogin();
        if (login == null) return "unknown-bot";
        return login.endsWith("[bot]") ? login.replace("[bot]", "") : login;
    }

    public String getHelpMessage() {
        String n = getBotName();
        return String.format("""
            I don't know this command or the format is incorrect.
            **Rule:** Commands must be on their own line. The message body starts on the next line.
            
            **Available Commands:**
            
            `@%s /reply keycloak-security`
            `REPLY BODY (Sent to: Sender + Keycloak Security List)`
            
            `@%s /new secalert "Subject"`
            `EMAIL BODY (Sent to: SecAlert + Keycloak Security List)`
            """, n, n);
    }
}
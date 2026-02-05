package org.keycloak.gh.bot.security.common;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.keycloak.gh.bot.GitHubInstallationProvider;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses GitHub comment commands and enforces security-specific subject rules.
 */
@ApplicationScoped
public class CommandParser {

    private static final Pattern COMMAND_PATTERN = Pattern.compile("^@(\\w+(?:-\\w+)?)\\s+/(new|reply)\\s+(\\w+)(?:\\s+\"([^\"]+)\")?(?:\\s+(.*))?", Pattern.DOTALL);

    @Inject
    GitHubInstallationProvider gitHubProvider;

    private String botName;

    @PostConstruct
    public void init() {
        this.botName = gitHubProvider.getBotLogin();
    }

    public String getBotName() {
        return botName;
    }

    public String getHelpMessage() {
        return Constants.HELP_MESSAGE;
    }

    public Optional<Command> parse(String body) {
        if (body == null || body.isBlank()) return Optional.empty();

        Matcher matcher = COMMAND_PATTERN.matcher(body.trim());
        if (matcher.find() && matcher.group(1).equalsIgnoreCase(botName)) {
            String action = matcher.group(2);
            String target = matcher.group(3);
            String subject = matcher.group(4);
            String content = matcher.group(5);

            if ("new".equals(action) && "secalert".equals(target)) {
                String prefixedSubject = Constants.CVE_TBD_PREFIX + " " + (subject != null ? subject : "No Subject");
                return Optional.of(new Command(CommandType.NEW_SECALERT, Optional.of(prefixedSubject), content));
            }

            if ("reply".equals(action) && "keycloak-security".equals(target)) {
                return Optional.of(new Command(CommandType.REPLY_KEYCLOAK_SECURITY, Optional.empty(), content));
            }

            return Optional.of(new Command(CommandType.UNKNOWN, Optional.empty(), null));
        }
        return Optional.empty();
    }

    public enum CommandType {
        NEW_SECALERT, REPLY_KEYCLOAK_SECURITY, UNKNOWN
    }

    public record Command(CommandType type, Optional<String> subject, String body) {}
}
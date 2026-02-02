package org.keycloak.gh.bot.security.common;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class CommandParser {

    @ConfigProperty(name = "quarkus.github-app.app-name", defaultValue = "keycloak-bot")
    String botName;

    // Regex anchor updated implicit in matches() call
    private static final Pattern CMD_NEW_SECALERT = Pattern.compile("^/new\\s+secalert\\s+\"([^\"]+)\"");
    private static final Pattern CMD_REPLY_KEYCLOAK = Pattern.compile("^/reply\\s+keycloak-security");
    private static final Pattern CMD_REPLY_SECALERT = Pattern.compile("^/reply\\s+secalert");

    public enum CommandType {
        NEW_SECALERT,
        REPLY_KEYCLOAK_SECURITY,
        REPLY_SECALERT,
        UNKNOWN
    }

    public record Command(CommandType type, Optional<String> argument, String payload) {}

    public Optional<Command> parse(String commentBody) {
        if (commentBody == null || commentBody.isBlank()) {
            return Optional.empty();
        }

        var lines = commentBody.split("\\R", 2);
        var firstLine = lines[0].trim();

        if (!firstLine.startsWith("@" + botName)) {
            return Optional.empty();
        }

        var commandText = firstLine.substring(("@" + botName).length()).trim();
        var payload = lines.length > 1 ? lines[1].trim() : "";

        var newMatcher = CMD_NEW_SECALERT.matcher(commandText);

        // FIX: Changed from .find() to .matches() to enforce exact matching and reject trailing junk
        if (newMatcher.matches()) {
            return Optional.of(new Command(CommandType.NEW_SECALERT, Optional.of(newMatcher.group(1)), payload));
        }

        if (CMD_REPLY_KEYCLOAK.matcher(commandText).matches()) {
            return Optional.of(new Command(CommandType.REPLY_KEYCLOAK_SECURITY, Optional.empty(), payload));
        }

        if (CMD_REPLY_SECALERT.matcher(commandText).matches()) {
            return Optional.of(new Command(CommandType.REPLY_SECALERT, Optional.empty(), payload));
        }

        return Optional.of(new Command(CommandType.UNKNOWN, Optional.empty(), payload));
    }

    public String getHelpMessage() {
        return Constants.HELP_MESSAGE;
    }

    public String getBotName() {
        return botName;
    }
}
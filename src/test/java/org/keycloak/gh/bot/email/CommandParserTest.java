package org.keycloak.gh.bot.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.gh.bot.GitHubInstallationProvider;
import org.keycloak.gh.bot.email.CommandParser.Command;
import org.keycloak.gh.bot.email.CommandParser.CommandType;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verification for raw text command parsing.
 */
public class CommandParserTest {

    private CommandParser parser;

    @BeforeEach
    public void setup() {
        parser = new CommandParser();
        parser.gitHubProvider = mock(GitHubInstallationProvider.class);
        when(parser.gitHubProvider.getBotLogin()).thenReturn("keycloak-bot");
        parser.init();
    }

    @Test
    public void testReplyParsing() {
        String text = "@keycloak-bot /reply keycloak-security\nBody content.";
        Optional<Command> cmd = parser.parse(text);
        assertTrue(cmd.isPresent());
        assertEquals(CommandType.REPLY_KEYCLOAK_SECURITY, cmd.get().type());
        assertEquals("Body content.", cmd.get().body());
    }

    @Test
    public void testNewSecAlertParsing() {
        String text = "@keycloak-bot /new secalert \"Subject\"\nDetails.";
        Optional<Command> cmd = parser.parse(text);
        assertTrue(cmd.isPresent());
        assertEquals("Subject", cmd.get().subject().get());
    }

    @Test
    public void testUnknownCommandParsing() {
        String text = "@keycloak-bot /unknown command";
        Optional<Command> cmd = parser.parse(text);
        assertTrue(cmd.isPresent());
        assertEquals(CommandType.UNKNOWN, cmd.get().type());
    }

    @Test
    public void testIgnoreNonMention() {
        assertTrue(parser.parse("No mention here").isEmpty());
    }
}
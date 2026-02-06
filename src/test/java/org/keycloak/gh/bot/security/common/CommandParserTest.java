package org.keycloak.gh.bot.security.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.gh.bot.GitHubInstallationProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CommandParser within the common security package.
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
    public void testNewSecAlertPrefixesTbd() {
        String input = "@keycloak-bot /new secalert \"Vulnerability\"\nContent.";
        Optional<CommandParser.Command> cmd = parser.parse(input);

        assertTrue(cmd.isPresent());
        assertEquals(CommandParser.CommandType.NEW_SECALERT, cmd.get().type());
        assertEquals("CVE-TBD Vulnerability", cmd.get().subject().get());
    }

    @Test
    public void testReplyParsing() {
        String input = "@keycloak-bot /reply keycloak-security\nResponse.";
        Optional<CommandParser.Command> cmd = parser.parse(input);

        assertTrue(cmd.isPresent());
        assertEquals(CommandParser.CommandType.REPLY_KEYCLOAK_SECURITY, cmd.get().type());
        assertEquals("Response.", cmd.get().body());
    }

    @Test
    public void testUnknownCommand() {
        String input = "@keycloak-bot /unknown command";
        Optional<CommandParser.Command> cmd = parser.parse(input);

        assertTrue(cmd.isPresent());
        // Ensure CommandParser.java has UNKNOWN in CommandType enum
        assertEquals(CommandParser.CommandType.UNKNOWN, cmd.get().type());
    }

    @Test
    public void testIgnoreNonMention() {
        assertTrue(parser.parse("No mention here").isEmpty());
    }
}
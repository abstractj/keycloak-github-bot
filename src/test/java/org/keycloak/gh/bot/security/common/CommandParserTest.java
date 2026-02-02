package org.keycloak.gh.bot.security.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandParserTest {

    private CommandParser parser;

    @BeforeEach
    public void setup() {
        parser = new CommandParser();
        // Manually inject the configuration property for the test
        parser.botName = "keycloak-bot";
    }

    @Test
    public void testNewSecAlertValid() {
        String input = "@keycloak-bot /new secalert \"RCE in Admin Console\"\n\nFound a vulnerability.";
        Optional<CommandParser.Command> cmd = parser.parse(input);

        assertTrue(cmd.isPresent());
        assertEquals(CommandParser.CommandType.NEW_SECALERT, cmd.get().type());

        // Updated field names based on the new Record definition
        assertEquals("RCE in Admin Console", cmd.get().argument().get());
        assertEquals("Found a vulnerability.", cmd.get().payload());
    }

    @Test
    public void testReplyValid() {
        String input = "@keycloak-bot /reply keycloak-security\n\nThis is a reply";
        Optional<CommandParser.Command> cmd = parser.parse(input);

        assertTrue(cmd.isPresent());
        assertEquals(CommandParser.CommandType.REPLY_KEYCLOAK_SECURITY, cmd.get().type());
        assertEquals("This is a reply", cmd.get().payload());
    }

    @Test
    public void testReplyWithJunkOnCommandLineFails() {
        String input = "@keycloak-bot /reply keycloak-security meh";
        Optional<CommandParser.Command> cmd = parser.parse(input);

        assertTrue(cmd.isPresent());
        // The parser now defaults to UNKNOWN if regex doesn't match cleanly
        assertEquals(CommandParser.CommandType.UNKNOWN, cmd.get().type());
    }

    @Test
    public void testNewSecAlertWithJunkOnCommandLineFails() {
        String input = "@keycloak-bot /new secalert \"Subject\" meh";
        Optional<CommandParser.Command> cmd = parser.parse(input);

        assertTrue(cmd.isPresent());
        assertEquals(CommandParser.CommandType.UNKNOWN, cmd.get().type());
    }

    @Test
    public void testIgnoreNonMention() {
        assertTrue(parser.parse("No mention here").isEmpty());
    }
}
package org.keycloak.gh.bot.security.email;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies auto-reply message templates load correctly and contain expected content. */
public class AutoReplyMessagesTest {

    @Test
    void getMessage_returnsNonNullForIssueResolved() {
        AutoReplyMessages messages = new AutoReplyMessages();
        String result = messages.getMessage(AutoReplyType.ISSUE_RESOLVED);
        assertNotNull(result);
    }

    @Test
    void getMessage_containsExpectedContent() {
        AutoReplyMessages messages = new AutoReplyMessages();
        String result = messages.getMessage(AutoReplyType.ISSUE_RESOLVED);
        assertTrue(result.contains("resolved"));
        assertTrue(result.contains("Keycloak Security Team"));
    }

}

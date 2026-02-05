package org.keycloak.gh.bot.security.email;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verification for email construction and threading headers.
 */
@QuarkusTest
public class MailSenderTest {

    @Inject
    org.keycloak.gh.bot.security.email.MailSender mailSender;
    @InjectMock
    org.keycloak.gh.bot.security.email.GmailAdapter gmailAdapter;
    @ConfigProperty(name = "gmail.user.email") String botEmail;

    @Test
    public void testReplyFindsLastHumanMessage() throws Exception {
        Message humanMsg = createMsg("msg-human", "human@example.com");
        Message botMsg = createMsg("msg-bot", botEmail);
        com.google.api.services.gmail.model.Thread thread = new com.google.api.services.gmail.model.Thread().setMessages(List.of(humanMsg, botMsg));

        when(gmailAdapter.getThread("t1")).thenReturn(thread);
        when(gmailAdapter.getHeadersMap(any())).thenCallRealMethod();

        mailSender.sendReply("t1", "Re: Sub", "Body", null);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(gmailAdapter).sendMessage(eq("t1"), captor.capture());
        assertEquals("msg-human", captor.getValue().getHeader("In-Reply-To", null));
    }

    private Message createMsg(String id, String from) {
        return new Message().setPayload(new MessagePart().setHeaders(List.of(
                new MessagePartHeader().setName("Message-ID").setValue(id),
                new MessagePartHeader().setName("From").setValue(from))));
    }
}
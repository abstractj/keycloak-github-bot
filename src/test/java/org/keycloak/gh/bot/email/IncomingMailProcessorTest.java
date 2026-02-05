package org.keycloak.gh.bot.email;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.gh.bot.utils.Labels;
import org.keycloak.gh.bot.utils.Throttler;
import org.kohsuke.github.GHIssue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verification for the incoming email processing logic and GitHub issue creation.
 */
@QuarkusTest
public class IncomingMailProcessorTest {

    @Inject IncomingMailProcessor incomingMailProcessor;
    @InjectMock GmailAdapter gmailAdapter;
    @InjectMock GitHubAdapter githubAdapter;
    @InjectMock Throttler throttler;
    @ConfigProperty(name = "google.group.target") String targetGroup;

    private static final String THREAD_ID = "123456789abcdef";

    @BeforeEach
    public void setup() throws IOException {
        when(githubAdapter.isAccessDenied()).thenReturn(false);
        when(gmailAdapter.fetchUnreadMessages(anyString())).thenReturn(Collections.emptyList());
        when(gmailAdapter.getBody(any())).thenCallRealMethod();
        when(gmailAdapter.getHeadersMap(any())).thenCallRealMethod();
    }

    @Test
    public void testNewThreadCreatesIssue() throws IOException {
        Message message = createMockMessage("Vulnerability", "Body content");
        when(gmailAdapter.fetchUnreadMessages(anyString())).thenReturn(List.of(message));
        when(gmailAdapter.getMessage(message.getId())).thenReturn(message);
        when(githubAdapter.findIssueByThreadId(THREAD_ID)).thenReturn(Optional.empty());

        GHIssue mockIssue = mock(GHIssue.class);
        when(mockIssue.getNumber()).thenReturn(101);
        when(githubAdapter.createIssue(eq("Vulnerability"), anyString())).thenReturn(mockIssue);

        incomingMailProcessor.processUnreadEmails();

        verify(githubAdapter).createIssue(eq("Vulnerability"), anyString());
        verify(mockIssue).addLabels(Labels.STATUS_TRIAGE);
        verify(gmailAdapter).markAsRead(message.getId());
        verify(throttler).throttle(any());
    }

    @Test
    public void testReplyAppendsComment() throws IOException {
        Message message = createMockMessage( "Re: New Vuln", "More details here.");
        when(gmailAdapter.fetchUnreadMessages(anyString())).thenReturn(List.of(message));
        when(gmailAdapter.getMessage(message.getId())).thenReturn(message);

        GHIssue existingIssue = mock(GHIssue.class);
        when(githubAdapter.findIssueByThreadId(THREAD_ID)).thenReturn(Optional.of(existingIssue));

        incomingMailProcessor.processUnreadEmails();

        verify(githubAdapter).commentOnIssue(eq(existingIssue), contains("More details here."));
        verify(gmailAdapter).markAsRead(message.getId());
        verify(throttler).throttle(any());
    }

    @Test
    public void testIgnoresIfRepoAccessDenied() throws IOException {
        when(githubAdapter.isAccessDenied()).thenReturn(true);
        incomingMailProcessor.processUnreadEmails();
        verify(gmailAdapter, never()).fetchUnreadMessages(anyString());
    }

    private Message createMockMessage(String subject, String body) {
        Message msg = new Message().setId(UUID.randomUUID().toString()).setThreadId(THREAD_ID);
        MessagePart payload = new MessagePart();
        List<MessagePartHeader> headers = new ArrayList<>();
        headers.add(new MessagePartHeader().setName("Subject").setValue(subject));
        headers.add(new MessagePartHeader().setName("From").setValue("user@test.com"));
        headers.add(new MessagePartHeader().setName("To").setValue(targetGroup));
        headers.add(new MessagePartHeader().setName("List-ID").setValue("<" + targetGroup.replace("@", ".") + ">"));
        payload.setHeaders(headers);
        payload.setMimeType("text/plain");
        payload.setBody(new MessagePartBody().setData(Base64.getUrlEncoder().encodeToString(body.getBytes())));
        msg.setPayload(payload);
        return msg;
    }
}
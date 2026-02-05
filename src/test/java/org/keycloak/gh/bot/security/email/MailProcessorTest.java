package org.keycloak.gh.bot.security.email;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
public class MailProcessorTest {

    @Inject
    MailProcessor processor;
    @InjectMock GmailAdapter gmailAdapter;
    @InjectMock
    org.keycloak.gh.bot.security.common.GitHubAdapter githubAdapter;
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
    public void testNewThreadCreatesIssueWithSourceLabel() throws IOException {
        Message message = createMockMessage(THREAD_ID, "Vulnerability", "Body content", "user@test.com");
        when(gmailAdapter.fetchUnreadMessages(anyString())).thenReturn(List.of(message));
        when(gmailAdapter.getMessage(message.getId())).thenReturn(message);
        when(githubAdapter.findOpenEmailIssueByThreadId(THREAD_ID)).thenReturn(Optional.empty());

        GHIssue mockIssue = mock(GHIssue.class);
        when(mockIssue.getNumber()).thenReturn(101);
        when(githubAdapter.createSecurityIssue(eq("Vulnerability"), anyString(), eq(org.keycloak.gh.bot.security.common.Constants.SOURCE_EMAIL))).thenReturn(mockIssue);

        processor.processUnreadEmails();

        verify(githubAdapter).createSecurityIssue(eq("Vulnerability"), anyString(), eq(org.keycloak.gh.bot.security.common.Constants.SOURCE_EMAIL));
        verify(mockIssue).addLabels(Labels.STATUS_TRIAGE);
        verify(gmailAdapter).markAsRead(message.getId());
    }

    @Test
    public void testRedHatCveUpdate() throws IOException {
        Message message = createMockMessage(THREAD_ID, "Re: Vuln", "Fixed in CVE-2026-1518", org.keycloak.gh.bot.security.common.Constants.REDHAT_SECALERT_SENDER);
        when(gmailAdapter.fetchUnreadMessages(anyString())).thenReturn(List.of(message));
        when(gmailAdapter.getMessage(message.getId())).thenReturn(message);

        GHIssue existingIssue = mock(GHIssue.class);
        when(existingIssue.getTitle()).thenReturn("CVE-TBD Vulnerability");
        when(githubAdapter.findOpenEmailIssueByThreadId(THREAD_ID)).thenReturn(Optional.of(existingIssue));

        processor.processUnreadEmails();

        verify(githubAdapter).updateTitleAndLabels(existingIssue, "CVE-2026-1518 Vulnerability", org.keycloak.gh.bot.security.common.Constants.KIND_CVE);
    }

    private Message createMockMessage(String threadId, String subject, String body, String from) {
        Message msg = new Message().setId(UUID.randomUUID().toString()).setThreadId(threadId);
        MessagePart payload = new MessagePart();
        List<MessagePartHeader> headers = new ArrayList<>();
        headers.add(new MessagePartHeader().setName("Subject").setValue(subject));
        headers.add(new MessagePartHeader().setName("From").setValue(from));
        headers.add(new MessagePartHeader().setName("To").setValue(targetGroup));
        headers.add(new MessagePartHeader().setName("List-ID").setValue("<" + targetGroup.replace("@", ".") + ">"));
        payload.setHeaders(headers);
        payload.setMimeType("text/plain");
        payload.setBody(new MessagePartBody().setData(Base64.getUrlEncoder().encodeToString(body.getBytes())));
        msg.setPayload(payload);
        return msg;
    }
}
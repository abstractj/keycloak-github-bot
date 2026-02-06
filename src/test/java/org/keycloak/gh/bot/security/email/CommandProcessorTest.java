package org.keycloak.gh.bot.security.email;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.gh.bot.security.common.CommandParser;
import org.keycloak.gh.bot.security.common.Constants;
import org.keycloak.gh.bot.security.common.GitHubAdapter;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueCommentQueryBuilder;
import org.kohsuke.github.GHReaction;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedIterator;
import org.kohsuke.github.ReactionContent;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
public class CommandProcessorTest {

    @Inject CommandProcessor commandProcessor;
    @InjectMock MailSender mailSender;
    @InjectMock GitHubAdapter githubAdapter;
    @InjectMock CommandParser commandParser;

    @ConfigProperty(name = "google.group.target") String targetGroup;
    @ConfigProperty(name = "email.target.secalert") String secAlertEmail;
    @ConfigProperty(name = "quarkus.application.name") String botName;

    @BeforeEach
    public void setup() throws IOException {
        when(commandParser.getBotName()).thenReturn(botName);
        when(githubAdapter.getIssuesUpdatedSince(any())).thenReturn(Collections.emptyList());
        when(commandParser.parse(anyString())).thenReturn(Optional.empty());
    }

    @Test
    public void testNewSecAlertUpdatesTitleAndSendsEmail() throws IOException {
        GHIssue issue = mock(GHIssue.class);
        GHIssueComment comment = mockComment("1");
        String commandText = "/new secalert";
        when(comment.getBody()).thenReturn(commandText);

        when(commandParser.parse(eq(commandText))).thenReturn(Optional.of(new CommandParser.Command(CommandParser.CommandType.NEW_SECALERT, Optional.of("Zero day"), "Body")));

        mockQueryComments(issue, List.of(comment));
        when(githubAdapter.getIssuesUpdatedSince(any())).thenReturn(List.of(issue));

        when(mailSender.sendNewEmail(anyString(), anyString(), anyString(), anyString())).thenReturn("123456abcdef");

        commandProcessor.processCommands();

        verify(mailSender).sendNewEmail(eq(secAlertEmail), eq(targetGroup), eq("Zero day"), eq("Body"));
        verify(githubAdapter).updateTitleAndLabels(issue, Constants.CVE_TBD_PREFIX + " Zero day", null);
        verify(comment).createReaction(ReactionContent.EYES);
        verify(githubAdapter).commentOnIssue(issue, "✅ SecAlert email sent. " + Constants.SECALERT_THREAD_ID_PREFIX + " 123456abcdef");
    }

    @Test
    public void testReplyKeycloakSecurityUsesThreadId() throws IOException {
        GHIssue issue = mock(GHIssue.class);
        String validHexId = "abc123456";

        GHIssueComment markerComment = mockComment("2");
        when(markerComment.getBody()).thenReturn("Some text " + Constants.GMAIL_THREAD_ID_PREFIX + " " + validHexId);

        GHIssueComment cmdComment = mockComment("3");
        String cmdText = "/reply keycloak-security";
        when(cmdComment.getBody()).thenReturn(cmdText);

        when(commandParser.parse(eq(cmdText))).thenReturn(Optional.of(new CommandParser.Command(CommandParser.CommandType.REPLY_KEYCLOAK_SECURITY, Optional.empty(), "Reply Body")));

        // Note: We include both comments in the "Recent" list for simplicity in testing
        mockQueryComments(issue, List.of(markerComment, cmdComment));
        when(githubAdapter.getIssuesUpdatedSince(any())).thenReturn(List.of(issue));

        when(mailSender.sendReply(anyString(), anyString(), anyString())).thenReturn(true);

        commandProcessor.processCommands();

        verify(mailSender).sendReply(eq(validHexId), eq("Reply Body"), eq(targetGroup));
        verify(cmdComment).createReaction(ReactionContent.EYES);
    }

    @Test
    public void testReplySecAlertUsesSecAlertThreadId() throws IOException {
        GHIssue issue = mock(GHIssue.class);
        String validHexId = "deadbeef123";

        GHIssueComment markerComment = mockComment("4");
        when(markerComment.getBody()).thenReturn("Info " + Constants.SECALERT_THREAD_ID_PREFIX + " " + validHexId);

        GHIssueComment cmdComment = mockComment("5");
        String cmdText = "/reply secalert";
        when(cmdComment.getBody()).thenReturn(cmdText);

        when(commandParser.parse(eq(cmdText))).thenReturn(Optional.of(new CommandParser.Command(CommandParser.CommandType.REPLY_SECALERT, Optional.empty(), "Sec Reply")));

        mockQueryComments(issue, List.of(markerComment, cmdComment));
        when(githubAdapter.getIssuesUpdatedSince(any())).thenReturn(List.of(issue));

        when(mailSender.sendThreadedEmail(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        commandProcessor.processCommands();

        verify(mailSender).sendThreadedEmail(eq(validHexId), eq(secAlertEmail), eq(targetGroup), eq("Sec Reply"));
        verify(cmdComment).createReaction(ReactionContent.EYES);
    }

    private void mockQueryComments(GHIssue issue, List<GHIssueComment> comments) throws IOException {
        GHIssueCommentQueryBuilder queryBuilder = mock(GHIssueCommentQueryBuilder.class);
        PagedIterable<GHIssueComment> pagedIterable = mock(PagedIterable.class);
        PagedIterator<GHIssueComment> pagedIterator = mock(PagedIterator.class);

        // Mock the basic queryComments() call (used for full fetch)
        when(issue.queryComments()).thenReturn(queryBuilder);
        when(queryBuilder.list()).thenReturn(pagedIterable);

        // Mock the .since() call chain (used for recent fetch)
        when(queryBuilder.since(any(Date.class))).thenReturn(queryBuilder);

        // Iterators
        when(pagedIterable.iterator()).thenReturn(pagedIterator);
        when(pagedIterable.toList()).thenReturn(comments);
        java.util.Iterator<GHIssueComment> realIterator = comments.iterator();
        when(pagedIterator.hasNext()).thenAnswer(i -> realIterator.hasNext());
        when(pagedIterator.next()).thenAnswer(i -> realIterator.next());
    }

    private GHIssueComment mockComment(String idSuffix) throws IOException {
        GHIssueComment comment = mock(GHIssueComment.class);
        when(comment.getId()).thenReturn(new Random().nextLong());
        when(comment.getCreatedAt()).thenReturn(new Date());
        when(comment.getBody()).thenReturn("");
        GHUser user = mock(GHUser.class);
        when(user.getLogin()).thenReturn("tester");
        when(comment.getUser()).thenReturn(user);
        PagedIterable<GHReaction> reactions = mock(PagedIterable.class);
        PagedIterator<GHReaction> iterator = mock(PagedIterator.class);
        when(iterator.hasNext()).thenReturn(false);
        when(reactions.iterator()).thenReturn(iterator);
        when(comment.listReactions()).thenReturn(reactions);
        return comment;
    }
}
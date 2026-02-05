package org.keycloak.gh.bot.email;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.gh.bot.email.CommandParser.Command;
import org.keycloak.gh.bot.email.CommandParser.CommandType;
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
    }

    @Test
    public void testNewSecAlertSuccess() throws IOException {
        GHIssue issue = mock(GHIssue.class);
        GHIssueComment comment = mockComment();
        when(commandParser.parse(anyString())).thenReturn(Optional.of(new Command(CommandType.NEW_SECALERT, Optional.of("CVE-123"), "Alert body.")));
        mockQueryComments(issue, List.of(comment));
        when(githubAdapter.getIssuesUpdatedSince(any())).thenReturn(List.of(issue));
        when(mailSender.sendNewEmail(eq(secAlertEmail), eq(targetGroup), anyString(), anyString())).thenReturn(true);

        commandProcessor.processCommands();

        verify(mailSender).sendNewEmail(eq(secAlertEmail), eq(targetGroup), eq("CVE-123"), eq("Alert body."));
        verify(comment).createReaction(ReactionContent.EYES);
    }

    @Test
    public void testReplyKeycloakSecuritySuccess() throws IOException {
        GHIssue issue = mock(GHIssue.class);
        when(issue.getTitle()).thenReturn("Security Thread");
        GHIssueComment comment = mockComment();
        when(commandParser.parse(anyString())).thenReturn(Optional.of(new Command(CommandType.REPLY_KEYCLOAK_SECURITY, Optional.empty(), "Fixed.")));
        when(comment.getBody()).thenReturn("**Gmail-Thread-ID:** abc123def");
        mockQueryComments(issue, List.of(comment));
        when(githubAdapter.getIssuesUpdatedSince(any())).thenReturn(List.of(issue));
        when(mailSender.sendReply(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        commandProcessor.processCommands();

        verify(mailSender).sendReply(eq("abc123def"), eq("Security Thread"), eq("Fixed."), eq(targetGroup));
    }

    private void mockQueryComments(GHIssue issue, List<GHIssueComment> comments) throws IOException {
        GHIssueCommentQueryBuilder queryBuilder = mock(GHIssueCommentQueryBuilder.class);
        PagedIterable<GHIssueComment> pagedIterable = mock(PagedIterable.class);
        PagedIterator<GHIssueComment> pagedIterator = mock(PagedIterator.class);
        when(issue.queryComments()).thenReturn(queryBuilder);
        when(queryBuilder.list()).thenReturn(pagedIterable);
        when(pagedIterable.iterator()).thenReturn(pagedIterator);
        when(pagedIterable.toList()).thenReturn(comments);
        java.util.Iterator<GHIssueComment> realIterator = comments.iterator();
        when(pagedIterator.hasNext()).thenAnswer(i -> realIterator.hasNext());
        when(pagedIterator.next()).thenAnswer(i -> realIterator.next());
    }

    private GHIssueComment mockComment() throws IOException {
        GHIssueComment comment = mock(GHIssueComment.class);
        when(comment.getId()).thenReturn(new Random().nextLong());
        when(comment.getCreatedAt()).thenReturn(new Date()); // Fixes NPE
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
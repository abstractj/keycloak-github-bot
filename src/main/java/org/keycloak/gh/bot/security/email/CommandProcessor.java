package org.keycloak.gh.bot.security.email;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.keycloak.gh.bot.security.common.CommandParser;
import org.keycloak.gh.bot.security.common.Constants;
import org.keycloak.gh.bot.security.common.GitHubAdapter;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.ReactionContent;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class CommandProcessor {

    private static final Logger LOG = Logger.getLogger(CommandProcessor.class);

    private static final Pattern THREAD_ID_PATTERN = Pattern.compile("Thread-ID:\\*\\*\\s+([a-fA-F0-9]+)");

    @Inject GitHubAdapter github;
    @Inject CommandParser parser;
    @Inject MailSender mailSender;

    @ConfigProperty(name = "email.sender.secalert") String secAlertEmail;
    @ConfigProperty(name = "google.group.target") String targetGroup;

    private final Set<Long> processedComments = new HashSet<>();

    public void processCommands() {
        if (github.isAccessDenied()) return;

        var since = java.sql.Date.from(Instant.now().minus(10, ChronoUnit.MINUTES));
        try {
            var issues = github.getIssuesUpdatedSince(since);
            for (var issue : issues) {
                scanIssue(issue, since);
            }
        } catch (IOException e) {
            LOG.error("Failed to fetch updated issues", e);
        }
    }

    private void scanIssue(GHIssue issue, java.util.Date since) {
        try {
            var comments = issue.queryComments().since(since).list();
            for (var comment : comments) {
                if (hasAlreadyProcessed(comment)) continue;

                var commandOpt = parser.parse(comment.getBody());
                if (commandOpt.isPresent()) {
                    var cmd = commandOpt.get();
                    handleCommand(issue, comment, cmd);
                    markAsProcessed(comment);
                }
            }
        } catch (IOException e) {
            LOG.errorf(e, "Error scanning issue #%d", issue.getNumber());
        }
    }

    private void handleCommand(GHIssue issue, GHIssueComment comment, CommandParser.Command cmd) throws IOException {
        switch (cmd.type()) {
            case NEW_SECALERT -> handleNewSecAlert(issue, cmd);
            case REPLY_KEYCLOAK_SECURITY -> handleReply(issue, cmd, Constants.GMAIL_THREAD_ID_PREFIX, targetGroup);
            case REPLY_SECALERT -> handleReply(issue, cmd, Constants.SECALERT_THREAD_ID_PREFIX, secAlertEmail);
            case UNKNOWN -> github.commentOnIssue(issue, parser.getHelpMessage());
        }
    }

    private void handleNewSecAlert(GHIssue issue, CommandParser.Command cmd) throws IOException {
        if (cmd.argument().isEmpty()) {
            github.commentOnIssue(issue, "❌ Error: Missing Subject argument.\nUsage: `/new secalert \"Subject\" Body`");
            return;
        }

        var subject = cmd.argument().get();
        if (!subject.startsWith(Constants.CVE_TBD_PREFIX)) {
            subject = Constants.CVE_TBD_PREFIX + " " + subject;
        }

        var threadId = mailSender.sendNewEmail(secAlertEmail, targetGroup, subject, cmd.payload());
        if (threadId != null) {
            github.commentOnIssue(issue, "✅ SecAlert email sent. Thread started.\n" + Constants.SECALERT_THREAD_ID_PREFIX + " " + threadId);
            github.updateTitleAndLabels(issue, subject, null);
        } else {
            github.commentOnIssue(issue, "❌ Failed to send email to SecAlert.");
        }
    }

    private void handleReply(GHIssue issue, CommandParser.Command cmd, String prefix, String recipient) throws IOException {
        var threadId = findThreadIdInComments(issue, prefix);
        if (threadId.isPresent()) {
            var success = prefix.equals(Constants.SECALERT_THREAD_ID_PREFIX)
                    ? mailSender.sendThreadedEmail(threadId.get(), recipient, targetGroup, cmd.payload())
                    : mailSender.sendReply(threadId.get(), cmd.payload(), recipient);

            if (success) {
                github.createReaction(issue, reactionContent(true));
            } else {
                github.commentOnIssue(issue, "❌ Failed to send reply.");
            }
        } else {
            github.commentOnIssue(issue, "❌ Could not find a linked email thread for " + prefix);
        }
    }

    private Optional<String> findThreadIdInComments(GHIssue issue, String prefix) throws IOException {
        for (var comment : issue.queryComments().list()) {
            var body = comment.getBody();
            if (body != null && body.contains(prefix)) {
                var matcher = THREAD_ID_PATTERN.matcher(body);
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
        }
        return Optional.empty();
    }

    private boolean hasAlreadyProcessed(GHIssueComment comment) throws IOException {
        if (processedComments.contains(comment.getId())) return true;

        for (var reaction : comment.listReactions()) {
            if (reaction.getUser().getLogin().equals(parser.getBotName()) &&
                    (reaction.getContent() == ReactionContent.ROCKET || reaction.getContent() == ReactionContent.EYES)) {
                processedComments.add(comment.getId());
                return true;
            }
        }
        return false;
    }

    private void markAsProcessed(GHIssueComment comment) throws IOException {
        processedComments.add(comment.getId());
        comment.createReaction(ReactionContent.ROCKET);
    }

    private ReactionContent reactionContent(boolean success) {
        return success ? ReactionContent.ROCKET : ReactionContent.CONFUSED;
    }
}
package org.keycloak.gh.bot.email;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHReaction;
import org.kohsuke.github.ReactionContent;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class CommandProcessor {

    private static final Logger LOG = Logger.getLogger(CommandProcessor.class);
    private static final Pattern VISIBLE_MARKER_PATTERN = Pattern.compile("\\*\\*Gmail-Thread-ID:\\*\\*\\s*([a-f0-9]+)");
    private static final Pattern RAW_HEX_PATTERN = Pattern.compile("\\b([a-f0-9]{16})\\b");

    @ConfigProperty(name = "google.group.target")
    String targetGroup; // This acts as the CC for security alerts

    @ConfigProperty(name = "email.target.secalert")
    String secAlertEmail; // This is the primary TO for security alerts

    @Inject GitHubAdapter github;
    @Inject CommandParser parser;
    @Inject MailSender mailSender;

    private final Set<Long> processedComments = ConcurrentHashMap.newKeySet();
    private Instant lastPollTime = Instant.now().minus(1, ChronoUnit.MINUTES);

    public void processCommands() {
        try {
            String myLogin = parser.getBotName();
            if (myLogin == null || myLogin.isEmpty()) return;

            Instant nextPollTime = Instant.now();
            List<GHIssue> updatedIssues = github.getIssuesUpdatedSince(Date.from(lastPollTime));

            for (GHIssue issue : updatedIssues) {
                scanIssue(issue, myLogin);
            }
            lastPollTime = nextPollTime;
        } catch (Exception e) {
            LOG.error("Error processing GitHub commands", e);
        }
    }

    private void scanIssue(GHIssue issue, String myLogin) throws IOException {
        Optional<String> threadIdOpt = findThreadIdInComments(issue);

        for (GHIssueComment comment : issue.getComments()) {
            if (hasAlreadyProcessed(comment, myLogin)) continue;

            parser.parse(comment.getBody()).ifPresent(cmd -> executeCommand(issue, comment, cmd, threadIdOpt));
        }
    }

    private void executeCommand(GHIssue issue, GHIssueComment comment, CommandParser.Command cmd, Optional<String> threadId) {
        boolean success = false;
        ReactionContent reaction = ReactionContent.EYES; // Default success reaction

        switch (cmd.type()) {
            case NEW_SECALERT:
                // STRICT REQUIREMENT: TO=secAlertEmail, CC=targetGroup
                success = mailSender.sendNewEmail(
                        secAlertEmail,      // TO
                        targetGroup,        // CC
                        cmd.subject().orElse("No Subject"),
                        cmd.body()
                );

                if (!success) {
                    replyWithError(issue, comment, "❌ Error: Failed to send email via Gmail API.");
                    reaction = ReactionContent.CONFUSED;
                }
                break;

            case REPLY_KEYCLOAK_SECURITY:
                if (threadId.isPresent()) {
                    success = mailSender.sendReply(threadId.get(), issue.getTitle(), cmd.body(), targetGroup);
                    if (!success) {
                        replyWithError(issue, comment, "❌ Error: Failed to send email via Gmail API.");
                        reaction = ReactionContent.CONFUSED;
                    }
                } else {
                    replyWithError(issue, comment, "❌ Error: Gmail Thread ID not found in comments.");
                    success = true; // Mark processed to stop loops
                    reaction = ReactionContent.CONFUSED;
                }
                break;

            case UNKNOWN:
                sendHelpMessage(issue, comment);
                success = true;
                reaction = ReactionContent.CONFUSED;
                break;
        }

        if (success) {
            processedComments.add(comment.getId());
            addReaction(comment, reaction);
            LOG.infof("✅ Command executed: %s", cmd.type());
        }
    }

    private void addReaction(GHIssueComment comment, ReactionContent reaction) {
        try {
            comment.createReaction(reaction);
        } catch (IOException e) {
            LOG.errorf("Failed to react to comment %d", comment.getId());
        }
    }

    private void replyWithError(GHIssue issue, GHIssueComment comment, String message) {
        try {
            github.commentOnIssue(issue, "@" + comment.getUser().getLogin() + " " + message);
        } catch (IOException e) {
            LOG.error("Failed to send error reply", e);
        }
    }

    private void sendHelpMessage(GHIssue issue, GHIssueComment comment) {
        try {
            String body = "@" + comment.getUser().getLogin() + " " + parser.getHelpMessage();
            github.commentOnIssue(issue, body);
        } catch (IOException e) {
            LOG.error("Failed to send help message", e);
        }
    }

    private boolean hasAlreadyProcessed(GHIssueComment comment, String myLogin) throws IOException {
        if (processedComments.contains(comment.getId())) return true;
        for (GHReaction reaction : comment.listReactions()) {
            String user = reaction.getUser().getLogin();
            if ((reaction.getContent() == ReactionContent.EYES || reaction.getContent() == ReactionContent.CONFUSED) &&
                    (user.equalsIgnoreCase(myLogin) || user.equalsIgnoreCase(myLogin + "[bot]"))) {
                processedComments.add(comment.getId());
                return true;
            }
        }
        return false;
    }

    private Optional<String> findThreadIdInComments(GHIssue issue) throws IOException {
        for (GHIssueComment comment : issue.getComments()) {
            Matcher m = VISIBLE_MARKER_PATTERN.matcher(comment.getBody());
            if (m.find()) return Optional.of(m.group(1).trim());
            Matcher raw = RAW_HEX_PATTERN.matcher(comment.getBody());
            if (raw.find()) return Optional.of(raw.group(1).trim());
        }
        return Optional.empty();
    }
}
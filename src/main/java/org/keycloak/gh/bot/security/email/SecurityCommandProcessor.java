package org.keycloak.gh.bot.security.email;

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
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies and executes bot commands from GitHub comments.
 */
@ApplicationScoped
public class SecurityCommandProcessor {

    private static final Logger LOG = Logger.getLogger(SecurityCommandProcessor.class);
    private static final Pattern VISIBLE_MARKER_PATTERN = Pattern.compile(Pattern.quote(org.keycloak.gh.bot.security.common.EmailConstants.GMAIL_THREAD_ID_PREFIX) + "\\s*([a-f0-9]+)");
    private static final Pattern RAW_HEX_PATTERN = Pattern.compile("\\b([a-f0-9]{16})\\b");
    private static final int MAX_PROCESSED_HISTORY = 10000;

    @ConfigProperty(name = "google.group.target") String targetGroup;
    @ConfigProperty(name = "email.target.secalert") String secAlertEmail;

    @Inject
    org.keycloak.gh.bot.security.common.GitHubAdapter github;
    @Inject
    org.keycloak.gh.bot.security.common.SecurityCommandParser parser;
    @Inject MailSender mailSender;

    private final Set<Long> processedComments = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<Long, Boolean>(MAX_PROCESSED_HISTORY + 1, .75F, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) { return size() > MAX_PROCESSED_HISTORY; }
            }));

    private Instant lastPollTime = Instant.now().minus(10, ChronoUnit.MINUTES);

    public void processCommands() {
        if (github.isAccessDenied()) return;
        try {
            String myLogin = parser.getBotName();
            if (myLogin == null || myLogin.isEmpty()) return;

            Instant executionStart = Instant.now();
            Date querySince = Date.from(lastPollTime.minus(1, ChronoUnit.MINUTES));
            List<GHIssue> updatedIssues = github.getIssuesUpdatedSince(querySince);

            for (GHIssue issue : updatedIssues) {
                scanIssue(issue, myLogin);
            }
            lastPollTime = executionStart;
        } catch (Exception e) {
            LOG.error("Fatal error fetching updated issues", e);
        }
    }

    private void scanIssue(GHIssue issue, String myLogin) {
        try {
            List<GHIssueComment> allComments = issue.queryComments().list().toList();
            Optional<String> threadIdOpt = findThreadId(allComments);
            Instant threshold = lastPollTime.minus(1, ChronoUnit.MINUTES);

            for (GHIssueComment comment : allComments) {
                if (comment.getCreatedAt() != null && comment.getCreatedAt().toInstant().isBefore(threshold)) continue;
                if (processedComments.contains(comment.getId())) continue;

                parser.parse(comment.getBody()).ifPresent(cmd -> {
                    try {
                        if (hasAlreadyProcessed(comment, myLogin)) return;
                        executeCommand(issue, comment, cmd, threadIdOpt);
                    } catch (IOException e) {
                        LOG.errorf(e, "Error checking reactions for comment %d", comment.getId());
                    }
                });
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to scan issue #%d", issue.getNumber());
        }
    }

    private Optional<String> findThreadId(List<GHIssueComment> comments) {
        for (GHIssueComment comment : comments) {
            String body = comment.getBody();
            if (body == null) continue;
            Matcher m = VISIBLE_MARKER_PATTERN.matcher(body);
            if (m.find()) return Optional.of(m.group(1).trim());
            Matcher raw = RAW_HEX_PATTERN.matcher(body);
            if (raw.find()) return Optional.of(raw.group(1).trim());
        }
        return Optional.empty();
    }

    private void executeCommand(GHIssue issue, GHIssueComment comment, org.keycloak.gh.bot.security.common.SecurityCommandParser.Command cmd, Optional<String> threadId) {
        boolean success = false;
        ReactionContent reaction = ReactionContent.EYES;

        switch (cmd.type()) {
            case NEW_SECALERT -> {
                success = mailSender.sendNewEmail(secAlertEmail, targetGroup, cmd.subject().orElse("No Subject"), cmd.body());
            }
            case REPLY_KEYCLOAK_SECURITY -> {
                if (threadId.isPresent()) {
                    success = mailSender.sendReply(threadId.get(), issue.getTitle(), cmd.body(), targetGroup);
                } else {
                    replyWithError(issue, comment, "❌ Error: Gmail Thread ID not found.");
                    success = true;
                    reaction = ReactionContent.CONFUSED;
                }
            }
            case UNKNOWN -> {
                sendHelpMessage(issue, comment);
                success = true;
                reaction = ReactionContent.CONFUSED;
            }
        }

        if (success) {
            processedComments.add(comment.getId());
            addReaction(comment, reaction);
        } else {
            replyWithError(issue, comment, "❌ Error: Failed to execute command via Gmail API.");
            addReaction(comment, ReactionContent.CONFUSED);
        }
    }

    private void addReaction(GHIssueComment comment, ReactionContent reaction) {
        try { comment.createReaction(reaction); } catch (IOException e) { LOG.error("Failed to react", e); }
    }

    private void replyWithError(GHIssue issue, GHIssueComment comment, String message) {
        try { github.commentOnIssue(issue, "@" + comment.getUser().getLogin() + " " + message); } catch (IOException e) { LOG.error("Failed to reply", e); }
    }

    private void sendHelpMessage(GHIssue issue, GHIssueComment comment) {
        try { github.commentOnIssue(issue, "@" + comment.getUser().getLogin() + " " + parser.getHelpMessage()); } catch (IOException e) { LOG.error("Failed help", e); }
    }

    private boolean hasAlreadyProcessed(GHIssueComment comment, String myLogin) throws IOException {
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
}
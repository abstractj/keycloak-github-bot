package org.keycloak.gh.bot.email;

import com.google.api.services.gmail.model.Message;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.keycloak.gh.bot.utils.Labels;
import org.keycloak.gh.bot.utils.Throttler;
import org.kohsuke.github.GHIssue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls Gmail for unread messages, filters out bot auto-replies to prevent loops,
 * and synchronizes valid emails to GitHub.
 */
@ApplicationScoped
public class IncomingMailProcessor {

    private static final Logger LOG = Logger.getLogger(IncomingMailProcessor.class);
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("(?m)^--\\s*$|^You received this message because you are subscribed.*");

    @ConfigProperty(name = "google.group.target") String targetGroup;
    @ConfigProperty(name = "gmail.user.email") String botEmail;

    @Inject GmailAdapter gmail;
    @Inject GitHubAdapter github;
    @Inject Throttler throttler;

    public void processUnreadEmails() {
        // Security Check: Fail fast if connected to the wrong repo
        if (github.isAccessDenied()) {
            return;
        }

        String query = "is:unread -from:" + botEmail;

        List<Message> messages = gmail.fetchUnreadMessages(query);
        for (Message msgSummary : messages) {
            processMessage(msgSummary);
            throttler.throttle(Duration.ofSeconds(1));
        }
    }

    private void processMessage(Message msgSummary) {
        Message msg = gmail.getMessage(msgSummary.getId());
        if (msg == null) return;

        try {
            Map<String, String> headers = gmail.getHeadersMap(msg);
            String from = headers.getOrDefault("From", "");

            if (isFromBot(from) || !isValidGroupMessage(headers)) {
                gmail.markAsRead(msg.getId());
                return;
            }

            String threadId = msg.getThreadId();
            String subject = headers.getOrDefault("Subject", "");
            String cleanBody = sanitizeBody(gmail.getBody(msg)).orElse("(No content)");

            Optional<GHIssue> existingIssue = github.findIssueByThreadId(threadId);
            if (existingIssue.isPresent()) {
                appendComment(existingIssue.get(), from, cleanBody, threadId);
            } else {
                createNewIssue(threadId, subject, from, cleanBody);
            }

            gmail.markAsRead(msg.getId());

        } catch (IOException e) {
            LOG.warnf("Deferred processing message %s due to API error (Rate Limit?): %s", msg.getId(), e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to process message %s. Marking as read to prevent loop.", msg.getId());
            try {
                gmail.markAsRead(msg.getId());
            } catch (Exception ex) {
                LOG.error("Failed to mark poison message as read", ex);
            }
        }
    }

    private void appendComment(GHIssue issue, String from, String body, String threadId) throws IOException {
        String comment = "**Reply from " + from + ":**\n\n" + body;
        github.commentOnIssue(issue, comment);
        LOG.debugf("✅ Commented on #%d (Thread %s)", issue.getNumber(), threadId);
    }

    private void createNewIssue(String threadId, String subject, String from, String body) throws IOException {
        LOG.debugf("🤖 Creating Issue for Thread %s", threadId);
        GHIssue newIssue = github.createIssue(subject, EmailConstants.ISSUE_DESCRIPTION_TEMPLATE);
        if (newIssue != null) {
            try {
                newIssue.addLabels(Labels.STATUS_TRIAGE);
            } catch (IOException e) {
                LOG.errorf(e, "Failed to label issue #%d", newIssue.getNumber());
            }
            String firstComment = EmailConstants.GMAIL_THREAD_ID_PREFIX + " " + threadId + "\n**From:** " + from + "\n\n" + body;
            github.commentOnIssue(newIssue, firstComment);
        }
    }

    private boolean isFromBot(String from) {
        return from != null && from.toLowerCase().contains(botEmail.toLowerCase());
    }

    private boolean isValidGroupMessage(Map<String, String> headers) {
        String listId = headers.get("List-ID");
        String groupIdentifier = targetGroup.split("@")[0];
        if (listId != null && listId.contains(groupIdentifier)) return true;

        String to = headers.get("To");
        String cc = headers.get("Cc");
        return (to != null && to.contains(targetGroup)) || (cc != null && cc.contains(targetGroup));
    }

    private Optional<String> sanitizeBody(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        Matcher matcher = SIGNATURE_PATTERN.matcher(body);
        if (matcher.find()) {
            String trimmed = body.substring(0, matcher.start()).trim();
            return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
        }
        return Optional.of(body.trim());
    }
}
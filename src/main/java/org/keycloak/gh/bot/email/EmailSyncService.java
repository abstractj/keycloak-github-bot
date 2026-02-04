package org.keycloak.gh.bot.email;

import com.google.api.services.gmail.model.Message;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.keycloak.gh.bot.utils.Labels;
import org.kohsuke.github.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class EmailSyncService {

    private static final Logger LOG = Logger.getLogger(EmailSyncService.class);

    private static final String VISIBLE_MARKER_PREFIX = "**Gmail-Thread-ID:** ";
    private static final Pattern VISIBLE_MARKER_PATTERN = Pattern.compile("\\*\\*Gmail-Thread-ID:\\*\\*\\s*([a-f0-9]+)");
    private static final Pattern RAW_HEX_PATTERN = Pattern.compile("\\b([a-f0-9]{16})\\b");
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("(?m)^--\\s*$|^You received this message because you are subscribed.*");

    private static final String ISSUE_DESCRIPTION_TEMPLATE =
            "_Thread originally started in the keycloak-security mailing list. Replace the content here by a proper description._";

    private final Set<Long> processedComments = ConcurrentHashMap.newKeySet();

    @ConfigProperty(name = "google.group.target") String targetGroup;
    @ConfigProperty(name = "gmail.user.email") String botEmail;
    @ConfigProperty(name = "email.target.secalert") String secAlertEmail;

    @Inject GmailAdapter gmail;
    @Inject EmailGitHubAdapter github;
    @Inject BotCommandService commandService;

    // 1. INCOMING EMAILS (60s)
    @Scheduled(every = "60s")
    public void syncGmailToGitHub() {
        LOG.debug("Starting sync: Gmail -> GitHub");
        List<Message> messages = gmail.fetchUnreadMessages("is:unread");
        for (Message msgSummary : messages) {
            processMessage(msgSummary);
        }
    }

    // 2. BOT COMMANDS & FEEDBACK (10s)
    @Scheduled(every = "10s")
    public void processGitHubCommands() {
        LOG.debug("Polling GitHub for new commands...");
        try {
            List<GHIssue> issues = github.getOpenIssues();
            String myLogin = commandService.getBotName();
            if (myLogin == null || myLogin.isEmpty()) return;

            for (GHIssue issue : issues) {
                processIssueForCommands(issue, myLogin);
            }
        } catch (Exception e) {
            LOG.error("Error processing GitHub commands", e);
        }
    }

    private void processMessage(Message msgSummary) {
        Message msg = gmail.getMessage(msgSummary.getId());
        if (msg == null) return;

        String from = gmail.getHeader(msg, "From");
        if (from != null && from.contains(botEmail)) {
            gmail.markAsRead(msg.getId());
            return;
        }

        if (!isValidGroupMessage(msg)) {
            gmail.markAsRead(msg.getId());
            return;
        }

        String threadId = msg.getThreadId();
        String subject = gmail.getHeader(msg, "Subject");
        String cleanBody = sanitizeBody(gmail.getBody(msg)).orElse("(No content)");

        github.findIssueByThreadId(threadId).ifPresentOrElse(
                existingIssue -> {
                    String commentBody = "**Reply from " + from + ":**\n\n" + cleanBody;
                    github.commentOnIssue(existingIssue, commentBody);
                    LOG.infof("✅ Added comment to Issue #%d for Thread %s", existingIssue.getNumber(), threadId);
                },
                () -> {
                    String currentBotName = commandService.getBotName();
                    LOG.infof("🤖 Creating new Issue for Thread %s", threadId);

                    GHIssue newIssue = github.createIssue(subject, ISSUE_DESCRIPTION_TEMPLATE);
                    if (newIssue != null) {
                        try {
                            newIssue.addLabels(Labels.STATUS_TRIAGE);
                        } catch (Exception e) {
                            LOG.errorf(e, "Failed to add label to issue #%d", newIssue.getNumber());
                        }

                        String firstComment = VISIBLE_MARKER_PREFIX + threadId + "\n" +
                                "**From:** " + from + "\n\n" +
                                cleanBody;
                        github.commentOnIssue(newIssue, firstComment);
                        LOG.infof("Created Issue #%d", newIssue.getNumber());
                    }
                }
        );
        gmail.markAsRead(msg.getId());
    }

    private void processIssueForCommands(GHIssue issue, String myLogin) throws IOException {
        Optional<String> threadIdOpt = findThreadIdInComments(issue);

        for (GHIssueComment comment : issue.getComments()) {
            if (hasAlreadyProcessed(comment, myLogin)) continue;

            commandService.parse(comment.getBody()).ifPresent(cmd -> {
                boolean success = false;
                ReactionContent reaction = ReactionContent.EYES;

                switch (cmd.type()) {
                    case REPLY_KEYCLOAK_SECURITY:
                        if (threadIdOpt.isPresent()) {
                            // Logic: Sender + Group
                            success = sendReplyToSenderAndGroup(threadIdOpt.get(), issue.getTitle(), cmd.body());
                            if (!success) {
                                replyWithError(issue, comment, "❌ Error: Failed to send email via Gmail API.");
                                success = true;
                                reaction = ReactionContent.CONFUSED;
                            }
                        } else {
                            replyWithError(issue, comment, "❌ Error: I cannot find the Gmail Thread ID in comments.");
                            success = true;
                            reaction = ReactionContent.CONFUSED;
                        }
                        break;

                    case NEW_SECALERT:
                        // Logic: SecAlert + Group
                        success = sendNewEmail(secAlertEmail, targetGroup, cmd.subject().orElse("No Subject"), cmd.body());
                        if (!success) {
                            replyWithError(issue, comment, "❌ Error: Failed to send email via Gmail API.");
                            success = true;
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
                    try {
                        comment.createReaction(reaction);
                    } catch (Exception e) {
                        LOG.errorf(e, "Failed to add reaction to comment %d", comment.getId());
                    }
                    LOG.infof("✅ Command executed: %s", cmd.type());
                }
            });
        }
    }

    private void replyWithError(GHIssue issue, GHIssueComment comment, String message) {
        try {
            String userLogin = comment.getUser().getLogin();
            github.commentOnIssue(issue, "@" + userLogin + " " + message);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to send error reply to issue #%d", issue.getNumber());
        }
    }

    private void sendHelpMessage(GHIssue issue, GHIssueComment comment) {
        try {
            String userLogin = comment.getUser().getLogin();
            String body = "@" + userLogin + " " + commandService.getHelpMessage();
            github.commentOnIssue(issue, body);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to send help message to issue #%d", issue.getNumber());
        }
    }

    private boolean sendReplyToSenderAndGroup(String threadId, String subject, String body) {
        try {
            com.google.api.services.gmail.model.Thread thread = gmail.getThread(threadId);
            if (thread == null || thread.getMessages() == null) return false;
            Message targetMsg = findLastHumanMessage(thread.getMessages());
            if (targetMsg == null) return false;

            MimeMessage email = new MimeMessage(Session.getDefaultInstance(new Properties(), null));
            setupThreadingHeaders(email, targetMsg);

            String sender = gmail.getHeader(targetMsg, "From");
            if (sender != null) email.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(sender));
            email.addRecipient(jakarta.mail.Message.RecipientType.CC, new InternetAddress(targetGroup));

            email.setFrom(new InternetAddress(botEmail));
            email.setSubject(subject.startsWith("Re:") ? subject : "Re: " + subject);
            email.setText(body);

            gmail.sendMessage(threadId, email);
            return true;
        } catch (Exception e) {
            LOG.error("Failed to send reply to sender+group", e);
            return false;
        }
    }

    private boolean sendNewEmail(String to, String cc, String subject, String body) {
        try {
            MimeMessage email = new MimeMessage(Session.getDefaultInstance(new Properties(), null));
            email.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(to));
            if (cc != null && !cc.isBlank()) {
                email.addRecipient(jakarta.mail.Message.RecipientType.CC, new InternetAddress(cc));
            }
            email.setFrom(new InternetAddress(botEmail));
            email.setSubject(subject);
            email.setText(body);
            gmail.sendMessage(null, email);
            return true;
        } catch (Exception e) {
            LOG.error("Failed to send new email", e);
            return false;
        }
    }

    private Optional<String> findThreadIdInComments(GHIssue issue) throws IOException {
        for (GHIssueComment comment : issue.getComments()) {
            Optional<String> id = extractThreadId(comment.getBody());
            if (id.isPresent()) return id;
        }
        return Optional.empty();
    }

    private Optional<String> extractThreadId(String text) {
        if (text == null) return Optional.empty();
        Matcher m = VISIBLE_MARKER_PATTERN.matcher(text);
        if (m.find()) return Optional.of(m.group(1).trim());
        Matcher raw = RAW_HEX_PATTERN.matcher(text);
        if (raw.find()) return Optional.of(raw.group(1).trim());
        return Optional.empty();
    }

    private Optional<String> sanitizeBody(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        Matcher matcher = SIGNATURE_PATTERN.matcher(body);
        if (matcher.find()) {
            String trimmed = body.substring(0, matcher.start()).trim();
            return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
        }
        String trimmed = body.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    private boolean isValidGroupMessage(Message msg) {
        String listId = gmail.getHeader(msg, "List-ID");
        String groupIdentifier = targetGroup.split("@")[0];
        if (listId != null && listId.contains(groupIdentifier)) return true;
        String to = gmail.getHeader(msg, "To");
        String cc = gmail.getHeader(msg, "Cc");
        return (to != null && to.contains(targetGroup)) || (cc != null && cc.contains(targetGroup));
    }

    private void setupThreadingHeaders(MimeMessage email, Message targetMsg) throws Exception {
        String parentId = gmail.getHeader(targetMsg, "Message-ID");
        String refs = gmail.getHeader(targetMsg, "References");
        if (parentId != null && !parentId.isEmpty()) {
            email.setHeader("In-Reply-To", parentId);
            email.setHeader("References", (refs == null || refs.isEmpty() ? "" : refs + " ") + parentId);
        }
    }

    private Message findLastHumanMessage(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            String from = gmail.getHeader(history.get(i), "From");
            if (from != null && !from.toLowerCase().contains(botEmail.toLowerCase())) return history.get(i);
        }
        return history.get(history.size() - 1);
    }

    private boolean hasAlreadyProcessed(GHIssueComment comment, String myLogin) throws IOException {
        if (processedComments.contains(comment.getId())) return true;
        try {
            for (GHReaction reaction : comment.listReactions()) {
                String reactionUser = reaction.getUser().getLogin();
                // Check for EYES (Success) or CONFUSED (Error/Unknown)
                if ((reaction.getContent() == ReactionContent.EYES || reaction.getContent() == ReactionContent.CONFUSED) &&
                        (reactionUser.equalsIgnoreCase(myLogin) || reactionUser.equalsIgnoreCase(myLogin + "[bot]"))) {
                    processedComments.add(comment.getId());
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to check reactions for comment %d", comment.getId());
        }
        return false;
    }
}
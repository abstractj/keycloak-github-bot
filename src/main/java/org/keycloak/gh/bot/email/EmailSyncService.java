package org.keycloak.gh.bot.email;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.Thread;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.kohsuke.github.*;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class EmailSyncService {

    private static final Logger LOG = Logger.getLogger(EmailSyncService.class);

    private static final String VISIBLE_MARKER_PREFIX = "**Gmail-Thread-ID:** ";
    private static final Pattern VISIBLE_MARKER_PATTERN = Pattern.compile("\\*\\*Gmail-Thread-ID:\\*\\*\\s*([a-f0-9]+)");
    private static final Pattern RAW_HEX_PATTERN = Pattern.compile("\\b([a-f0-9]{16})\\b");

    @ConfigProperty(name = "google.group.target") String targetGroup;
    @ConfigProperty(name = "gmail.user.email") String botEmail;
    @ConfigProperty(name = "email.target.secalert") String secAlertEmail;

    @Inject GmailAdapter gmail;
    @Inject EmailGitHubAdapter github;
    @Inject BotCommandService commandService;

    @Scheduled(every = "60s")
    public void syncGmailToGitHub() {
        LOG.debug("Starting sync: Gmail -> GitHub");
        List<Message> messages = gmail.fetchUnreadMessages("is:unread to:" + targetGroup);
        for (Message msgSummary : messages) {
            processMessage(msgSummary);
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

        if (!isValidMailingListMessage(msg)) {
            gmail.markAsRead(msg.getId());
            return;
        }

        String threadId = msg.getThreadId();
        String subject = gmail.getHeader(msg, "Subject");
        String body = gmail.getBody(msg);

        GHIssue existingIssue = github.findIssueByThreadId(threadId);

        if (existingIssue == null) {
            String issueBody = body + "\n\n" + VISIBLE_MARKER_PREFIX + threadId;
            github.createIssue(subject, issueBody);
        } else {
            String commentBody = "**Reply from " + from + ":**\n\n" + body;
            github.commentOnIssue(existingIssue, commentBody);
        }
        gmail.markAsRead(msg.getId());
    }

    @Scheduled(every = "60s")
    public void syncGitHubToGmail() {
        LOG.debug("Starting sync: GitHub -> Gmail");
        try {
            List<GHIssue> issues = github.getOpenIssues();
            String myLogin = commandService.getBotName();
            if (myLogin == null) return; // GitHub provider might not be ready

            for (GHIssue issue : issues) {
                processIssueForCommands(issue, myLogin);
            }
        } catch (Exception e) {
            LOG.error("Error in syncGitHubToGmail", e);
        }
    }

    private void processIssueForCommands(GHIssue issue, String myLogin) throws IOException {
        String threadId = extractThreadId(issue.getBody());
        if (threadId == null || threadId.isEmpty()) return;

        for (GHIssueComment comment : issue.getComments()) {
            if (hasAlreadyProcessed(comment, myLogin)) continue;

            boolean success = false;
            String commentBody = comment.getBody();

            if (commandService.isReplyAllCommand(commentBody)) {
                String content = commandService.extractReplyContent(commentBody);
                if (!content.isEmpty()) {
                    success = sendEmailReply(threadId, issue.getTitle(), content);
                }
            }
            else if (commandService.isEmailSecAlertCommand(commentBody)) {
                BotCommandService.EmailRequest request = commandService.extractSecAlertData(commentBody);
                if (request != null && !request.body().isEmpty()) {
                    String subjectToUse = (request.subject() != null) ? request.subject() : issue.getTitle();
                    //TODO remove duplicates
                    success = sendEmailDirect(threadId, subjectToUse, request.body(), secAlertEmail, targetGroup);
                }
            }

            if (success) {
                comment.createReaction(ReactionContent.EYES);
                LOG.infof("✅ Command executed for Thread %s", threadId);
            }
        }
    }

    private boolean sendEmailReply(String threadId, String subject, String body) {
        try {
            Thread thread = gmail.getThread(threadId);
            if (thread == null || thread.getMessages() == null || thread.getMessages().isEmpty()) return false;
            Message targetMsg = findLastHumanMessage(thread.getMessages());
            if (targetMsg == null) return false;

            MimeMessage email = new MimeMessage(Session.getDefaultInstance(new Properties(), null));
            setupThreadingHeaders(email, targetMsg);

            List<InternetAddress> recipients = determineReplyRecipients(targetMsg);
            for (InternetAddress addr : recipients) {
                email.addRecipient(jakarta.mail.Message.RecipientType.TO, addr);
            }

            email.setFrom(new InternetAddress(botEmail));
            email.setSubject(subject.startsWith("Re:") ? subject : "Re: " + subject);
            email.setText(body);
            gmail.sendMessage(threadId, email);
            return true;
        } catch (Exception e) {
            LOG.error("Failed to send reply email", e);
            return false;
        }
    }

    private boolean sendEmailDirect(String threadId, String subject, String body, String to, String cc) {
        try {
            Thread thread = gmail.getThread(threadId);
            if (thread == null || thread.getMessages() == null) return false;
            Message targetMsg = findLastHumanMessage(thread.getMessages());
            if (targetMsg == null) targetMsg = thread.getMessages().get(thread.getMessages().size() - 1);

            MimeMessage email = new MimeMessage(Session.getDefaultInstance(new Properties(), null));
            setupThreadingHeaders(email, targetMsg);

            email.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(to));
            if (cc != null && !cc.isEmpty()) {
                email.addRecipient(jakarta.mail.Message.RecipientType.CC, new InternetAddress(cc));
            }

            email.setFrom(new InternetAddress(botEmail));
            email.setSubject(subject);
            email.setText(body);

            gmail.sendMessage(threadId, email);
            return true;
        } catch (Exception e) {
            LOG.error("Failed to send direct email", e);
            return false;
        }
    }

    private void setupThreadingHeaders(MimeMessage email, Message targetMsg) throws Exception {
        String parentId = gmail.getHeader(targetMsg, "Message-ID");
        String refs = gmail.getHeader(targetMsg, "References");
        if (parentId != null && !parentId.isEmpty()) {
            email.setHeader("In-Reply-To", parentId);
            email.setHeader("References", (refs == null || refs.isEmpty() ? "" : refs + " ") + parentId);
        }
    }

    private boolean isValidMailingListMessage(Message msg) {
        String listId = gmail.getHeader(msg, "List-ID");
        return listId != null && listId.contains(targetGroup.split("@")[0]);
    }

    private Message findLastHumanMessage(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            String from = gmail.getHeader(history.get(i), "From");
            if (from != null && !from.toLowerCase().contains(botEmail.toLowerCase())) return history.get(i);
        }
        return history.get(history.size() - 1);
    }

    private boolean hasAlreadyProcessed(GHIssueComment comment, String myLogin) throws IOException {
        for (GHReaction reaction : comment.listReactions()) {
            if (reaction.getContent() == ReactionContent.EYES && reaction.getUser().getLogin().equals(myLogin)) return true;
        }
        return false;
    }

    private String extractThreadId(String text) {
        if (text == null) return null;
        Matcher m = VISIBLE_MARKER_PATTERN.matcher(text);
        if (m.find()) return m.group(1).trim();
        Matcher raw = RAW_HEX_PATTERN.matcher(text);
        return raw.find() ? raw.group(1) : null;
    }

    private List<InternetAddress> determineReplyRecipients(Message targetMsg) {
        Map<String, InternetAddress> map = new HashMap<>();
        try {
            InternetAddress group = new InternetAddress(targetGroup);
            map.put(group.getAddress().toLowerCase(), group);
            String from = gmail.getHeader(targetMsg, "From");
            String replyTo = gmail.getHeader(targetMsg, "Reply-To");

            if (from != null && from.toLowerCase().contains(group.getAddress().toLowerCase())) {
                addRecipientsToMap(map, replyTo);
            } else {
                addRecipientsToMap(map, from);
            }
            addRecipientsToMap(map, gmail.getHeader(targetMsg, "To"));
            addRecipientsToMap(map, gmail.getHeader(targetMsg, "Cc"));
            map.remove(botEmail.toLowerCase());
        } catch (Exception e) { LOG.error(e); }
        return new ArrayList<>(map.values());
    }

    private void addRecipientsToMap(Map<String, InternetAddress> map, String header) {
        if (header == null || header.isEmpty()) return;
        try {
            for (InternetAddress addr : InternetAddress.parse(header)) {
                map.put(addr.getAddress().toLowerCase(), addr);
            }
        } catch (Exception e) {}
    }
}
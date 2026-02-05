package org.keycloak.gh.bot.email;

import com.google.api.services.gmail.model.Message;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Properties;

@ApplicationScoped
public class MailSender {

    private static final Logger LOG = Logger.getLogger(MailSender.class);

    @ConfigProperty(name = "gmail.user.email") String botEmail;

    @Inject GmailAdapter gmail;

    private Session mailSession;

    @PostConstruct
    public void init() {
        this.mailSession = Session.getDefaultInstance(new Properties(), null);
    }

    public boolean sendNewEmail(String to, String cc, String subject, String body) {
        try {
            MimeMessage email = createBaseMessage();
            email.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(to));
            if (cc != null && !cc.isBlank()) {
                email.addRecipient(jakarta.mail.Message.RecipientType.CC, new InternetAddress(cc));
            }
            email.setSubject(subject);
            email.setText(body);

            gmail.sendMessage(null, email);
            return true;
        } catch (Exception e) {
            LOG.error("Failed to send new email", e);
            return false;
        }
    }

    public boolean sendReply(String threadId, String subject, String body, String ccTarget) {
        try {
            com.google.api.services.gmail.model.Thread thread = gmail.getThread(threadId);
            if (thread == null || thread.getMessages() == null) return false;

            Message targetMsg = findLastHumanMessage(thread.getMessages());
            if (targetMsg == null) return false;

            Map<String, String> headers = gmail.getHeadersMap(targetMsg);

            MimeMessage email = createBaseMessage();
            setupThreadingHeaders(email, headers);

            String sender = headers.get("From");
            if (sender != null) email.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(sender));
            if (ccTarget != null) email.addRecipient(jakarta.mail.Message.RecipientType.CC, new InternetAddress(ccTarget));

            email.setSubject(subject.startsWith("Re:") ? subject : "Re: " + subject);
            email.setText(body);

            gmail.sendMessage(threadId, email);
            return true;
        } catch (Exception e) {
            LOG.error("Failed to send reply email", e);
            return false;
        }
    }

    private MimeMessage createBaseMessage() throws Exception {
        MimeMessage email = new MimeMessage(mailSession);
        email.setFrom(new InternetAddress(botEmail));
        return email;
    }

    private void setupThreadingHeaders(MimeMessage email, Map<String, String> headers) throws Exception {
        String parentId = headers.get("Message-ID");
        String refs = headers.get("References");
        if (parentId != null && !parentId.isEmpty()) {
            email.setHeader("In-Reply-To", parentId);
            email.setHeader("References", (refs == null || refs.isEmpty() ? "" : refs + " ") + parentId);
        }
    }

    private Message findLastHumanMessage(List<Message> history) {
        // Iterate backwards to find the most recent message NOT from the bot
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            // Optimization: Get map once per message instead of repeatedly iterating parts
            Map<String, String> headers = gmail.getHeadersMap(msg);
            String from = headers.get("From");

            if (from != null && !from.toLowerCase().contains(botEmail.toLowerCase())) {
                return msg;
            }
        }
        // Fallback: return the last message if everything seems to be from the bot (edge case)
        return history.get(history.size() - 1);
    }
}
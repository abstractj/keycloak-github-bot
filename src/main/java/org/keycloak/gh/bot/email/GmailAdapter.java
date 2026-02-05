package org.keycloak.gh.bot.email;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.google.api.services.gmail.model.Thread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Adapts the Gmail API to provide domain-specific email operations.
 */
@ApplicationScoped
public class GmailAdapter {

    @Inject
    Gmail gmail;

    @ConfigProperty(name = "gmail.batch.size", defaultValue = "20")
    long batchSize;

    public List<Message> fetchUnreadMessages(String query) throws IOException {
        ListMessagesResponse listResponse = gmail.users().messages().list("me")
                .setQ(query)
                .setMaxResults(batchSize)
                .execute();
        return listResponse.getMessages() != null ? listResponse.getMessages() : Collections.emptyList();
    }

    public Message getMessage(String id) throws IOException {
        return gmail.users().messages().get("me", id).execute();
    }

    public Thread getThread(String threadId) throws IOException {
        return gmail.users().threads().get("me", threadId).setFormat("METADATA").execute();
    }

    public void markAsRead(String messageId) throws IOException {
        ModifyMessageRequest mods = new ModifyMessageRequest().setRemoveLabelIds(Collections.singletonList("UNREAD"));
        gmail.users().messages().modify("me", messageId, mods).execute();
    }

    public void sendMessage(String threadId, MimeMessage email) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            email.writeTo(buffer);
        } catch (Exception e) {
            throw new IOException("Failed to serialize MimeMessage", e);
        }

        String encodedEmail = Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());

        Message message = new Message();
        message.setRaw(encodedEmail);
        if (threadId != null) {
            message.setThreadId(threadId);
        }
        gmail.users().messages().send("me", message).execute();
    }

    public String getHeader(Message message, String name) {
        if (message == null || message.getPayload() == null || message.getPayload().getHeaders() == null) return "";
        return message.getPayload().getHeaders().stream()
                .filter(h -> h.getName().equalsIgnoreCase(name))
                .findFirst()
                .map(MessagePartHeader::getValue).orElse("");
    }

    public Map<String, String> getHeadersMap(Message message) {
        if (message == null || message.getPayload() == null || message.getPayload().getHeaders() == null) {
            return Collections.emptyMap();
        }
        return message.getPayload().getHeaders().stream()
                .collect(Collectors.toMap(
                        MessagePartHeader::getName,
                        MessagePartHeader::getValue,
                        (existing, replacement) -> existing,
                        () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)
                ));
    }

    public String getBody(Message message) {
        if (message == null || message.getPayload() == null) return "";

        MessagePartBody body = message.getPayload().getBody();
        if (body != null && body.getData() != null) {
            return new String(Base64.getUrlDecoder().decode(body.getData()));
        }

        return getPartsBody(message.getPayload().getParts()).orElse("");
    }

    private Optional<String> getPartsBody(List<MessagePart> parts) {
        if (parts == null) return Optional.empty();
        for (MessagePart part : parts) {
            if ("text/plain".equals(part.getMimeType()) && part.getBody() != null && part.getBody().getData() != null) {
                return Optional.of(new String(Base64.getUrlDecoder().decode(part.getBody().getData())));
            }
            if (part.getParts() != null) {
                Optional<String> result = getPartsBody(part.getParts());
                if (result.isPresent()) return result;
            }
        }
        return Optional.empty();
    }
}
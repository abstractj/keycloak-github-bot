package org.keycloak.gh.bot.security.email;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Loads and serves auto-reply message templates for email responses. */
@Singleton
public class AutoReplyMessages {

    private static final String RESOURCE = "auto-reply-messages.properties";

    private final Properties properties;

    public AutoReplyMessages() {
        this.properties = loadProperties();
    }

    public String getMessage(AutoReplyType type) {
        String value = properties.getProperty(type.name());
        if (value == null) {
            throw new IllegalArgumentException("No auto-reply template for " + type.name());
        }
        return value;
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream stream = AutoReplyMessages.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(RESOURCE + " not found on classpath");
            }
            props.load(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + RESOURCE, e);
        }
        return props;
    }
}

package org.keycloak.gh.bot.security.common;

public final class Constants {

    public static final String SOURCE_EMAIL = "source/email";
    public static final String KIND_CVE = "kind/cve";
    public static final String CVE_TBD_PREFIX = "CVE-TBD";

    public static final String GMAIL_THREAD_ID_PREFIX = "**Gmail-Thread-ID:**";
    public static final String ISSUE_DESCRIPTION_TEMPLATE = "_Thread originally started in the keycloak-security mailing list. Replace the content here by a proper description._";

    public static final String REDHAT_JIRA_SENDER = "jira-issues@redhat.com";
    public static final String REDHAT_SECALERT_SENDER = "secalert@redhat.com";

    public static final String HELP_MESSAGE = """
            **Security Bot Help**
            
            Available commands:
            - `/new secalert "Subject" [Body]`: Create a new security alert email (auto-prefixes CVE-TBD).
            - `/reply keycloak-security [Body]`: Reply to the current security thread.
            
            Example:
            `@keycloak-bot /new secalert "RCE in Admin Console" Found a vulnerability...`
            """;

    private Constants() {
    }
}
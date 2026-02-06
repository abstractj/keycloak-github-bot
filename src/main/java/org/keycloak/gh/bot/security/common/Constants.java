package org.keycloak.gh.bot.security.common;

public final class Constants {

    public static final String SOURCE_EMAIL = "source/email";
    public static final String KIND_CVE = "kind/cve";
    public static final String CVE_TBD_PREFIX = "CVE-TBD";

    // Marker for the thread with the External Reporter
    public static final String GMAIL_THREAD_ID_PREFIX = "**Gmail-Thread-ID:**";

    // New Marker for the internal thread with Red Hat SecAlert
    public static final String SECALERT_THREAD_ID_PREFIX = "**SecAlert-Thread-ID:**";

    public static final String ISSUE_DESCRIPTION_TEMPLATE = "_Thread originally started in the keycloak-security mailing list. Replace the content here by a proper description._";

    public static final String REDHAT_JIRA_SENDER = "jira-issues@redhat.com";
    public static final String REDHAT_SECALERT_SENDER = "secalert@redhat.com";

    public static final String HELP_MESSAGE = """
            **Security Bot Help**
            
            Available commands:
            - `/new secalert "Subject" [Body]`: Create a new security alert email (auto-prefixes CVE-TBD in GitHub).
            - `/reply keycloak-security [Body]`: Reply to the current security thread.
            
            Note: The command must be on the first line, and the body must start on a new line.
            """;

    private Constants() {
    }
}
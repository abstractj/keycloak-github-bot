package org.keycloak.gh.bot.security.common;

public final class SecurityConstants {

    public static final String SOURCE_EMAIL = "source/email";
    public static final String KIND_CVE = "kind/cve";
    public static final String CVE_TBD_PREFIX = "CVE-TBD";

    public static final String GMAIL_THREAD_ID_PREFIX = "**Gmail-Thread-ID:**";
    public static final String ISSUE_DESCRIPTION_TEMPLATE = "_Thread originally started in the keycloak-security mailing list. Replace the content here by a proper description._";

    public static final String REDHAT_JIRA_SENDER = "jira-issues@redhat.com";
    public static final String REDHAT_SECALERT_SENDER = "secalert@redhat.com";

    private SecurityConstants() {
    }
}
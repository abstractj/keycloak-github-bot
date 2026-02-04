package org.keycloak.gh.bot.email;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class EmailSyncScheduler {

    @Inject IncomingMailProcessor incomingMail;
    @Inject CommandProcessor commandProcessor;

    @Scheduled(every = "60s")
    public void syncGmailToGitHub() {
        incomingMail.processUnreadEmails();
    }

    @Scheduled(every = "10s")
    public void processGitHubCommands() {
        commandProcessor.processCommands();
    }
}
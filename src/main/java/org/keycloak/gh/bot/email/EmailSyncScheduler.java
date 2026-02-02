package org.keycloak.gh.bot.email;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Manages the scheduled execution of email synchronization and command processing tasks.
 */
@ApplicationScoped
public class EmailSyncScheduler {

    @Inject IncomingMailProcessor incomingMail;
    @Inject CommandProcessor commandProcessor;

    @Scheduled(every = "${bot.email.sync.interval:60s}", concurrentExecution = ConcurrentExecution.SKIP)
    public void syncGmailToGitHub() {
        incomingMail.processUnreadEmails();
    }

    @Scheduled(every = "${bot.command.process.interval:10s}", concurrentExecution = ConcurrentExecution.SKIP)
    public void processGitHubCommands() {
        commandProcessor.processCommands();
    }
}
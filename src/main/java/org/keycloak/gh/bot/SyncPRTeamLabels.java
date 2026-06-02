package org.keycloak.gh.bot;

import io.quarkiverse.githubapp.event.PullRequest;
import org.jboss.logging.Logger;
import org.keycloak.gh.bot.utils.CommitUtils;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

public class SyncPRTeamLabels {

    private static final Logger logger = Logger.getLogger(SyncPRTeamLabels.class);

    void onOpen(@PullRequest.Opened GHEventPayload.PullRequest payload) throws IOException {
        GHPullRequest pullRequest = payload.getPullRequest();

        List<String> pullRequestTeamLabels = getTeamLabels(pullRequest.getLabels());
        if (!pullRequestTeamLabels.isEmpty()) {
            logger.infof("New PR %s: Team labels %s already exists", pullRequestTeamLabels);
            return;
        }

        List<GHIssue> linkedIssues = CommitUtils.linkedIssues(payload.getRepository(), pullRequest);
        List<String> teamLabels = linkedIssues.stream().flatMap(i -> i.getLabels().stream()).map(GHLabel::getName).filter(l -> l.startsWith("team/")).distinct().toList();
        List<Integer> linkedIssueNumbers = linkedIssues.stream().map(GHIssue::getNumber).toList();

        if (!teamLabels.isEmpty()) {
            logger.infof("New PR %s: Adding labels %s from issues %s", pullRequest.getNumber(), String.join(", ", teamLabels), linkedIssueNumbers);
            pullRequest.addLabels(teamLabels.toArray(new String[0]));
        } else if (!linkedIssues.isEmpty()) {
            logger.infof("New PR %s: No team labels found in issues %s", pullRequest.getNumber(), linkedIssueNumbers);
        } else {
            logger.infof("New PR %s: No linked issues found", pullRequest.getNumber());
        }
    }

    void onLabelled(@PullRequest.Labeled GHEventPayload.PullRequest payload) throws IOException {
        String newLabel = payload.getLabel().getName();
        if (newLabel.startsWith("team/")) {
            GHPullRequest pullRequest = payload.getPullRequest();
            List<GHIssue> linkedIssues = CommitUtils.linkedIssues(payload.getRepository(), pullRequest);
            List<Integer> linkedIssueNumbers = linkedIssues.stream().map(GHIssue::getNumber).toList();

            if (!linkedIssues.isEmpty()) {
                logger.infof("Team label %s added to PR %s: Updating issues %s", newLabel, pullRequest.getNumber(), linkedIssueNumbers);
                for (GHIssue issue : linkedIssues) {
                    issue.addLabels(newLabel);
                }
            } else {
                logger.infof("Team label %s added to PR %s: No linked issues found", newLabel, pullRequest.getNumber());
            }
        }
    }

    private List<String> getTeamLabels(Collection<GHLabel> labels) {
        return labels.stream().map(GHLabel::getName).filter(l -> l.startsWith("team/")).toList();
    }

}

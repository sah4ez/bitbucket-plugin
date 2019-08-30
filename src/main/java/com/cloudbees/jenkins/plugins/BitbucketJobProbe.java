package com.cloudbees.jenkins.plugins;

import hudson.model.Job;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitStatus;
import hudson.plugins.mercurial.MercurialSCM;
import hudson.scm.SCM;
import hudson.security.ACL;

import java.net.URISyntaxException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.triggers.Trigger;
import jenkins.model.Jenkins;

import jenkins.model.ParameterizedJobMixIn;
import jenkins.triggers.SCMTriggerItem;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import com.google.common.base.Objects;

public class BitbucketJobProbe {

    public static final String RONTE_JOB_NAME = "platoon-backend-multijob";

    @Deprecated
    public void triggerMatchingJobs(String user, String url, String scm) {
        triggerMatchingJobs(user, url, scm, "");
    }

    public void triggerMatchingJobs(String user, String url, String scm, String payload) {
        if ("git".equals(scm) || "hg".equals(scm)) {
            SecurityContext old = Jenkins.getInstance().getACL().impersonate(ACL.SYSTEM);
            try {
                BitBucketTrigger bTrigger = null;
                Job job = Jenkins.getInstance().getItemByFullName(RONTE_JOB_NAME, Job.class);
                if (job instanceof ParameterizedJobMixIn.ParameterizedJob) {
                    ParameterizedJobMixIn.ParameterizedJob pJob = (ParameterizedJobMixIn.ParameterizedJob) job;
                    for (Trigger trigger : pJob.getTriggers().values()) {

                        if (trigger instanceof BitBucketTrigger) {
                            bTrigger = (BitBucketTrigger) trigger;
                            break;
                        }
                    }
                }

                if (bTrigger != null) {
                    LOGGER.log(Level.INFO, "Considering to poke {0}", job.getFullDisplayName());
                    bTrigger.onPost(user, payload);
                } else {
                    LOGGER.log(Level.INFO, "{0} hasn't BitBucketTrigger set", job.getName());
                }
            }catch (Exception e) {
                LOGGER.log(Level.SEVERE, "{0} hasn't BitBucketTrigger set", e.getCause());
            } finally {
                SecurityContextHolder.setContext(old);
            }

        } else {
            throw new UnsupportedOperationException("Unsupported SCM type " + scm);
        }
    }

    private boolean hasBeenTriggered(List<SCM> scmTriggered, SCM scmTrigger) {
        for (SCM scm : scmTriggered) {
            if (scm.equals(scmTrigger)) {
                return true;
            }
        }
        return false;
    }

    private boolean match(SCM scm, URIish url) {
        if (scm instanceof GitSCM) {
            for (RemoteConfig remoteConfig : ((GitSCM) scm).getRepositories()) {
                for (URIish urIish : remoteConfig.getURIs()) {
                    // needed cause the ssh and https URI differs in Bitbucket Server.
                    if (urIish.getPath().startsWith("/scm")) {
                        urIish = urIish.setPath(urIish.getPath().substring(4));
                    }

                    // needed because bitbucket self hosted does not transfer any host information
                    if (StringUtils.isEmpty(url.getHost())) {
                        urIish = urIish.setHost(url.getHost());
                    }

                    LOGGER.log(Level.FINE, "Trying to match {0} ", urIish.toString() + "<-->" + url.toString());
                    if (GitStatus.looselyMatches(urIish, url)) {
                        return true;
                    }
                }
            }
        } else if (scm instanceof MercurialSCM) {
            try {
                URI hgUri = new URI(((MercurialSCM) scm).getSource());
                String remote = url.toString();
                if (looselyMatches(hgUri, remote)) {
                    return true;
                }
            } catch (URISyntaxException ex) {
                LOGGER.log(Level.SEVERE, "Could not parse jobSource uri: {0} ", ex);
            }
        }
        return false;
    }

    private boolean looselyMatches(URI notifyUri, String repository) {
        boolean result = false;
        try {
            URI repositoryUri = new URI(repository);
            result = Objects.equal(notifyUri.getHost(), repositoryUri.getHost())
                    && Objects.equal(notifyUri.getPath(), repositoryUri.getPath())
                    && Objects.equal(notifyUri.getQuery(), repositoryUri.getQuery());
        } catch (URISyntaxException ex) {
            LOGGER.log(Level.SEVERE, "Could not parse repository uri: {0}, {1}", new Object[]{repository, ex});
        }
        return result;
    }

    private static final Logger LOGGER = Logger.getLogger(BitbucketJobProbe.class.getName());

}

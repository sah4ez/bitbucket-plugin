package com.cloudbees.jenkins.plugins;

import hudson.Extension;
import hudson.Util;
import hudson.console.AnnotatedLargeText;
import hudson.model.*;
import hudson.model.queue.QueueTaskFuture;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.SequentialExecutionQueue;
import hudson.util.StreamTaskListener;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.triggers.SCMTriggerItem;
import org.apache.commons.jelly.XMLOutput;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class BitBucketTrigger extends Trigger<Job<?, ?>> {



    @DataBoundConstructor
    public BitBucketTrigger() {

    }

    /**
     * Called when a POST is made.
     */
    @Deprecated
    public void onPost(String triggeredByUser) {
        onPost(triggeredByUser, "");
    }

    /**
     * Called when a POST is made.
     */
    public QueueTaskFuture onPost(String triggeredByUser, final String payload) {
        final String pushBy = triggeredByUser;
        AtomicReference<QueueTaskFuture> currentTask = new AtomicReference<>();

        getDescriptor().queue.execute(() -> {
            try (StreamTaskListener listener = new StreamTaskListener(getLogFile())) {

                long start = System.currentTimeMillis();
                PrintStream logger = listener.getLogger();
                logger.println("Started on " + DateFormat.getDateTimeInstance().format(new Date()));

                assert job != null;
                String name = " #" + job.getNextBuildNumber();
                BitBucketPushCause cause;
                try {
                    cause = new BitBucketPushCause(getLogFile(), pushBy);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to parse the polling log", e);
                    cause = new BitBucketPushCause(pushBy);
                }
                ParameterizedJobMixIn pJob = new ParameterizedJobMixIn() {
                    @Override
                    protected Job asJob() {
                        return job;
                    }
                };
                BitBucketPayload bitBucketPayload = new BitBucketPayload(payload);
                LOGGER.info("Schedule " + job.getName());
                currentTask.set(pJob.scheduleBuild2(0, new CauseAction(cause), bitBucketPayload));
                if (pJob.scheduleBuild(cause)) {
                    LOGGER.info("SCM changes detected in " + job.getName() + ". Triggering " + name);
                } else {
                    LOGGER.info("SCM changes detected in " + job.getName() + ". Job is already in the queue");
                }
                logger.println("Done. Took " + Util.getTimeSpanString(System.currentTimeMillis() - start));
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to record SCM polling", e);
            }
        });
        return currentTask.get();
    }

    @Override
    public Collection<? extends Action> getProjectActions() {
        return Collections.singleton(new BitBucketWebHookPollingAction());
    }

    /**
     * Returns the file that records the last/current polling activity.
     */
    public File getLogFile() {
        return new File(job.getRootDir(), "bitbucket-polling.log");
    }

    /**
     * Check if "bitbucket-polling.log" already exists to initialize it
     */
    public boolean IsLogFileInitialized() {
        File file = new File(job.getRootDir(), "bitbucket-polling.log");
        return file.exists();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public final class BitBucketWebHookPollingAction implements Action {
        public Job<?, ?> getOwner() {
            return job;
        }

        public String getIconFileName() {
            return "clipboard.png";
        }

        public String getDisplayName() {
            return "BitBucket Hook Log";
        }

        public String getUrlName() {
            return "BitBucketPollLog";
        }

        public String getLog() throws IOException {
            return Util.loadFile(getLogFile());
        }

        /**
         * Writes the annotated log to the given output.
         */
        public void writeLogTo(XMLOutput out) throws IOException {
            new AnnotatedLargeText<BitBucketWebHookPollingAction>(getLogFile(), Charset.defaultCharset(), true, this).writeHtmlTo(0, out.asWriter());
        }
    }

    @Extension
    @Symbol("bitbucketPush")
    public static class DescriptorImpl extends TriggerDescriptor {
        private transient final SequentialExecutionQueue queue = new SequentialExecutionQueue(Hudson.MasterComputer.threadPoolForRemoting);

        @Override
        public boolean isApplicable(Item item) {
            return item instanceof Job && SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(item) != null
                    && item instanceof ParameterizedJobMixIn.ParameterizedJob;
        }

        @Override
        public String getDisplayName() {
            return "Ronte Bitbucket trigger for build from multirepo by Bitbucket push";
        }
    }

    private static final Logger LOGGER = Logger.getLogger(BitBucketTrigger.class.getName());
}

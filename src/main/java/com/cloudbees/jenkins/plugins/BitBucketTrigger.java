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
import sun.awt.windows.ThemeReader;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class BitBucketTrigger extends Trigger<Job<?, ?>> {

    private transient final static ReentrantLock lock = new ReentrantLock();

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
    public void onPost(String triggeredByUser, final String payload) {
        final String pushBy = triggeredByUser;
        while (getDescriptor().queue.getInProgress().size() > 0){
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        getDescriptor().queue.execute(new Runnable() {
            private boolean runPolling() {
                try {
                    StreamTaskListener listener = new StreamTaskListener(getLogFile());

                    try {
                        PrintStream logger = listener.getLogger();
                        long start = System.currentTimeMillis();
                        logger.println("Started on " + DateFormat.getDateTimeInstance().format(new Date()));
                        boolean result = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(job).poll(listener).hasChanges();

                        logger.println("Done. Took " + Util.getTimeSpanString(System.currentTimeMillis() - start));
                        if (result)
                            logger.println("Changes found");
                        else
                            logger.println("No changes");
                    } catch (Error | Exception e) {
                        e.printStackTrace(listener.error("Failed to record SCM polling"));
                        LOGGER.log(Level.SEVERE, "Failed to record SCM polling", e);
                        throw e;
                    } finally {
                        listener.close();
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Failed to record SCM polling", e);
                }
                return true;
            }

            public void run() {
                try {
                    runPolling();
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
                    QueueTaskFuture queueTaskFuture = pJob.scheduleBuild2(5, new CauseAction(cause), bitBucketPayload);
                    assert queueTaskFuture != null;
                    if (pJob.scheduleBuild(cause)) {
                        LOGGER.info("SCM changes detected in " + job.getName() + ". Triggering " + name);
                    } else {
                        LOGGER.info("SCM changes detected in " + job.getName() + ". Job is already in the queue");
                    }
                    queueTaskFuture.waitForStart();
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            }

        });
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

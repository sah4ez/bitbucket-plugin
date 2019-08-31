package com.cloudbees.jenkins.plugins;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.EnvironmentContributingAction;
import hudson.model.InvisibleAction;
import net.sf.json.JSONObject;

import javax.annotation.Nonnull;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Inject the payload received by BitBucket into the build through $BITBUCKET_PAYLOAD so it can be processed
 * @since January 9, 2016
 * @version 1.1.5
 */
public class BitBucketPayload extends InvisibleAction implements EnvironmentContributingAction {
    private final @Nonnull String payload;

    public BitBucketPayload(@Nonnull String payload) {
        this.payload = payload;
    }

    @Nonnull
    public String getPayload() {
        return payload;
    }

    /**
     *
     * @param abstractBuild
     * @param envVars
     * prepare environment variables from BITBUCKET_PAYLOAD
     * extract REPOSITORY name, AUTHOR changes and BRANCH
     */
    @Override
    public void buildEnvVars(AbstractBuild<?, ?> abstractBuild, EnvVars envVars) {
        final String payload = getPayload();
        LOGGER.log(Level.FINEST, "Injecting BITBUCKET_PAYLOAD: {0}", payload);
        envVars.put("BITBUCKET_PAYLOAD", payload);

        JSONObject JSONPayload = JSONObject.fromObject(payload);
        if (JSONPayload.has("repository")) {
            envVars.put("REPOSITORY", JSONPayload.getJSONObject("repository").getString("name"));
        }
        if (JSONPayload.has("actor")) {
            envVars.put("AUTHOR", JSONPayload.getJSONObject("actor").getString("nickname"));
        }
        if (JSONPayload.has("push")) {
            for (Object changes : JSONPayload.getJSONObject("push").getJSONArray("changes")) {
                if (changes instanceof JSONObject) {
                    JSONObject item = (JSONObject) changes;
                    if (item.has("new")) {
                        envVars.put("BRANCH", item.getJSONObject("new").getString("name"));
                    }
                }

            }
        }
        if (JSONPayload.has("pullrequest")) {
            if (JSONPayload.getJSONObject("pullrequest").getString("state") == "MERGED") {
                envVars.put("BRANCH", JSONPayload
                                .getJSONObject("pullrequest")
                                .getJSONObject("destination")
                                .getJSONObject("branch")
                                .getString("name"));
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(BitBucketPayload.class.getName());
}

package io.jenkins.plugins.appcircle.enterprise.app.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.appcircle.enterprise.app.store.Models.UserResponse;
import java.io.IOException;
import java.net.URISyntaxException;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.json.JSONException;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class EnterpriseAppStoreBuilder extends Builder implements SimpleBuildStep {

    private final Secret personalAPIToken;
    private final String appPath;
    private final String summary;
    private final String releaseNotes;
    private final String publishType;
    private String authEndpoint;
    private String apiEndpoint;

    @DataBoundConstructor
    public EnterpriseAppStoreBuilder(
            String personalAPIToken, String appPath, String releaseNotes, String summary, String publishType) {
        this.personalAPIToken = Secret.fromString(personalAPIToken);
        this.appPath = appPath;
        this.summary = summary;
        this.releaseNotes = releaseNotes;
        this.publishType = publishType;
    }

    public Secret getPersonalAPIToken() {
        return personalAPIToken;
    }

    public String getAuthEndpoint() {
        return authEndpoint;
    }

    @DataBoundSetter
    public void setAuthEndpoint(String authEndpoint) {
        this.authEndpoint = authEndpoint;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    @DataBoundSetter
    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    public String getAppPath() {
        return appPath;
    }

    public String getReleaseNotes() {
        return releaseNotes;
    }

    public String getSummary() {
        return summary;
    }

    public String getPublishType() {
        return publishType;
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars env,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        try {
            if (!validateFileExtension(this.appPath)) {
                throw new IOException("Invalid file extension: " + this.appPath
                        + ". For Android, use .apk or .aab. For iOS, use .ipa.");
            }

            UserResponse response = AuthService.getAcToken(this.personalAPIToken, this.authEndpoint);
            listener.getLogger().println("Login is successful.");
            UploadService uploadService = new UploadService(response.getAccessToken(), this.apiEndpoint);

            // The artifact lives in the build workspace on the agent, not on the controller,
            // so resolve it through the FilePath passed to perform() (remote-safe access).
            FilePath artifact = workspace.child(this.appPath);
            if (!artifact.exists()) {
                throw new IOException("App path not found in workspace: " + this.appPath);
            }
            JSONObject uploadResponse = uploadService.uploadArtifact(artifact);
            Boolean result = uploadService.checkUploadStatus(uploadResponse.optString("taskId"));

            if (result) {
                listener.getLogger()
                        .println(this.appPath + " uploaded to the Appcircle Enterprise Store successfully.");
                if (!this.publishType.equals("0")) {
                    listener.getLogger().println("App is publishing.");
                    String profileId = uploadService.getProfileId();
                    String appVersionId = uploadService.getLatestAppVersionId(profileId);
                    Boolean isPublished = uploadService.publishEnterpriseAppVersion(
                            profileId, appVersionId, this.summary, this.releaseNotes, this.publishType);
                    if (isPublished) {
                        listener.getLogger().println("App is published.");
                    } else {
                        listener.getLogger().println("Something went wrong. App could not published.");
                    }
                }
            }
        } catch (JSONException e) {
            listener.getLogger().println(e.getMessage());
        } catch (URISyntaxException e) {
            listener.error("Invalid URI: " + e.getMessage());
        } catch (Exception e) {
            listener.getLogger().println(e.getMessage());
            run.setResult(Result.FAILURE);
        }
    }

    Boolean validateFileExtension(String filePath) {
        if (!filePath.matches(".*\\.(apk|aab|ipa)$")) {
            return false;
        }

        return true;
    }

    @Symbol("appcircleEnterpriseAppStore")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        // Validate the artifact file extension only. No sensitive/back-end access, so no permission
        // check is needed here; the lgtm suppression tells the Jenkins security scan this is intentional.
        // Empty/required validation for the other fields is handled declaratively in config.jelly.
        @POST
        public FormValidation doCheckAppPath(@QueryParameter String value) { // lgtm[jenkins/no-permission-check]
            if (value.isEmpty()) return FormValidation.error("App Path cannot be empty");
            if (!value.matches(".*\\.(apk|aab|ipa)$")) {
                return FormValidation.error(
                        "Invalid file extension: For Android, use .apk or .aab. For iOS, use .ipa.");
            }
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.AppcircleEnterpriseStore_DescriptorImpl_DisplayName();
        }
    }
}

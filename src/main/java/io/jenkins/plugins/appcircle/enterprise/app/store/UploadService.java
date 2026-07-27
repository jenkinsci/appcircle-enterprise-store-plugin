package io.jenkins.plugins.appcircle.enterprise.app.store;

import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.FilePath;
import hudson.util.Secret;
import io.jenkins.plugins.appcircle.enterprise.app.store.Models.AppVersions;
import io.jenkins.plugins.appcircle.enterprise.app.store.Models.EnterpriseProfile;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.http.HttpEntity;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;

public class UploadService {
    public static final String DEFAULT_API_ENDPOINT = "https://api.appcircle.io";

    private static final int MAX_RETRIES = 5;

    // Stored as Secret so the bearer token is never held (or serialized) as plaintext.
    private final Secret authToken;
    String baseUrl;

    @DataBoundConstructor
    public UploadService(String authToken) {
        this(authToken, null);
    }

    public UploadService(String authToken, String apiEndpoint) {
        this.authToken = Secret.fromString(authToken);
        this.baseUrl = (apiEndpoint == null || apiEndpoint.trim().isEmpty())
                ? DEFAULT_API_ENDPOINT
                : apiEndpoint.trim().replaceAll("/+$", "");
    }

    public JSONObject uploadArtifact(FilePath artifact) throws IOException, InterruptedException {
        String fileName = artifact.getName();
        long fileSize = artifact.length();

        // 1) Request signed-URL upload information (size-validated).
        JSONObject uploadInfo = getUploadInformation(fileName, fileSize);
        String fileId = uploadInfo.optString("fileId");
        String uploadUrl = uploadInfo.optString("uploadUrl");
        JSONObject configuration = uploadInfo.optJSONObject("configuration");
        String httpMethod =
                (configuration != null && !configuration.optString("httpMethod").isEmpty())
                        ? configuration.optString("httpMethod").toUpperCase()
                        : "PUT";

        // 2) Upload the binary to the signed URL.
        if ("POST".equals(httpMethod)) {
            uploadViaPost(uploadUrl, artifact, fileName, configuration);
        } else {
            uploadViaPut(uploadUrl, artifact, fileSize);
        }

        // 3) Commit the upload. createNewProfile=true lets the server match the binary to its profile by package
        //    (adding a version to the existing profile, or creating one only if none exists).
        return commitFileUpload(fileId, fileName);
    }

    private JSONObject getUploadInformation(String fileName, long fileSize) throws IOException {
        try {
            URI uri = new URIBuilder(String.format("%s/store/v1/profiles/app-versions", this.baseUrl))
                    .addParameter("action", "uploadInformation")
                    .addParameter("fileName", fileName)
                    .addParameter("fileSize", String.valueOf(fileSize))
                    .build();

            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpGet request = new HttpGet(uri);
                request.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));
                request.setHeader("Accept", "application/json");

                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    String body = EntityUtils.toString(response.getEntity());
                    if (status < 200 || status >= 300) {
                        throw new IOException("Failed to retrieve file upload information (" + status + "): " + body);
                    }
                    return new JSONObject(body);
                }
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid upload information URI: " + e.getMessage(), e);
        }
    }

    private void uploadViaPut(String uploadUrl, FilePath artifact, long fileSize)
            throws IOException, InterruptedException {
        IOException lastError = null;
        long delayMillis = 1000;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            // Reopen the stream from the agent on every attempt so retries re-send from the start.
            try (CloseableHttpClient httpClient = HttpClients.createDefault();
                    InputStream in = artifact.read()) {
                HttpPut request = new HttpPut(uploadUrl);
                request.setEntity(new InputStreamEntity(in, fileSize, ContentType.APPLICATION_OCTET_STREAM));

                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    EntityUtils.consumeQuietly(response.getEntity());
                    if (status >= 200 && status < 300) {
                        return;
                    }
                    if (status != 503 || attempt >= MAX_RETRIES) {
                        throw new IOException("File upload failed with status code: " + status);
                    }
                    lastError = new IOException("File upload failed with status code: " + status);
                }
            } catch (NoHttpResponseException | SocketException e) {
                if (attempt >= MAX_RETRIES) {
                    throw e;
                }
                lastError = e;
            }

            sleepWithJitter(delayMillis);
            delayMillis *= 2;
        }

        throw lastError != null ? lastError : new IOException("File upload failed.");
    }

    private void uploadViaPost(String uploadUrl, FilePath artifact, String fileName, @Nullable JSONObject configuration)
            throws IOException, InterruptedException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault();
                InputStream in = artifact.read()) {
            HttpPost request = new HttpPost(uploadUrl);

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            JSONObject signParameters = configuration != null ? configuration.optJSONObject("signParameters") : null;
            if (signParameters != null) {
                for (String key : signParameters.keySet()) {
                    builder.addTextBody(key, signParameters.optString(key));
                }
            }
            // The file field MUST be appended last.
            builder.addBinaryBody("file", in, ContentType.APPLICATION_OCTET_STREAM, fileName);
            request.setEntity(builder.build());

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                EntityUtils.consumeQuietly(response.getEntity());
                if (status < 200 || status >= 300) {
                    throw new IOException("File upload failed with status code: " + status);
                }
            }
        }
    }

    private JSONObject commitFileUpload(String fileId, String fileName) throws IOException {
        try {
            URI uri = new URIBuilder(String.format("%s/store/v1/profiles/app-versions", this.baseUrl))
                    .addParameter("action", "commitFileUpload")
                    .addParameter("createNewProfile", "true")
                    .build();

            JSONObject payload = new JSONObject();
            payload.put("fileId", fileId);
            payload.put("fileName", fileName);

            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPost request = new HttpPost(uri);
                request.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));
                request.setHeader("Accept", "application/json");
                request.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));

                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    String body = EntityUtils.toString(response.getEntity());
                    if (status < 200 || status >= 300) {
                        throw new IOException("Commit failed with status code: " + status + ": " + body);
                    }
                    return new JSONObject(body);
                }
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid commit URI: " + e.getMessage(), e);
        }
    }

    private void sleepWithJitter(long delayMillis) throws IOException {
        try {
            // Add up to 300ms of random jitter so concurrent retries do not align.
            long jitter = ThreadLocalRandom.current().nextInt(300);
            Thread.sleep(delayMillis + jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload retry interrupted", ie);
        }
    }

    public Boolean publishEnterpriseAppVersion(
            String entProfileId, String entVersionId, String summary, String releaseNotes, String publishType)
            throws IOException {
        String url = String.format(
                "%s/store/v2/profiles/%s/app-versions/%s?action=publish", this.baseUrl, entProfileId, entVersionId);
        CloseableHttpClient httpClient = HttpClients.createDefault();

        HttpPatch httpPatch = new HttpPatch(url);
        httpPatch.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));
        httpPatch.setHeader("Content-Type", "application/json");

        JSONObject jsonBody = new JSONObject();
        jsonBody.put("summary", summary);
        jsonBody.put("releaseNotes", releaseNotes);
        jsonBody.put("publishType", publishType);

        StringEntity requestEntity = new StringEntity(jsonBody.toString(), "UTF-8");
        httpPatch.setEntity(requestEntity);

        try (CloseableHttpResponse response = httpClient.execute(httpPatch)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                return false;
            }

            return true;
        } catch (IOException e) {
            throw e;
        }
    }

    public AppVersions[] getAppVersions(String entProfileId) throws IOException {
        String url = String.format("%s/store/v2/profiles/%s/app-versions", this.baseUrl, entProfileId);
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpGet getRequest = new HttpGet(url);
        getRequest.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));
        getRequest.setHeader("Accept", "application/json");

        try (CloseableHttpResponse response = httpClient.execute(getRequest)) {
            String responseBody = EntityUtils.toString(response.getEntity());
            JSONArray profilesArray = new JSONArray(responseBody);

            AppVersions[] appVersions = new AppVersions[profilesArray.length()];
            for (int i = 0; i < profilesArray.length(); i++) {
                JSONObject profileObject = profilesArray.getJSONObject(i);
                String id = profileObject.optString("id");
                String dateToCompare;

                if (profileObject.has("updateDate")
                        && !profileObject.optString("updateDate").isEmpty()) {
                    dateToCompare = profileObject.optString("updateDate");
                } else if (profileObject.has("createDate")
                        && !profileObject.optString("createDate").isEmpty()) {
                    dateToCompare = profileObject.optString("createDate");
                } else {
                    throw new IOException("Error: Build publication failed due to unparseable app versions.");
                }

                ZonedDateTime zonedDateTime = ZonedDateTime.parse(dateToCompare, DateTimeFormatter.ISO_DATE_TIME);
                LocalDateTime updateDateTime = zonedDateTime.toLocalDateTime();
                appVersions[i] = new AppVersions(id, updateDateTime);
            }

            return appVersions;
        } catch (IOException e) {
            throw e;
        }
    }

    Boolean checkUploadStatus(String taskId) throws Exception {
        String url = String.format("%s/task/v1/tasks/%s", this.baseUrl, taskId);
        String result = "";

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            request.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    result = EntityUtils.toString(entity);
                }

                JSONObject jsonResponse = new JSONObject(result);
                @Nullable Integer stateValue = jsonResponse.optInt("stateValue", -1);
                @Nullable String stateName = jsonResponse.optString("stateName");

                if (stateName == null) {
                    throw new Exception("Upload Status Could Not Received");
                } else if (stateValue == 2) {
                    throw new Exception(taskId + " id upload request failed with status " + stateName);
                } else if (stateValue == 1) {
                    Thread.sleep(2000);
                    return checkUploadStatus(taskId);
                } else if (stateValue == 3) {
                    return true;
                }
            }
        } catch (Exception e) {
            throw e;
        }

        return true;
    }

    public EnterpriseProfile[] getEntProfiles() throws IOException {
        String url = String.format("%s/store/v2/profiles", this.baseUrl);
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpGet getRequest = new HttpGet(url);
        getRequest.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));
        getRequest.setHeader("Accept", "application/json");

        try (CloseableHttpResponse response = httpClient.execute(getRequest)) {
            String responseBody = EntityUtils.toString(response.getEntity());

            JSONArray profilesArray = new JSONArray(responseBody);

            List<EnterpriseProfile> profiles = new ArrayList<>();
            for (int i = 0; i < profilesArray.length(); i++) {
                JSONObject profileObject = profilesArray.getJSONObject(i);
                String id = profileObject.optString("id");
                String name = profileObject.optString("name");
                String lastBinaryReceivedDate = profileObject.optString("lastBinaryReceivedDate");

                LocalDateTime lastBinaryReceivedDateTime = LocalDateTime.MIN;
                if (lastBinaryReceivedDate != null && !lastBinaryReceivedDate.isEmpty()) {
                    ZonedDateTime zonedDateTime =
                            ZonedDateTime.parse(lastBinaryReceivedDate, DateTimeFormatter.ISO_DATE_TIME);
                    lastBinaryReceivedDateTime = zonedDateTime.toLocalDateTime();
                }

                profiles.add(new EnterpriseProfile(id, name, lastBinaryReceivedDateTime));
            }

            return profiles.toArray(new EnterpriseProfile[0]);
        } catch (IOException e) {
            throw e;
        }
    }

    public String getProfileId() throws IOException {
        EnterpriseProfile[] profiles = getEntProfiles();
        if (profiles.length == 0) {
            throw new IOException("Error: No Enterprise App Store profile was found for this organization.");
        }

        Arrays.sort(profiles, new Comparator<EnterpriseProfile>() {
            @Override
            public int compare(EnterpriseProfile o1, EnterpriseProfile o2) {
                return o2.getLastBinaryReceivedDate().compareTo(o1.getLastBinaryReceivedDate());
            }
        });

        return profiles[0].getId();
    }

    public String getLatestAppVersionId(String profileId) throws IOException {
        AppVersions[] versions = getAppVersions(profileId);
        if (versions.length == 0) {
            throw new IOException("Error: No app versions were found for the profile.");
        }

        Arrays.sort(versions, new Comparator<AppVersions>() {
            @Override
            public int compare(AppVersions o1, AppVersions o2) {
                return o2.getUpdateDate().compareTo(o1.getUpdateDate());
            }
        });

        return versions[0].getId();
    }
}

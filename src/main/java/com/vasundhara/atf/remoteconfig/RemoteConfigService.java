package com.vasundhara.atf.remoteconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.vasundhara.atf.model.RemoteConfigFlag;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Communicates with the Firebase Remote Config REST API to fetch and publish
 * Remote Config templates. Authentication uses a service-account JSON via the
 * Google Auth library; the access token is obtained fresh for every call.
 */
@Service
public class RemoteConfigService {

    private static final String RC_URL =
            "https://firebaseremoteconfig.googleapis.com/v1/projects/%s/remoteConfig";
    private static final String RC_SCOPE =
            "https://www.googleapis.com/auth/firebase.remoteconfig";

    private final ObjectMapper mapper;
    private final HttpClient http;

    public RemoteConfigService(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newHttpClient();
    }

    /**
     * Fetches the current Remote Config template for the given Firebase project.
     *
     * @param projectId          Firebase project ID (not the project number)
     * @param serviceAccountJson contents of the service-account JSON key file
     * @param log                consumer that receives progress messages
     * @return parsed flags, the raw template JSON and the ETag for conditional puts
     */
    public FetchResult fetch(String projectId, String serviceAccountJson,
                             Consumer<String> log) throws Exception {
        String token = getAccessToken(serviceAccountJson);

        String url = RC_URL.formatted(projectId);
        log.accept("GET " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept-Encoding", "identity")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        log.accept("Response: HTTP " + status);

        if (status < 200 || status >= 300) {
            throw new IllegalStateException(
                    "Firebase RC fetch failed with HTTP " + status + ": " + response.body());
        }

        String etag = response.headers().firstValue("ETag").orElse("*");
        String body = response.body();

        log.accept("Parsing Remote Config template…");
        List<RemoteConfigFlag> flags = parseFlags(body);
        log.accept("Parsed " + flags.size() + " parameter(s).");

        return new FetchResult(flags, body, etag);
    }

    /**
     * Applies {@code changes} to the stored template and PUTs it back to Firebase.
     *
     * @param projectId          Firebase project ID
     * @param serviceAccountJson service-account key file contents
     * @param templateJson       the raw JSON string obtained from the last fetch
     * @param changes            key → new default value pairs to update
     * @param etag               ETag from the last fetch (used for conditional put)
     * @param log                consumer that receives progress messages
     */
    public void publish(String projectId, String serviceAccountJson,
                        String templateJson, Map<String, String> changes,
                        String etag, Consumer<String> log) throws Exception {
        log.accept("Merging " + changes.size() + " change(s) into template…");

        JsonNode root = mapper.readTree(templateJson);
        ObjectNode parameters = root.has("parameters")
                ? (ObjectNode) root.get("parameters")
                : ((ObjectNode) root).putObject("parameters");

        for (Map.Entry<String, String> entry : changes.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            ObjectNode param;
            if (parameters.has(key) && parameters.get(key).isObject()) {
                param = (ObjectNode) parameters.get(key);
            } else {
                param = parameters.putObject(key);
            }

            ObjectNode defaultValue;
            if (param.has("defaultValue") && param.get("defaultValue").isObject()) {
                defaultValue = (ObjectNode) param.get("defaultValue");
            } else {
                defaultValue = param.putObject("defaultValue");
            }
            defaultValue.put("value", value);
            log.accept("  " + key + " → \"" + value + "\"");
        }

        String updatedJson = mapper.writeValueAsString(root);

        String token = getAccessToken(serviceAccountJson);

        String url = RC_URL.formatted(projectId);
        log.accept("PUT " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; UTF-8")
                .header("If-Match", etag)
                .PUT(HttpRequest.BodyPublishers.ofString(updatedJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        log.accept("Response: HTTP " + status);

        if (status < 200 || status >= 300) {
            throw new IllegalStateException(
                    "Firebase RC publish failed with HTTP " + status + ": " + response.body());
        }
    }

    /** Parsed result of a successful fetch. */
    public record FetchResult(List<RemoteConfigFlag> flags, String templateJson, String etag) {}

    // ---- private helpers ---------------------------------------------------

    private String getAccessToken(String serviceAccountJson) throws IOException {
        GoogleCredentials creds = GoogleCredentials
                .fromStream(new ByteArrayInputStream(
                        serviceAccountJson.getBytes(StandardCharsets.UTF_8)))
                .createScoped(RC_SCOPE);
        creds.refresh();
        return creds.getAccessToken().getTokenValue();
    }

    private List<RemoteConfigFlag> parseFlags(String templateJson) {
        List<RemoteConfigFlag> flags = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(templateJson);
            JsonNode parameters = root.get("parameters");
            if (parameters == null || !parameters.isObject()) return flags;

            Iterator<Map.Entry<String, JsonNode>> it = parameters.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                JsonNode param = entry.getValue();

                String defaultValue = "";
                JsonNode dv = param.get("defaultValue");
                if (dv != null && dv.has("value")) {
                    defaultValue = dv.get("value").asText("");
                }

                String valueType = "STRING";
                JsonNode vt = param.get("valueType");
                if (vt != null && !vt.isNull()) {
                    valueType = vt.asText("STRING");
                }

                String description = "";
                JsonNode desc = param.get("description");
                if (desc != null && !desc.isNull()) {
                    description = desc.asText("");
                }

                flags.add(new RemoteConfigFlag(key, defaultValue, valueType, description));
            }
        } catch (Exception e) {
            // Return whatever was parsed up to the failure point.
        }
        return flags;
    }
}

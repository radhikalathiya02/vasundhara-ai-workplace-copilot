package com.vasundhara.atf.smartexec.figma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vasundhara.atf.config.AtfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Figma REST API client — same shape/conventions as {@link com.vasundhara.atf.smartexec.SmartVisionClient}
 * (reads its token live from {@link AtfProperties}, every failure resolves to null/empty rather than
 * throwing, so a Figma comparison can never block or break a Smart Execution run). Only a Personal
 * Access Token is supported (https://www.figma.com/developers/api#access-tokens) — the file/frame
 * must be shared with that token's account.
 */
@Component
public class FigmaClient {

    private static final Logger log = LoggerFactory.getLogger(FigmaClient.class);
    private static final String BASE = "https://api.figma.com/v1";

    private final AtfProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public FigmaClient(AtfProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public boolean isEnabled() {
        return props.getFigmaApiToken() != null && !props.getFigmaApiToken().isBlank();
    }

    /**
     * The Figma document JSON for the given file, scoped to a single starting node when one was
     * given in the pasted URL (much lighter than downloading the whole file for a large design
     * system), or the whole file's document tree otherwise.
     *
     * @return the "document" node (whole file) or the single requested node, or null on any failure.
     */
    public JsonNode fetchDocument(String fileKey, String nodeId) {
        if (!isEnabled()) return null;
        try {
            if (nodeId != null && !nodeId.isBlank()) {
                JsonNode resp = get(BASE + "/files/" + fileKey + "/nodes?ids=" + urlEncode(nodeId));
                if (resp == null) return null;
                JsonNode nodes = resp.path("nodes");
                Iterator<String> keys = nodes.fieldNames();
                if (keys.hasNext()) return nodes.path(keys.next()).path("document");
                return null;
            }
            JsonNode resp = get(BASE + "/files/" + fileKey);
            return resp == null ? null : resp.path("document");
        } catch (Exception e) {
            log.debug("Figma fetchDocument failed: {}", e.toString());
            return null;
        }
    }

    /** Renders the given node ids as PNGs (scale 2x for retina-comparable detail) and returns a
     *  nodeId → download URL map (the URLs are short-lived, per Figma's API — download promptly). */
    public Map<String, String> renderImageUrls(String fileKey, List<String> nodeIds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!isEnabled() || nodeIds == null || nodeIds.isEmpty()) return out;
        try {
            String ids = String.join(",", nodeIds);
            JsonNode resp = get(BASE + "/images/" + fileKey + "?ids=" + urlEncode(ids) + "&format=png&scale=2");
            if (resp == null) return out;
            JsonNode images = resp.path("images");
            Iterator<String> keys = images.fieldNames();
            while (keys.hasNext()) {
                String k = keys.next();
                String url = images.path(k).asText(null);
                if (url != null && !url.isBlank()) out.put(k, url);
            }
        } catch (Exception e) {
            log.debug("Figma renderImageUrls failed: {}", e.toString());
        }
        return out;
    }

    /** Downloads a rendered image from the short-lived URL {@link #renderImageUrls} returned. */
    public byte[] download(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            log.debug("Figma image download failed: {}", e.toString());
            return null;
        }
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("X-Figma-Token", props.getFigmaApiToken()).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("Figma API HTTP {} for {}", resp.statusCode(), url);
            return null;
        }
        return mapper.readTree(resp.body());
    }

    private static String urlEncode(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }
}

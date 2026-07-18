package com.vasundhara.atf.smartexec.figma;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a pasted Figma file/frame URL into the file key + optional starting node id that the
 *  REST API needs. Handles both URL shapes Figma has used ({@code /file/...} and {@code /design/...}),
 *  with or without a {@code node-id} query param (a specific frame link vs. the whole file). */
public final class FigmaUrlParser {
    private FigmaUrlParser() {}

    public record Parsed(String fileKey, String nodeId) {}

    private static final Pattern FILE_KEY = Pattern.compile("figma\\.com/(?:file|design|proto)/([a-zA-Z0-9]+)");
    private static final Pattern NODE_ID = Pattern.compile("[?&]node-id=([^&]+)");

    /** Returns null if the URL doesn't look like a Figma file/design link at all. */
    public static Parsed parse(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher fm = FILE_KEY.matcher(url);
        if (!fm.find()) return null;
        String fileKey = fm.group(1);
        String nodeId = null;
        Matcher nm = NODE_ID.matcher(url);
        if (nm.find()) {
            // Figma URLs encode the node id with a hyphen (e.g. "12-34"); the API wants "12:34".
            nodeId = java.net.URLDecoder.decode(nm.group(1), java.nio.charset.StandardCharsets.UTF_8).replace('-', ':');
        }
        return new Parsed(fileKey, nodeId);
    }
}

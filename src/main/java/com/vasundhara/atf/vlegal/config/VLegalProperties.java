package com.vasundhara.atf.vlegal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "vlegal")
public class VLegalProperties {

    private Gemini gemini = new Gemini();
    private UrlFetch urlFetch = new UrlFetch();

    public Gemini getGemini() { return gemini; }
    public void setGemini(Gemini gemini) { this.gemini = gemini; }
    public UrlFetch getUrlFetch() { return urlFetch; }
    public void setUrlFetch(UrlFetch urlFetch) { this.urlFetch = urlFetch; }

    public static class Gemini {
        private String apiKey = "YOUR_GEMINI_API_KEY_HERE";
        private String model = "gemini-2.0-flash";
        private String baseUrl = "https://generativelanguage.googleapis.com";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public static class UrlFetch {
        private int timeoutSeconds = 10;
        private String userAgent = "Mozilla/5.0 (compatible; VasundharaLegalBot/1.0)";

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    }
}

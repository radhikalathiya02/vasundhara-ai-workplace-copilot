package com.vasundhara.atf.vlegal.dto;

public class VerifyUrlRequest {

    private String url;
    private String rawText; // fallback when URL fetch fails or user pastes text directly

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
}

package com.vasundhara.atf.vlegal.service;

import com.vasundhara.atf.vlegal.dto.DocumentRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PromptBuilder {

    // Maps dataCollected item → list of [PERMISSION_NAME, description]
    private static final Map<String, List<String[]>> PERMISSION_MAP = new LinkedHashMap<>();
    static {
        List<String[]> loc = new ArrayList<>();
        loc.add(new String[]{"ACCESS_FINE_LOCATION", "Allows the app to get your precise location using GPS and network-based location providers."});
        loc.add(new String[]{"ACCESS_COARSE_LOCATION", "Allows the app to get your approximate location using Wi-Fi, Bluetooth, or cellular networks."});
        loc.add(new String[]{"ACCESS_BACKGROUND_LOCATION", "Allows the app to access your location even when the app is running in the background."});
        PERMISSION_MAP.put("Location", loc);

        List<String[]> cam = new ArrayList<>();
        cam.add(new String[]{"CAMERA", "Allows the app to take photos and videos using the device camera."});
        PERMISSION_MAP.put("Camera", cam);

        List<String[]> contacts = new ArrayList<>();
        contacts.add(new String[]{"READ_CONTACTS", "Allows the app to read data about your contacts stored on your phone."});
        contacts.add(new String[]{"WRITE_CONTACTS", "Allows the app to modify data about your contacts stored on your phone."});
        PERMISSION_MAP.put("Contacts", contacts);

        List<String[]> phone = new ArrayList<>();
        phone.add(new String[]{"READ_PHONE_STATE", "Allows the app to access the phone features including phone number, network state, and ongoing calls."});
        phone.add(new String[]{"CALL_PHONE", "Allows the app to initiate phone calls without requiring the user to use the dialer interface."});
        PERMISSION_MAP.put("Phone", phone);

        List<String[]> storage = new ArrayList<>();
        storage.add(new String[]{"READ_EXTERNAL_STORAGE", "Allows the app to read the contents of your device external storage."});
        storage.add(new String[]{"WRITE_EXTERNAL_STORAGE", "Allows the app to write to the device external storage."});
        storage.add(new String[]{"MANAGE_EXTERNAL_STORAGE", "Allows the app to have broad access to external storage on the device."});
        PERMISSION_MAP.put("Storage", storage);

        List<String[]> mic = new ArrayList<>();
        mic.add(new String[]{"RECORD_AUDIO", "Allows the app to record audio using the device microphone."});
        PERMISSION_MAP.put("Microphone", mic);

        List<String[]> health = new ArrayList<>();
        health.add(new String[]{"ACTIVITY_RECOGNITION", "Allows the app to recognize your physical activity such as walking, running, and cycling."});
        health.add(new String[]{"BODY_SENSORS", "Allows the app to access data from sensors that measure body activity such as heart rate."});
        PERMISSION_MAP.put("Health", health);

        List<String[]> pay = new ArrayList<>();
        pay.add(new String[]{"BIND_NFC_SERVICE", "Allows the app to use NFC-based payment and secure element services."});
        PERMISSION_MAP.put("Payment Info", pay);
    }

    // Maps third-party service → description for Section 8
    private static final Map<String, String> SERVICE_DESC = Map.of(
        "Firebase",           "Firebase (Google) — Realtime Database, Firestore, Authentication, and Cloud Messaging for data storage, sync, and push notifications.",
        "Google Analytics",   "Google Analytics — Collects anonymized usage statistics to help us understand how users interact with the app.",
        "AdMob",              "AdMob (Google) — Displays in-app advertisements. AdMob may use device identifiers and usage data to serve personalized ads.",
        "Stripe",             "Stripe — Processes in-app payments securely. Stripe handles all payment card data under PCI-DSS compliance.",
        "Facebook SDK",       "Facebook SDK — Enables social login, sharing features, and Facebook audience analytics.",
        "Crashlytics",        "Firebase Crashlytics — Collects crash reports and app stability metrics to help us identify and fix bugs quickly."
    );

    public String buildPrivacyPolicyPrompt(DocumentRequest req) {
        return buildDocumentPrompt(req, "Privacy Policy");
    }

    public String buildTermsOfServicePrompt(DocumentRequest req) {
        return buildDocumentPrompt(req, "Terms of Service");
    }

    private String buildDocumentPrompt(DocumentRequest req, String docType) {
        String permissionsSection = buildPermissionsBlock(req.getDataCollected());
        String servicesSection    = buildServicesBlock(req.getThirdPartyServices());
        String platforms          = String.join(", ", req.getPlatforms());
        String regions            = String.join(", ", req.getTargetRegions());
        String data               = req.getDataCollected().isEmpty() ? "None" : String.join(", ", req.getDataCollected());
        String services           = req.getThirdPartyServices().isEmpty() ? "None" : String.join(", ", req.getThirdPartyServices());

        boolean isPrivacy = "Privacy Policy".equals(docType);

        String sectionTwelveHeading = isPrivacy
                ? "12. Changes to this Privacy Policy"
                : "12. Changes to this Terms of Service";

        String sectionElevenText = isPrivacy
                ? "Our app may contain links to other websites. If you click on a third-party link, you will be directed to that site. Note that these external sites are not operated by us. Therefore, we strongly advise you to review the Privacy Policy of these websites. We have no control over and assume no responsibility for the content, privacy policies, or practices of any third-party sites or services."
                : "Our app may contain links to other websites. If you click on a third-party link, you will be directed to that site. Note that these external sites are not operated by us. Therefore, we strongly advise you to review the Terms of Service of these websites. We have no control over and assume no responsibility for the content, terms, or practices of any third-party sites or services.";

        return """
You are a legal document generator for Vasundhara Infotech LLP, a software services company that builds mobile apps and websites.
Your task is to generate a COMPLETE %s document for the app "%s" following the EXACT Vasundhara Infotech LLP style and structure described below.

===== APP DETAILS =====
App Name: %s
App Type: %s
Platforms: %s
Data Collected: %s
Third-Party Services: %s
Target Regions: %s
Has In-App Purchases: %s
Has User Accounts: %s
Has Ads: %s
Has Social Features: %s
Target Age Group: %s
Contact Email: %s

===== REQUIRED DOCUMENT STRUCTURE =====
Follow this structure EXACTLY. Every section must be present and substantive.

INTRO PARAGRAPH:
"With the given policy, we notify the laws, rules, limitations, conditions, and %s when you use our app."

SECTION 1 - %s:
State that this policy applies to ALL apps published by "Vasundhara Infotech LLP".
Include: This policy informs you about our policies regarding the collection, use and disclosure of personal information when you use our services. By using our service, you agree to the collection and use of information in accordance with this policy. %s

SECTION 2 - There are the following sections in this %s:
Include three sub-sections:
A) Information You Give Us:
   - Account Info: %s
   - Transaction Info: %s
   - Other Info: Any other information you voluntarily provide through contact forms, feedback, or customer support.
B) Information We Collect Automatically:
   - Log Info: When you use our %s, our servers automatically record information including your device's IP address, browser type, pages visited, time spent, and error information.
   - Mobile Device Info: We may collect information about your mobile device including device model, operating system version, unique device identifiers, and mobile network information.
   - Cookies and Tracking Technologies: We use cookies and similar tracking technologies to track activity on our service and hold certain information to improve your experience.
C) Information From Other Sources: %s

SECTION 3 - Use of Information:
Provide EXACTLY 10 bullet points (a) through (j) explaining how we use collected data.
Include points about: improving service, personalizing experience, processing transactions%s, sending notifications%s, analytics, customer support, security, legal compliance, research, and communication.

SECTION 4 - Sharing of Information:
Provide EXACTLY 7 bullet points (a) through (g) about when/how we share data.
Include: service providers, business transfers, legal requirements, protection of rights, aggregated/anonymized data, affiliates, and third-party integrations.

SECTION 5 - Social Sharing Features:
%s

SECTION 6 - Security:
Explain that we use commercially acceptable means to protect personal information including encryption, secure servers, and regular security audits. Acknowledge that no method of transmission over the internet is 100%% secure.

SECTION 7 - Sensitive Information:
List the Android permissions the app uses with descriptions. Use this exact format for each: PERMISSION_NAME - description
Permissions:
%s
If no special permissions: State that this app does not request sensitive permissions beyond standard functionality.

SECTION 8 - Advertising and Analytics Services:
%s

SECTION 9 - Your Choices:
Include these sub-sections: (a) Account - how to update/delete (b) Location - location permission control (c) Native Apps - platform-level permission management (d) Cookies - browser/app cookie controls (e) Promotional Communications - opt-out options (f) Push Notifications - notification settings

SECTION 10 - Children's Privacy:
State that our services are not directed to children under the age of 13. We do not knowingly collect personal information from children under 13. If a parent or guardian becomes aware that their child has provided us with personal information, they should contact us. If we become aware that a child under 13 has provided us with personal information, we will take steps to remove that information and terminate the child's account. Parental consent is required for users under 13.

SECTION 11 - Links To Other Sites:
%s

SECTION 12 - %s:
We may update our %s from time to time. We will notify you of any changes by posting the new policy on this page. You are advised to review this %s periodically for any changes. Changes are effective when they are posted on this page.

SECTION 13 - Contact Information:
Vasundhara Infotech LLP
Email: %s

End with: "Thank You"

===== STYLE RULES =====
- Company name is ALWAYS "Vasundhara Infotech LLP" (NEVER LLC, NEVER anything else)
- Formal but slightly repetitive tone (intentional — matches Vasundhara brand)
- Section headings use Title Case
- Sub-sections use (a), (b), (c) format
- Bullet points use - (a), - (b) format
- Every document MUST end with "Thank You" on its own line
- Write at least 1000 words total

===== OUTPUT FORMAT =====
Return ONLY valid JSON. No markdown. No explanation. No extra text before or after the JSON.

{
  "title": "%s %s",
  "sections": [
    {
      "heading": "1. %s",
      "content": "<intro paragraph + full section 1 text>",
      "subSections": []
    },
    {
      "heading": "2. There are the following sections in this %s",
      "content": "<overview sentence>",
      "subSections": [
        {"heading": "A) Information You Give Us", "content": "<full content>", "subSections": []},
        {"heading": "B) Information We Collect Automatically", "content": "<full content>", "subSections": []},
        {"heading": "C) Information From Other Sources", "content": "<full content>", "subSections": []}
      ]
    },
    {"heading": "3. Use of Information", "content": "<10 bullet points a-j>", "subSections": []},
    {"heading": "4. Sharing of Information", "content": "<7 bullet points a-g>", "subSections": []},
    {"heading": "5. Social Sharing Features", "content": "<content>", "subSections": []},
    {"heading": "6. Security", "content": "<content>", "subSections": []},
    {"heading": "7. Sensitive Information", "content": "<permissions list>", "subSections": []},
    {"heading": "8. Advertising and Analytics Services", "content": "<content>", "subSections": []},
    {"heading": "9. Your Choices", "content": "<content with a-f sub-points>", "subSections": []},
    {"heading": "10. Children's Privacy", "content": "<content>", "subSections": []},
    {"heading": "11. Links To Other Sites", "content": "<content>", "subSections": []},
    {"heading": "%s", "content": "<content>", "subSections": []},
    {"heading": "13. Contact Information", "content": "Vasundhara Infotech LLP\\nEmail: %s\\n\\nThank You", "subSections": []}
  ],
  "complianceTags": [],
  "wordCount": <actual word count as integer>
}
""".formatted(
            docType, req.getAppName(),
            req.getAppName(), req.getAppType(), platforms, data, services, regions,
            req.isHasInAppPurchases(), req.isHasUserAccounts(), req.isHasAds(), req.isHasSocialFeatures(),
            req.getTargetAgeGroup(), req.getContactEmail(),
            docType.toLowerCase(),
            docType,
            docType.equals("Privacy Policy")
                ? "We may also collect information that you provide voluntarily."
                : "Your continued use of our service constitutes your acceptance of these terms.",
            docType.toLowerCase(),
            req.isHasUserAccounts()
                ? "If you create an account, we collect your name, email address, username, and password."
                : "This app does not require account registration.",
            req.isHasInAppPurchases()
                ? "If you make in-app purchases, we collect billing information including payment method details (processed securely through our payment partners)."
                : "This app does not process financial transactions.",
            req.getAppType().toLowerCase(),
            req.isHasSocialFeatures()
                ? "If you connect your social media accounts, we may receive profile information and friend lists from those platforms."
                : "We do not collect information from third-party social media platforms.",
            req.isHasInAppPurchases() ? ", processing payments" : "",
            req.isHasAds() ? ", delivering relevant advertisements" : "",
            req.isHasSocialFeatures()
                ? "Our app offers social sharing features that allow you to share content with friends and family. When you use these features, information may be shared with the relevant social media platform according to their privacy policies. You can control social sharing through your account settings."
                : "Our app does not include social sharing features at this time.",
            permissionsSection.isEmpty() ? "No special sensitive permissions are required by this application beyond standard functionality." : permissionsSection,
            servicesSection.isEmpty() ? "This app does not use any third-party advertising or analytics services." : servicesSection,
            sectionElevenText,
            sectionTwelveHeading,
            docType, docType,
            req.getContactEmail(),
            req.getAppName(), docType,
            docType,
            docType.toLowerCase(),
            sectionTwelveHeading,
            req.getContactEmail()
        );
    }

    public String buildVerifyPrompt(String content, String sourceDesc) {
        return """
You are a legal compliance expert specializing in app privacy policies and terms of service.
Analyze the following legal document and return a compliance report.

SOURCE: %s
DOCUMENT CONTENT:
---
%s
---

Evaluate this document against standard compliance requirements including GDPR, CCPA, COPPA, DPDP India, and general best practices for Privacy Policies and Terms of Service documents.

Check for these standard clauses:
1. Data Collection disclosure
2. Data Usage explanation
3. Data Sharing / Third-party disclosure
4. User Rights (access, delete, correct data)
5. Children's Privacy (COPPA / age restrictions)
6. Security measures description
7. Contact Information
8. Changes/Updates policy
9. Cookie/Tracking policy
10. Data Retention period
11. Legal basis for processing (GDPR)
12. Opt-out mechanisms
13. Third-party links disclaimer
14. Consent mechanism
15. Governing law / Jurisdiction

Return ONLY valid JSON. No markdown. No extra text.

{
  "complianceScore": <0-100 integer>,
  "riskLevel": "<LOW|MEDIUM|HIGH>",
  "documentType": "<Privacy Policy|Terms of Service|Combined|Unknown>",
  "presentClauses": ["<clause name>", ...],
  "missingClauses": [
    {"clause": "<clause name>", "severity": "<LOW|MEDIUM|HIGH|CRITICAL>", "description": "<why this matters>"},
    ...
  ],
  "recommendedFixes": ["<actionable fix>", ...]
}
""".formatted(sourceDesc, content.length() > 8000 ? content.substring(0, 8000) + "\n[truncated...]" : content);
    }

    private String buildPermissionsBlock(List<String> dataCollected) {
        if (dataCollected == null || dataCollected.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (String data : dataCollected) {
            List<String[]> perms = PERMISSION_MAP.get(data);
            if (perms != null) {
                for (String[] p : perms) {
                    lines.add(p[0] + " - " + p[1]);
                }
            }
        }
        return String.join("\n", lines);
    }

    private String buildServicesBlock(List<String> services) {
        if (services == null || services.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (String svc : services) {
            String desc = SERVICE_DESC.get(svc);
            if (desc != null) lines.add("- " + desc);
            else lines.add("- " + svc + " — Third-party service used to enhance app functionality.");
        }
        return String.join("\n", lines);
    }
}

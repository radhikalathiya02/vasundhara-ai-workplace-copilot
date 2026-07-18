package com.vasundhara.atf.analysis;

import net.dongliu.apk.parser.ApkFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Best-effort deep-link (custom URI scheme / App Links host) detector, scoped entirely to the
 * "Analyze APK" feature — deliberately independent of {@code ApkAnalyzer}/{@code ApkInfo} so
 * this new feature can never regress the widely-used static-analysis pipeline those classes
 * feed (Security/Compatibility/Ads categories). Scans {@code <intent-filter>} blocks for a
 * {@code VIEW}+{@code BROWSABLE} action/category pair with a {@code <data>} scheme/host.
 */
public class DeepLinkScanner {

    private static final Logger log = LoggerFactory.getLogger(DeepLinkScanner.class);

    public record DeepLink(String scheme, String host, String activity) {}

    public List<DeepLink> scan(File apkFile) {
        List<DeepLink> found = new ArrayList<>();
        try (ApkFile apk = new ApkFile(apkFile)) {
            String xml = apk.getManifestXml();
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList activities = doc.getElementsByTagName("activity");
            for (int i = 0; i < activities.getLength(); i++) {
                Element act = (Element) activities.item(i);
                String name = act.getAttribute("android:name");
                NodeList filters = act.getElementsByTagName("intent-filter");
                for (int j = 0; j < filters.getLength(); j++) {
                    Element filter = (Element) filters.item(j);
                    boolean isView = hasChildWithAttr(filter, "action", "android:name", "android.intent.action.VIEW");
                    boolean isBrowsable = hasChildWithAttr(filter, "category", "android:name", "android.intent.category.BROWSABLE");
                    if (!isView || !isBrowsable) continue;

                    NodeList dataNodes = filter.getElementsByTagName("data");
                    Set<String> schemes = new LinkedHashSet<>();
                    Set<String> hosts = new LinkedHashSet<>();
                    for (int k = 0; k < dataNodes.getLength(); k++) {
                        Element data = (Element) dataNodes.item(k);
                        String scheme = data.getAttribute("android:scheme");
                        String host = data.getAttribute("android:host");
                        if (!scheme.isBlank()) schemes.add(scheme);
                        if (!host.isBlank()) hosts.add(host);
                    }
                    if (schemes.isEmpty()) continue;
                    for (String scheme : schemes) {
                        found.add(new DeepLink(scheme, hosts.isEmpty() ? "" : String.join(", ", hosts), name));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Deep-link scan skipped due to an error: {}", e.toString());
        }
        return found;
    }

    private boolean hasChildWithAttr(Element parent, String tag, String attr, String value) {
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (value.equals(el.getAttribute(attr))) return true;
        }
        return false;
    }
}

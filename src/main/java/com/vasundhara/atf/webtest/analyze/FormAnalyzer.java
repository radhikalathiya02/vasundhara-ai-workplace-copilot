package com.vasundhara.atf.webtest.analyze;

import com.vasundhara.atf.model.Severity;
import com.vasundhara.atf.webtest.crawl.CrawledPage;
import com.vasundhara.atf.webtest.model.WebIssue;
import com.vasundhara.atf.webtest.model.WebIssueCategory;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Static analysis of every form and interactive input on a page — the counterpart to the QA
 * discipline of manually testing login/registration/search/contact/payment forms. Works purely
 * from the parsed DOM (no submission, no side effects), covering the aspects a professional
 * form-usability audit checks: accessible labels, correct input types &amp; validation
 * attributes, required-field and length constraints, autocomplete tokens, keyboard/tab order,
 * file-upload/OTP/date affordances, and unsafe credential handling.
 *
 * <p>Live client-side validation behaviour (empty-submit, {@code checkValidity()}, focus order)
 * is verified separately and non-destructively in the browser tier
 * ({@code WebBrowserService.collectForms}); this class provides the deterministic structural pass
 * that always runs.
 */
@Component
public class FormAnalyzer {

    /** Control types that don't need a visible/associated text label. */
    private static final Set<String> UNLABELLED_TYPES = Set.of(
            "hidden", "submit", "button", "reset", "image");

    /** Cap per-control findings on a single page so a huge form can't flood the report. */
    private static final int PER_PAGE_CONTROL_CAP = 20;

    public List<WebIssue> analyze(CrawledPage page) {
        List<WebIssue> out = new ArrayList<>();
        if (!page.isHtml()) return out;
        Document doc = page.getDoc();
        String url = page.getFinalUrl();

        Elements forms = doc.select("form");
        // Also consider stray controls not wrapped in a <form> (common in SPA/JS-driven UIs).
        Elements orphanControls = doc.select("input, textarea, select").stream()
                .filter(c -> c.closest("form") == null)
                .collect(Elements::new, Elements::add, Elements::addAll);

        if (forms.isEmpty() && orphanControls.isEmpty()) return out;

        int controlFindings = 0;
        int formIndex = 0;

        for (Element form : forms) {
            formIndex++;
            controlFindings += analyzeForm(form, formIndex, url, out, controlFindings);
        }

        // Orphan controls (inputs outside any <form>).
        int oi = 0;
        for (Element c : orphanControls) {
            if (controlFindings >= PER_PAGE_CONTROL_CAP) break;
            oi++;
            controlFindings += analyzeControl(c, "input#" + (c.id().isBlank() ? oi : c.id()), url, out, false);
        }

        return out;
    }

    /** @return number of per-control findings added (for the page-level cap). */
    private int analyzeForm(Element form, int formIndex, String url, List<WebIssue> out, int already) {
        int added = 0;
        String formSel = form.id().isBlank() ? "form:nth-of-type(" + formIndex + ")" : "form#" + form.id();
        String method = form.attr("method").toLowerCase(Locale.ROOT);
        boolean hasPassword = !form.select("input[type=password]").isEmpty();
        boolean isSearch = "search".equalsIgnoreCase(form.attr("role"))
                || !form.select("input[type=search]").isEmpty();

        // --- Credentials submitted via GET (would end up in the URL / history / logs) ---
        if (hasPassword && method.equals("get")) {
            out.add(form(Severity.HIGH, "Login form submits credentials via GET",
                    "The form containing a password field uses method=\"get\", so the username and password are placed in the URL query string.",
                    "Change the form method to POST (and submit over HTTPS).", url)
                    .impact("Passwords in the URL are stored in browser history, server access logs, proxy logs and the Referer header — a serious credential-leak vector.")
                    .rootCause("<form method=\"get\"> combined with an <input type=\"password\">.")
                    .standard("OWASP Authentication Cheat Sheet")
                    .element(formSel)
                    .codeFix("<form method=\"post\" action=\"/login\">\n  …\n</form>"));
        }

        // --- novalidate disables built-in validation ---
        if (form.hasAttr("novalidate")) {
            out.add(form(Severity.LOW, "Form disables native validation (novalidate)",
                    "The form sets the novalidate attribute, turning off the browser's built-in HTML5 validation.",
                    "Remove novalidate unless equivalent JavaScript validation is guaranteed, and always validate on the server.", url)
                    .impact("Users get no immediate feedback on invalid/empty required fields before submission, and the safety net of type/pattern checks is lost.")
                    .rootCause("The <form> carries the novalidate attribute.")
                    .standard("HTML Living Standard — constraint validation")
                    .element(formSel));
        }

        // --- Missing submit control ---
        if (form.select("button, input[type=submit], input[type=image]").isEmpty()) {
            out.add(form(Severity.LOW, "Form has no submit control",
                    "The form contains inputs but no <button>/<input type=\"submit\">.",
                    "Add an explicit submit button so the form is operable by keyboard and assistive tech.", url)
                    .impact("Keyboard and screen-reader users may be unable to submit the form; relying on Enter-in-field or JS-only handlers is fragile.")
                    .rootCause("No submit-capable element inside the <form>.")
                    .standard("WCAG 2.1 SC 2.1.1 (Keyboard)")
                    .element(formSel));
        }

        // --- Duplicate control names (radio/checkbox groups excepted) ---
        detectDuplicateNames(form, formSel, url, out);

        // --- Per-control checks ---
        Elements controls = form.select("input, textarea, select");
        int idx = 0;
        for (Element c : controls) {
            if (already + added >= PER_PAGE_CONTROL_CAP) {
                out.add(form(Severity.INFO, "Additional form controls not individually listed",
                        "More interactive controls exist on this page than are individually reported (report capped at "
                                + PER_PAGE_CONTROL_CAP + " control findings).",
                        "Review the remaining controls against the same checklist (labels, types, validation, autocomplete).", url)
                        .impact("Some control-level issues on this page are summarised rather than listed individually to keep the report readable.")
                        .element(formSel));
                break;
            }
            idx++;
            String sel = controlSelector(c, formSel, idx);
            added += analyzeControl(c, sel, url, out, isSearch);
        }
        return added;
    }

    /** @return number of findings added for this control. */
    private int analyzeControl(Element c, String sel, String url, List<WebIssue> out, boolean inSearch) {
        String tag = c.tagName().toLowerCase(Locale.ROOT);
        String type = c.attr("type").toLowerCase(Locale.ROOT);
        if (tag.equals("input") && type.isBlank()) type = "text";
        int before = out.size();

        // Skip controls that legitimately need no text label.
        boolean needsLabel = !(tag.equals("input") && UNLABELLED_TYPES.contains(type));

        // --- Accessible label ---
        if (needsLabel && !hasAccessibleLabel(c)) {
            String placeholder = c.attr("placeholder");
            boolean placeholderOnly = !placeholder.isBlank();
            out.add(form(Severity.MEDIUM,
                    placeholderOnly ? "Input uses placeholder instead of a label" : "Form control has no accessible label",
                    placeholderOnly
                            ? "The " + describe(tag, type) + " has a placeholder (\"" + trunc(placeholder, 40)
                                + "\") but no associated <label>, aria-label or aria-labelledby."
                            : "The " + describe(tag, type) + " has no <label for>, wrapping <label>, aria-label, aria-labelledby or title.",
                    placeholderOnly
                            ? "Add a real <label> (placeholders disappear on input and aren't reliably announced)."
                            : "Associate a <label> with the control, or add aria-label / aria-labelledby.", url)
                    .impact("Screen-reader users hear an unlabelled control (\"edit text\") and can't tell what to enter; clicking the label text also won't focus the field.")
                    .rootCause(placeholderOnly
                            ? "A placeholder is being used as the label — placeholders vanish once typing starts and have poor contrast/AT support."
                            : "The control is not associated with any accessible name.")
                    .standard("WCAG 2.1 SC 1.3.1, 3.3.2, 4.1.2")
                    .element(sel)
                    .codeFix("<label for=\"" + labelId(c) + "\">Field label</label>\n<"
                            + tag + (tag.equals("input") ? " type=\"" + type + "\"" : "")
                            + " id=\"" + labelId(c) + "\" name=\"" + labelId(c) + "\">"));
        }

        // --- Missing name (control won't be submitted) ---
        if ((tag.equals("input") || tag.equals("textarea") || tag.equals("select"))
                && c.attr("name").isBlank() && !UNLABELLED_TYPES.contains(type) && c.id().isBlank()) {
            out.add(form(Severity.LOW, "Form control has no name",
                    "The " + describe(tag, type) + " has no name attribute, so its value is never sent on submit.",
                    "Add a name attribute (or confirm the value is handled purely client-side).", url)
                    .impact("The field appears interactive but its data is silently dropped on submission — a common cause of \"my input wasn't saved\" bugs.")
                    .rootCause("No name attribute on a data-bearing control.")
                    .standard("HTML Living Standard — form submission")
                    .element(sel));
        }

        // --- Type/validation heuristics by field purpose ---
        String idName = (c.attr("name") + " " + c.id() + " " + c.attr("placeholder")
                + " " + c.attr("autocomplete")).toLowerCase(Locale.ROOT);

        if (tag.equals("input")) {
            if (type.equals("text") && matches(idName, "email", "e-mail")) {
                out.add(typeSuggestion(c, sel, url, "email", "type=\"email\"",
                        "email address", "<input type=\"email\" name=\"email\" autocomplete=\"email\" required>",
                        "Browsers can't validate the address format or show the @-optimised keyboard on mobile."));
            }
            if (type.equals("text") && matches(idName, "phone", "mobile", "tel")) {
                out.add(typeSuggestion(c, sel, url, "tel", "type=\"tel\" with inputmode/pattern",
                        "phone number", "<input type=\"tel\" name=\"phone\" inputmode=\"tel\" autocomplete=\"tel\" pattern=\"[0-9+ ()-]{7,}\">",
                        "Mobile users don't get the numeric dial pad and the value isn't format-validated."));
            }
            if (type.equals("text") && matches(idName, "otp", "one-time", "onetime", "verification code", "verificationcode", "passcode")) {
                out.add(form(Severity.LOW, "OTP field lacks one-time-code affordances",
                        "A one-time-code / OTP field is a plain text input.",
                        "Use inputmode=\"numeric\", autocomplete=\"one-time-code\" and a pattern.", url)
                        .impact("Users don't get the numeric keypad, and iOS/Android can't auto-fill the SMS code, adding friction to 2FA.")
                        .rootCause("The OTP input is missing inputmode/autocomplete=one-time-code.")
                        .standard("WHATWG autocomplete tokens · WebOTP")
                        .element(sel)
                        .codeFix("<input type=\"text\" inputmode=\"numeric\" autocomplete=\"one-time-code\" pattern=\"[0-9]{4,8}\" maxlength=\"6\">"));
            }
            if (type.equals("text") && matches(idName, "url", "website")) {
                out.add(typeSuggestion(c, sel, url, "url", "type=\"url\"", "URL",
                        "<input type=\"url\" name=\"website\" autocomplete=\"url\">",
                        "The URL format isn't validated by the browser."));
            }

            // File upload without accept filter.
            if (type.equals("file") && c.attr("accept").isBlank()) {
                out.add(form(Severity.INFO, "File upload has no accept filter",
                        "The file input has no accept attribute, so the picker offers every file type.",
                        "Set accept to the expected MIME types/extensions to guide users and reduce invalid uploads.", url)
                        .impact("Users can pick unsupported files, only discovering the problem after a failed/slow upload; server-side validation is still required regardless.")
                        .rootCause("<input type=\"file\"> has no accept attribute.")
                        .standard("HTML Living Standard — the accept attribute")
                        .element(sel)
                        .codeFix("<input type=\"file\" accept=\".pdf,image/png,image/jpeg\">"));
            }

            // Password field hygiene.
            if (type.equals("password")) {
                String ac = c.attr("autocomplete").toLowerCase(Locale.ROOT);
                if (ac.isBlank()) {
                    out.add(form(Severity.LOW, "Password field missing autocomplete hint",
                            "The password input has no autocomplete token (current-password / new-password).",
                            "Add autocomplete=\"current-password\" (login) or \"new-password\" (signup/reset).", url)
                            .impact("Password managers can't reliably fill or generate a password, pushing users toward weak, reused passwords.")
                            .rootCause("No autocomplete token on <input type=\"password\">.")
                            .standard("WHATWG autocomplete tokens · OWASP Authentication")
                            .element(sel)
                            .codeFix("<input type=\"password\" autocomplete=\"current-password\">  <!-- or new-password -->"));
                }
                if (!c.hasAttr("minlength")) {
                    out.add(form(Severity.INFO, "Password field has no minimum length",
                            "The password input has no minlength constraint.",
                            "Add minlength (e.g. 8) as a first-line check; always enforce the real policy server-side.", url)
                            .impact("Nothing stops trivially short passwords client-side, weakening account security posture.")
                            .rootCause("No minlength attribute on the password field.")
                            .standard("OWASP Authentication — password policy")
                            .element(sel)
                            .codeFix("<input type=\"password\" minlength=\"8\" autocomplete=\"new-password\" required>"));
                }
            }

            // Number-ish fields as plain text.
            if (type.equals("text") && matches(idName, "quantity", "qty", "amount", "age", "zip", "postal", "pincode", "pin code")) {
                out.add(typeSuggestion(c, sel, url, "number", "type=\"number\" or inputmode=\"numeric\"",
                        "numeric value", "<input type=\"number\" inputmode=\"numeric\" min=\"0\">",
                        "No numeric keypad on mobile and no built-in min/max/step validation."));
            }

            // Date fields as plain text.
            if (type.equals("text") && matches(idName, "date", "dob", "birthday", "birthdate")) {
                out.add(typeSuggestion(c, sel, url, "date", "type=\"date\"", "date",
                        "<input type=\"date\" name=\"dob\">",
                        "Users must type a date in a guessed format with no picker or validation."));
            }
        }

        return out.size() - before;
    }

    private WebIssue typeSuggestion(Element c, String sel, String url, String suggestedType,
                                    String label, String purpose, String fix, String impact) {
        return form(Severity.LOW, "Input should use " + label + " for " + purpose,
                "A field that appears to collect a " + purpose + " uses type=\"text\" instead of " + label + ".",
                "Use the semantic input type so the browser validates the value and shows the right keyboard.", url)
                .impact(impact)
                .rootCause("The " + purpose + " field is declared as a generic text input.")
                .standard("HTML Living Standard — input types · WCAG 1.3.5 (Identify Input Purpose)")
                .element(sel)
                .codeFix(fix);
    }

    private void detectDuplicateNames(Element form, String formSel, String url, List<WebIssue> out) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        Set<String> groupTypes = new HashSet<>();
        for (Element c : form.select("input[name], textarea[name], select[name]")) {
            String name = c.attr("name");
            String type = c.attr("type").toLowerCase(Locale.ROOT);
            if (name.isBlank() || name.endsWith("[]")) continue;
            // radio & checkbox groups legitimately share a name.
            if (type.equals("radio") || type.equals("checkbox")) { groupTypes.add(name); continue; }
            counts.merge(name, 1, Integer::sum);
        }
        for (var e : counts.entrySet()) {
            if (e.getValue() > 1 && !groupTypes.contains(e.getKey())) {
                out.add(form(Severity.LOW, "Duplicate field name in form",
                        "The name \"" + e.getKey() + "\" is used by " + e.getValue() + " non-group controls in the same form.",
                        "Give each control a unique name (radio/checkbox groups are the only exception).", url)
                        .impact("On submit, only one value survives (or they collide as an array), so user input is silently lost or mis-parsed on the server.")
                        .rootCause("Multiple text/other controls share name=\"" + e.getKey() + "\".")
                        .standard("HTML Living Standard — form submission")
                        .element(formSel + " [name=" + e.getKey() + "]"));
            }
        }
    }

    // ---- helpers ----

    private static boolean hasAccessibleLabel(Element c) {
        if (!c.attr("aria-label").isBlank()) return true;
        if (!c.attr("aria-labelledby").isBlank()) return true;
        if (!c.attr("title").isBlank()) return true;
        // Wrapped in a <label>?
        if (c.closest("label") != null) return true;
        // <label for="id"> pointing at this control?
        String id = c.id();
        if (!id.isBlank()) {
            Element root = c.ownerDocument();
            if (root != null && root.selectFirst("label[for=" + cssEscape(id) + "]") != null) return true;
        }
        return false;
    }

    private static boolean matches(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    private static String describe(String tag, String type) {
        if (tag.equals("select")) return "<select> dropdown";
        if (tag.equals("textarea")) return "<textarea>";
        return "<input type=\"" + type + "\">";
    }

    private static String controlSelector(Element c, String formSel, int idx) {
        if (!c.id().isBlank()) return "#" + c.id();
        if (!c.attr("name").isBlank()) return formSel + " [name=" + c.attr("name") + "]";
        return formSel + " " + c.tagName() + ":nth-of-type(" + idx + ")";
    }

    private static String labelId(Element c) {
        if (!c.id().isBlank()) return c.id();
        if (!c.attr("name").isBlank()) return c.attr("name");
        return "field";
    }

    private static String cssEscape(String s) {
        return s.replaceAll("([^a-zA-Z0-9_-])", "\\\\$1");
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static WebIssue form(Severity sev, String title, String detail, String rec, String url) {
        return WebIssue.of(WebIssueCategory.FORM_VALIDATION, sev, title, detail, rec, url, "FORM");
    }
}

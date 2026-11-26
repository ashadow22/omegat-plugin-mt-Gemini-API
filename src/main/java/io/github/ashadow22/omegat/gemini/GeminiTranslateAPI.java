/*
 * Copyright (C) 2026 Oleg Hudiakov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package io.github.ashadow22.omegat.gemini;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.omegat.core.Core;
import org.omegat.core.data.IProject;
import org.omegat.core.data.SourceTextEntry;
import org.omegat.core.machinetranslators.BaseCachedTranslate;
import org.omegat.gui.glossary.GlossaryEntry;
import org.omegat.gui.glossary.GlossarySearcher;
import org.omegat.util.Language;
import org.omegat.util.Log;
import org.omegat.util.PatternConsts;
import org.omegat.util.Preferences;

public class GeminiTranslateAPI extends BaseCachedTranslate {

    private static final String PREF_ALLOW_GEMINI = "allow_gemini_translate_api";
    private static final String PREF_API_KEY = "gemini_api_key";
    private static final String PREF_MODEL = "gemini_api_model";
    private static final String PREF_SYSTEM_PROMPT = "gemini_api_system_prompt";
    private static final String PREF_USE_GLOSSARY = "gemini_api_use_glossary";
    private static final String PREF_USE_CONTEXT = "gemini_api_use_context";
    private static final String PREF_SHOW_COUNTER = "gemini_api_show_counter";

    private static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a professional translator. Translate the segment accurately and naturally. "
            + "Preserve all OmegaT inline tags (like <g0>, </g0>, <x1/>) in their exact format and logical positions. "
            + "Output ONLY the translated text without explanations, quotes, or markdown formatting.";

    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final char QUOTE = '"';
    private static final char BACKSLASH = '\\';
    private static final Pattern RE_HTML = Pattern.compile("&#([0-9]+);");
    private static final Pattern RE_MODEL_NAME = Pattern.compile("\"name\":\\s*\"models/([^\"]+)\"");

    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger(0);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final Object rateLock = new Object();
    private long lastRequestTime = 0;

    public static void loadPlugins() {
        Core.registerMachineTranslationClass(GeminiTranslateAPI.class);
    }

    public static void unloadPlugins() {
        /* empty */
    }

    @Override
    public String getName() {
        boolean showCounter = Preferences.isPreferenceDefault(PREF_SHOW_COUNTER, true);
        String model = Preferences.getPreferenceDefault(PREF_MODEL, DEFAULT_MODEL);
        if (showCounter) {
            return "Google Gemini API (" + model + " | requests: " + REQUEST_COUNT.get() + ")";
        }
        return "Google Gemini API (" + model + ")";
    }

    @Override
    protected String getPreferenceName() {
        return PREF_ALLOW_GEMINI;
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public void showConfigurationUI(Window parent) {
        String currentKey = Preferences.getPreferenceDefault(PREF_API_KEY, "");
        String currentModel = Preferences.getPreferenceDefault(PREF_MODEL, DEFAULT_MODEL);
        String currentPrompt = Preferences.getPreferenceDefault(PREF_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT);
        boolean currentUseGlossary = Preferences.isPreferenceDefault(PREF_USE_GLOSSARY, true);
        boolean currentUseContext = Preferences.isPreferenceDefault(PREF_USE_CONTEXT, true);
        boolean currentShowCounter = Preferences.isPreferenceDefault(PREF_SHOW_COUNTER, true);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(620, 410));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Gemini API Key:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JPasswordField keyField = new JPasswordField(currentKey, 36);
        panel.add(keyField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Model:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JPanel modelPanel = new JPanel(new BorderLayout(6, 0));
        String[] defaultModels = {
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-4-hydrogen"
        };
        JComboBox<String> modelBox = new JComboBox<>(defaultModels);
        modelBox.setEditable(true);
        modelBox.setSelectedItem(currentModel);
        JButton refreshBtn = new JButton("Refresh Models");
        modelPanel.add(modelBox, BorderLayout.CENTER);
        modelPanel.add(refreshBtn, BorderLayout.EAST);
        panel.add(modelPanel, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0.0;
        gbc.weighty = 1.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel("System Prompt:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        JTextArea promptArea = new JTextArea(currentPrompt, 6, 36);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        JScrollPane promptScroll = new JScrollPane(promptArea);
        promptScroll.setMinimumSize(new Dimension(420, 125));
        promptScroll.setPreferredSize(new Dimension(450, 140));
        panel.add(promptScroll, gbc);

        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        JCheckBox useGlossaryBox = new JCheckBox("Include matching OmegaT glossary terms in prompt", currentUseGlossary);
        panel.add(useGlossaryBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        JCheckBox useContextBox = new JCheckBox("Include surrounding segments as context", currentUseContext);
        panel.add(useContextBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        JCheckBox showCounterBox = new JCheckBox("Show session request counter in Machine Translation pane", currentShowCounter);
        panel.add(showCounterBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 2;
        JPanel testPanel = new JPanel(new BorderLayout(8, 0));
        JButton testBtn = new JButton("Test Connection");
        JLabel statusLabel = new JLabel("Enter key and click 'Test Connection' to verify");
        testPanel.add(testBtn, BorderLayout.WEST);
        testPanel.add(statusLabel, BorderLayout.CENTER);
        panel.add(testPanel, gbc);

        refreshBtn.addActionListener(e -> {
            String keyToUse = new String(keyField.getPassword()).trim();
            if (keyToUse.isEmpty()) {
                statusLabel.setText("Enter API key first");
                return;
            }
            refreshBtn.setEnabled(false);
            statusLabel.setText("Loading models...");
            new Thread(() -> {
                List<String> fetched = fetchAvailableModels(keyToUse);
                SwingUtilities.invokeLater(() -> {
                    if (!fetched.isEmpty()) {
                        Object currentSel = modelBox.getSelectedItem();
                        modelBox.removeAllItems();
                        for (String m : fetched) {
                            modelBox.addItem(m);
                        }
                        if (currentSel != null) {
                            modelBox.setSelectedItem(currentSel);
                        }
                        statusLabel.setText("Loaded " + fetched.size() + " models.");
                    } else {
                        statusLabel.setText("Failed to load models (check API key).");
                    }
                    refreshBtn.setEnabled(true);
                });
            }).start();
        });

        testBtn.addActionListener(e -> {
            String keyToTest = new String(keyField.getPassword()).trim();
            Object selModel = modelBox.getSelectedItem();
            String modelToTest = selModel != null ? selModel.toString().trim() : DEFAULT_MODEL;
            if (keyToTest.isEmpty()) {
                statusLabel.setText("Enter API key first");
                return;
            }
            testBtn.setEnabled(false);
            statusLabel.setText("Testing...");
            new Thread(() -> {
                String res = testConnection(keyToTest, modelToTest);
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText(res);
                    testBtn.setEnabled(true);
                });
            }).start();
        });

        int result = JOptionPane.showConfirmDialog(
                parent,
                panel,
                "Google Gemini API Configuration",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );

        if (result == JOptionPane.OK_OPTION) {
            String newKey = new String(keyField.getPassword()).trim();
            Object selModel = modelBox.getSelectedItem();
            String newModel = (selModel != null && !selModel.toString().trim().isEmpty())
                    ? selModel.toString().trim() : DEFAULT_MODEL;
            String newPrompt = promptArea.getText().trim();
            if (newPrompt.isEmpty()) {
                newPrompt = DEFAULT_SYSTEM_PROMPT;
            }

            Preferences.setPreference(PREF_API_KEY, newKey);
            Preferences.setPreference(PREF_MODEL, newModel);
            Preferences.setPreference(PREF_SYSTEM_PROMPT, newPrompt);
            Preferences.setPreference(PREF_USE_GLOSSARY, useGlossaryBox.isSelected());
            Preferences.setPreference(PREF_USE_CONTEXT, useContextBox.isSelected());
            Preferences.setPreference(PREF_SHOW_COUNTER, showCounterBox.isSelected());
            Preferences.save();
        }
    }

    private List<String> fetchAvailableModels(String apiKey) {
        Set<String> models = new LinkedHashSet<>();
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models?key="
                    + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                Matcher m = RE_MODEL_NAME.matcher(resp.body());
                while (m.find()) {
                    String name = m.group(1);
                    if (name.contains("gemini")) {
                        models.add(name);
                    }
                }
            }
        } catch (Exception ex) {
            Log.log(ex);
        }
        return new ArrayList<>(models);
    }

    private String testConnection(String apiKey, String model) {
        try {
            String url = API_BASE + URLEncoder.encode(model, StandardCharsets.UTF_8)
                    + ":generateContent?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            String payload = buildRequestJson("Reply with single word: OK", "Ping");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                return "Success! Model '" + model + "' is ready.";
            }
            String errMsg = extractErrorMessage(resp.body());
            return "Error " + resp.statusCode() + (errMsg.isEmpty() ? "" : ": " + errMsg);
        } catch (Exception ex) {
            return "Failed: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }

    @Override
    protected String translate(Language sLang, Language tLang, String text) throws Exception {
        String apiKey = Preferences.getPreferenceDefault(PREF_API_KEY, "").trim();
        if (apiKey.isEmpty()) {
            return "Gemini API key is not configured (Options -> Machine Translation -> Configure)";
        }

        String prev = getFromCache(sLang, tLang, text);
        if (prev != null) {
            return prev;
        }

        String model = Preferences.getPreferenceDefault(PREF_MODEL, DEFAULT_MODEL).trim();
        String systemPrompt = Preferences.getPreferenceDefault(PREF_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT);
        boolean useGlossary = Preferences.isPreferenceDefault(PREF_USE_GLOSSARY, true);
        boolean useContext = Preferences.isPreferenceDefault(PREF_USE_CONTEXT, true);

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Translate from ").append(sLang.getLanguage())
                  .append(" to ").append(tLang.getLanguage()).append(".\n\n");

        if (useGlossary) {
            String glossaryBlock = buildGlossaryBlock(text);
            if (!glossaryBlock.isEmpty()) {
                userPrompt.append(glossaryBlock).append("\n");
            }
        }

        if (useContext) {
            String contextBlock = buildSurroundingContext(text);
            if (!contextBlock.isEmpty()) {
                userPrompt.append(contextBlock).append("\n");
            }
        }

        userPrompt.append("Segment to translate:\n").append(text);

        String payload = buildRequestJson(systemPrompt, userPrompt.toString());
        String url = API_BASE + URLEncoder.encode(model, StandardCharsets.UTF_8)
                + ":generateContent?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = sendWithRetry(request);
        REQUEST_COUNT.incrementAndGet();

        if (response.statusCode() != 200) {
            String errMsg = extractErrorMessage(response.body());
            Log.logErrorRB("Gemini HTTP error " + response.statusCode() + ": " + response.body());
            return "Gemini API Error " + response.statusCode() + (errMsg.isEmpty() ? "" : ": " + errMsg);
        }

        String translated = extractGeminiText(response.body());
        if (translated == null) {
            return null;
        }

        translated = cleanOmegaTTags(text, translated.trim());
        putToCache(sLang, tLang, text, translated);
        return translated;
    }

    private String buildGlossaryBlock(String currentText) {
        try {
            if (Core.getGlossaryManager() == null) {
                return "";
            }
            IProject project = Core.getProject();
            if (project == null || !project.isProjectLoaded()) {
                return "";
            }
            List<SourceTextEntry> entries = project.getAllEntries();
            SourceTextEntry matchedEntry = null;
            if (entries != null) {
                for (SourceTextEntry ste : entries) {
                    if (currentText.equals(ste.getSrcText())) {
                        matchedEntry = ste;
                        break;
                    }
                }
            }
            if (matchedEntry == null) {
                return "";
            }
            List<GlossaryEntry> allGlossary = Core.getGlossaryManager().getGlossaryEntries(currentText);
            if (allGlossary == null || allGlossary.isEmpty()) {
                return "";
            }
            GlossarySearcher searcher = new GlossarySearcher(project.getSourceTokenizer(), project.getProjectProperties().getSourceLanguage(), true);
            List<GlossaryEntry> matched = searcher.searchSourceMatches(matchedEntry, allGlossary);
            if (matched == null || matched.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("Mandatory glossary terms:\n");
            for (GlossaryEntry ge : matched) {
                String src = ge.getSrcText();
                String loc = ge.getLocText();
                if (src != null && loc != null && !src.isBlank() && !loc.isBlank()) {
                    sb.append("- ").append(src.trim()).append(" => ").append(loc.trim()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildSurroundingContext(String currentText) {
        try {
            IProject project = Core.getProject();
            if (project == null || !project.isProjectLoaded()) {
                return "";
            }
            List<SourceTextEntry> entries = project.getAllEntries();
            if (entries == null || entries.isEmpty()) {
                return "";
            }
            int foundIdx = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (currentText.equals(entries.get(i).getSrcText())) {
                    foundIdx = i;
                    break;
                }
            }
            if (foundIdx == -1) {
                return "";
            }
            List<String> ctxLines = new ArrayList<>();
            int start = Math.max(0, foundIdx - 2);
            int end = Math.min(entries.size() - 1, foundIdx + 1);
            for (int i = start; i <= end; i++) {
                if (i == foundIdx) {
                    continue;
                }
                String s = entries.get(i).getSrcText();
                if (s != null && !s.isBlank()) {
                    ctxLines.add(s.trim());
                }
            }
            if (ctxLines.isEmpty()) {
                return "";
            }
            String joined = String.join(" ", ctxLines);
            if (joined.length() > 1500) {
                joined = joined.substring(0, 1500);
            }
            return "Surrounding context (DO NOT translate, use only for tone and grammar agreement):\n" + joined + "\n";
        } catch (Exception e) {
            return "";
        }
    }

    private static String buildRequestJson(String systemInstruction, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append(jsonKey("system_instruction")).append(":{");
        sb.append(jsonKey("parts")).append(":[{");
        sb.append(jsonKey("text")).append(":").append(escapeJson(systemInstruction));
        sb.append("}]},");
        sb.append(jsonKey("contents")).append(":[{");
        sb.append(jsonKey("role")).append(":").append(escapeJson("user")).append(",");
        sb.append(jsonKey("parts")).append(":[{");
        sb.append(jsonKey("text")).append(":").append(escapeJson(userMessage));
        sb.append("}]}],");
        sb.append(jsonKey("generationConfig")).append(":{");
        sb.append(jsonKey("temperature")).append(":0.2");
        sb.append("}}");
        return sb.toString();
    }

    private static String jsonKey(String key) {
        return QUOTE + key + QUOTE;
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return QUOTE + "" + QUOTE;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(QUOTE);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == QUOTE) {
                sb.append(BACKSLASH).append(QUOTE);
            } else if (c == BACKSLASH) {
                sb.append(BACKSLASH).append(BACKSLASH);
            } else if (c == '\n') {
                sb.append(BACKSLASH).append('n');
            } else if (c == '\r') {
                sb.append(BACKSLASH).append('r');
            } else if (c == '\t') {
                sb.append(BACKSLASH).append('t');
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append(QUOTE);
        return sb.toString();
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws Exception {
        int maxRetries = 3;
        long backoffMs = 1500L;
        HttpResponse<String> response = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            synchronized (rateLock) {
                long now = System.currentTimeMillis();
                long wait = 500L - (now - lastRequestTime);
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                lastRequestTime = System.currentTimeMillis();
            }

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code != 429 && code != 503) {
                return response;
            }
            if (attempt < maxRetries) {
                Thread.sleep(backoffMs);
                backoffMs *= 2;
            }
        }
        return response;
    }

    private static String extractErrorMessage(String json) {
        String msg = extractJsonStringValue(json, "message");
        if (msg == null || msg.isEmpty()) {
            return "";
        }
        return msg.length() > 140 ? msg.substring(0, 140) + "..." : msg;
    }

    private static String extractGeminiText(String json) {
        return extractJsonStringValue(json, "text");
    }

    private static String extractJsonStringValue(String json, String fieldName) {
        if (json == null) {
            return null;
        }
        String marker = QUOTE + fieldName + QUOTE + ":";
        int idx = json.indexOf(marker);
        if (idx == -1) {
            return null;
        }
        int i = idx + marker.length();
        int len = json.length();
        while (i < len && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= len || json.charAt(i) != QUOTE) {
            return null;
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < len) {
            char c = json.charAt(i);
            if (c == BACKSLASH && i + 1 < len) {
                char next = json.charAt(i + 1);
                if (next == 'u' && i + 5 < len) {
                    String hex = json.substring(i + 2, i + 6);
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 6;
                    continue;
                } else if (next == 'n') {
                    sb.append('\n');
                } else if (next == 'r') {
                    sb.append('\r');
                } else if (next == 't') {
                    sb.append('\t');
                } else {
                    sb.append(next);
                }
                i += 2;
                continue;
            }
            if (c == QUOTE) {
                break;
            }
            sb.append(c);
            i++;
        }
        String res = sb.toString();
        while (true) {
            Matcher m = RE_HTML.matcher(res);
            if (!m.find()) {
                break;
            }
            String g = m.group();
            char c = (char) Integer.parseInt(m.group(1));
            res = res.replace(g, Character.toString(c));
        }
        return res;
    }

    private static String cleanOmegaTTags(String source, String translated) {
        String newTr = translated;
        Matcher tag = PatternConsts.OMEGAT_TAG_SPACE.matcher(newTr);
        while (tag.find()) {
            String searchTag = tag.group();
            if (!source.contains(searchTag)) {
                String replacement = searchTag.substring(0, searchTag.length() - 1);
                newTr = newTr.replace(searchTag, replacement);
            }
        }
        tag = PatternConsts.SPACE_OMEGAT_TAG.matcher(newTr);
        while (tag.find()) {
            String searchTag = tag.group();
            if (!source.contains(searchTag)) {
                String replacement = searchTag.substring(1);
                newTr = newTr.replace(searchTag, replacement);
            }
        }
        return newTr;
    }
}

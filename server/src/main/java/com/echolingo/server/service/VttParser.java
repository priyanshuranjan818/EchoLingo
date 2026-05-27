package com.echolingo.server.service;

import com.echolingo.server.model.Cue;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class VttParser {

    // Matches both HH:MM:SS.mmm and MM:SS.mmm
    private static final Pattern TIMESTAMP_LINE = Pattern.compile(
            "((?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3})\\s*-->\\s*((?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3})"
    );
    // Strips VTT inline tags: <c>, </c>, <00:00:01.234>, <b>, </b> etc.
    private static final Pattern INLINE_TAG = Pattern.compile("<[^>]+>");

    public List<Cue> parse(String vttText) {
        List<Cue> cues = new ArrayList<>();
        String[] lines = vttText.split("\\r?\\n");

        double start = 0, end = 0;
        StringBuilder textBuf = new StringBuilder();
        boolean inCue = false;
        int index = 0;

        for (String raw : lines) {
            String line = raw.trim();

            Matcher tsMatch = TIMESTAMP_LINE.matcher(line);
            if (tsMatch.find()) {
                // Flush previous cue
                if (inCue && textBuf.length() > 0) {
                    addCue(cues, index++, start, end, textBuf.toString(), cues);
                    textBuf.setLength(0);
                }
                start = parseTimestamp(tsMatch.group(1));
                end   = parseTimestamp(tsMatch.group(2));
                inCue = true;
                continue;
            }

            if (inCue) {
                if (line.isEmpty()) {
                    // Blank line = end of cue block
                    if (textBuf.length() > 0) {
                        addCue(cues, index++, start, end, textBuf.toString(), cues);
                        textBuf.setLength(0);
                    }
                    inCue = false;
                } else if (!line.startsWith("WEBVTT") && !line.startsWith("NOTE") && !isIndexLine(line)) {
                    if (textBuf.length() > 0) textBuf.append(' ');
                    textBuf.append(stripTags(line));
                }
            }
        }
        // Flush last cue
        if (inCue && textBuf.length() > 0) {
            addCue(cues, index, start, end, textBuf.toString(), cues);
        }

        return cues;
    }

    // Only add if text is not identical to the previous cue (dedup YouTube auto-captions)
    private static void addCue(List<Cue> cues, int index, double start, double end,
                                String rawText, List<Cue> existing) {
        String text = rawText.trim();
        if (text.isEmpty()) return;
        if (!existing.isEmpty() && existing.get(existing.size() - 1).text().equals(text)) return;
        cues.add(new Cue(index, start, end, text));
    }

    private static double parseTimestamp(String ts) {
        // Normalise to always HH:MM:SS.mmm by prepending "00:" if only MM:SS.mmm
        String[] parts = ts.split(":");
        double seconds = 0;
        if (parts.length == 3) {
            seconds = Integer.parseInt(parts[0]) * 3600.0
                    + Integer.parseInt(parts[1]) * 60.0
                    + Double.parseDouble(parts[2]);
        } else if (parts.length == 2) {
            seconds = Integer.parseInt(parts[0]) * 60.0
                    + Double.parseDouble(parts[1]);
        }
        return Math.round(seconds * 1000.0) / 1000.0; // 3 decimal precision
    }

    private static String stripTags(String text) {
        return INLINE_TAG.matcher(text).replaceAll("").trim();
    }

    // Cue index lines are pure numbers or number + position cue setting, skip them
    private static boolean isIndexLine(String line) {
        return line.matches("\\d+") || line.startsWith("align:") || line.startsWith("position:");
    }
}

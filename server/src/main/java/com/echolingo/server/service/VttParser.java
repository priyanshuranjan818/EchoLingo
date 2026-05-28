package com.echolingo.server.service;

import com.echolingo.server.model.Cue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class VttParser {

    private static final Pattern INLINE_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ANNOTATION_ONLY = Pattern.compile("^\\[([^\\]]+)](\\s+\\[([^\\]]+)])*$", Pattern.CASE_INSENSITIVE);

    public List<Cue> parse(String vttText) {
        if (vttText == null || vttText.isBlank()) return List.of();

        String[] lines = vttText.split("\\r?\\n");
        List<MutableCue> cues = new ArrayList<>();
        int i = 0;

        while (i < lines.length) {
            String line = lines[i].trim();
            if (!line.contains("-->")) {
                i++;
                continue;
            }

            String[] parts = line.split("-->", 2);
            if (parts.length < 2) {
                i++;
                continue;
            }

            double start = parseTimestamp(parts[0].trim());
            String endPart = parts[1].trim().split("\\s+")[0];
            double end = parseTimestamp(endPart);

            List<String> textLines = new ArrayList<>();
            i++;
            while (i < lines.length) {
                String textLine = lines[i].trim();
                if (textLine.isEmpty() || textLine.contains("-->")) break;
                String stripped = stripTags(textLine);
                if (!stripped.isBlank() && !stripped.startsWith("WEBVTT") && !stripped.startsWith("NOTE") && !isIndexLine(stripped)) {
                    textLines.add(stripped);
                }
                i++;
            }

            String text = String.join(" ", textLines).trim();
            if (!text.isBlank() && start < end) {
                if (text.length() > 500) {
                    text = text.substring(0, 497) + "...";
                }
                cues.add(new MutableCue(start, end, text));
            }
        }

        cues.sort(Comparator.comparingDouble(c -> c.start));
        List<MutableCue> deduped = mergeConsecutiveDuplicates(cues);
        List<MutableCue> collapsed = collapseYouTubeRollingCaptions(deduped);

        List<Cue> result = new ArrayList<>(collapsed.size());
        for (int idx = 0; idx < collapsed.size(); idx++) {
            MutableCue cue = collapsed.get(idx);
            result.add(new Cue(idx, roundMillis(cue.start), roundMillis(cue.end), cue.text));
        }
        return result;
    }

    private static List<MutableCue> mergeConsecutiveDuplicates(List<MutableCue> cues) {
        List<MutableCue> deduped = new ArrayList<>();
        for (MutableCue cue : cues) {
            MutableCue prev = deduped.isEmpty() ? null : deduped.get(deduped.size() - 1);
            if (prev != null && prev.text.equals(cue.text) && cue.start <= prev.end + 0.1) {
                prev.end = Math.max(prev.end, cue.end);
            } else {
                deduped.add(new MutableCue(cue.start, cue.end, cue.text));
            }
        }
        return deduped;
    }

    private static List<MutableCue> collapseYouTubeRollingCaptions(List<MutableCue> cues) {
        if (cues.size() < 4) return cues;

        int prefixPairs = 0;
        for (int i = 0; i < cues.size() - 1; i++) {
            if (isPrefixOf(cues.get(i).text, cues.get(i + 1).text)) {
                prefixPairs++;
            }
        }
        if ((double) prefixPairs / (cues.size() - 1) < 0.25) {
            return removeAnnotationOnly(cues);
        }

        List<MutableCue> noBridges = cues.stream()
                .filter(c -> c.end - c.start > 0.05)
                .map(c -> new MutableCue(c.start, c.end, c.text))
                .toList();

        List<MutableCue> withoutPrefixes = new ArrayList<>();
        for (int i = 0; i < noBridges.size(); i++) {
            MutableCue curr = noBridges.get(i);
            MutableCue next = i + 1 < noBridges.size() ? noBridges.get(i + 1) : null;
            if (next != null && isPrefixOf(curr.text, next.text) && next.start - curr.start < 4.0) {
                continue;
            }
            withoutPrefixes.add(curr);
        }

        List<MutableCue> result = new ArrayList<>();
        for (int i = 0; i < withoutPrefixes.size(); i++) {
            MutableCue curr = withoutPrefixes.get(i);
            MutableCue next = i + 1 < withoutPrefixes.size() ? withoutPrefixes.get(i + 1) : null;
            if (next == null) {
                result.add(curr);
                continue;
            }

            int overlapLen = findWordOverlapLength(curr.text, next.text);
            if (overlapLen > 0) {
                String[] words = curr.text.trim().split("\\s+");
                int keep = Math.max(0, words.length - overlapLen);
                String uniqueText = String.join(" ", java.util.Arrays.copyOfRange(words, 0, keep)).trim();
                if (!uniqueText.isBlank()) {
                    result.add(new MutableCue(curr.start, curr.end, uniqueText));
                }
            } else {
                result.add(curr);
            }
        }

        return removeAnnotationOnly(result);
    }

    private static List<MutableCue> removeAnnotationOnly(List<MutableCue> cues) {
        return cues.stream()
                .filter(c -> !ANNOTATION_ONLY.matcher(c.text.trim()).matches())
                .map(c -> new MutableCue(c.start, c.end, c.text))
                .toList();
    }

    private static boolean isPrefixOf(String textA, String textB) {
        if (textA == null || textB == null) return false;
        String a = textA.trim();
        String b = textB.trim();
        return !a.isBlank() && !b.isBlank() && (b.equals(a) || b.startsWith(a + " "));
    }

    private static int findWordOverlapLength(String textA, String textB) {
        String[] wordsA = textA.trim().split("\\s+");
        String[] wordsB = textB.trim().split("\\s+");
        int maxOverlap = Math.min(wordsA.length - 1, wordsB.length);
        for (int len = maxOverlap; len >= 1; len--) {
            String suffixA = String.join(" ", java.util.Arrays.copyOfRange(wordsA, wordsA.length - len, wordsA.length));
            String prefixB = String.join(" ", java.util.Arrays.copyOfRange(wordsB, 0, len));
            if (suffixA.equals(prefixB)) return len;
        }
        return 0;
    }

    private static double parseTimestamp(String ts) {
        String[] parts = ts.trim().split(":");
        double seconds = 0;
        if (parts.length == 3) {
            seconds = Integer.parseInt(parts[0]) * 3600.0
                    + Integer.parseInt(parts[1]) * 60.0
                    + Double.parseDouble(parts[2]);
        } else if (parts.length == 2) {
            seconds = Integer.parseInt(parts[0]) * 60.0
                    + Double.parseDouble(parts[1]);
        } else if (parts.length == 1) {
            seconds = Double.parseDouble(parts[0]);
        }
        return roundMillis(seconds);
    }

    private static String stripTags(String text) {
        return INLINE_TAG.matcher(text)
                .replaceAll("")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .trim();
    }

    private static boolean isIndexLine(String line) {
        return line.matches("\\d+") || line.startsWith("align:") || line.startsWith("position:");
    }

    private static double roundMillis(double seconds) {
        return Math.round(seconds * 1000.0) / 1000.0;
    }

    private static final class MutableCue {
        private final double start;
        private double end;
        private final String text;

        private MutableCue(double start, double end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }
}

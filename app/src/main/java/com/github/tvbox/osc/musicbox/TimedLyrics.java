package com.github.tvbox.osc.musicbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts LRC timestamps to ordered lines for the player. */
final class TimedLyrics {
    static final class Line {
        final int millis;
        final String text;
        final int[] characterMillis;
        Line(int millis, String text) { this(millis, text, new int[0]); }
        Line(int millis, String text, int[] characterMillis) {
            this.millis = millis; this.text = text; this.characterMillis = characterMillis;
        }
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\[offset:([+-]?\\d+)\\]\\s*$");
    private static final Pattern WORD = Pattern.compile("<(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?>");

    private TimedLyrics() {}

    static List<Line> parse(String lrc) {
        if (lrc == null || lrc.isEmpty()) return Collections.emptyList();
        int offset = 0;
        Matcher offsetTag = OFFSET.matcher(lrc);
        if (offsetTag.find()) {
            try { offset = Integer.parseInt(offsetTag.group(1)); } catch (NumberFormatException ignored) {}
        }
        List<Line> lines = new ArrayList<>();
        for (String row : lrc.split("\\r?\\n")) {
            Matcher stamp = STAMP.matcher(row);
            List<Integer> times = new ArrayList<>();
            int end = 0;
            while (stamp.find()) {
                int minutes = Integer.parseInt(stamp.group(1));
                int seconds = Integer.parseInt(stamp.group(2));
                String fraction = stamp.group(3);
                int millis = fraction == null ? 0 : Integer.parseInt((fraction + "000").substring(0, 3));
                times.add(Math.max(0, (minutes * 60 + seconds) * 1000 + millis + offset));
                end = stamp.end();
            }
            if (times.isEmpty()) continue;
            String rawText = row.substring(end).trim();
            String text = rawText;
            if (text.isEmpty()) continue;
            int[] characterMillis = new int[0];
            Matcher word = WORD.matcher(rawText);
            if (word.find()) {
                StringBuilder clean = new StringBuilder();
                List<Integer> characterTimes = new ArrayList<>();
                word.reset(); int cursor = 0, start = times.get(0);
                while (word.find()) {
                    for (int i = cursor; i < word.start(); i++) {
                        clean.append(rawText.charAt(i)); characterTimes.add(start);
                    }
                    String fraction = word.group(3);
                    start = (Integer.parseInt(word.group(1)) * 60 + Integer.parseInt(word.group(2))) * 1000
                        + (fraction == null ? 0 : Integer.parseInt((fraction + "000").substring(0, 3))) + offset;
                    cursor = word.end();
                }
                for (int i = cursor; i < rawText.length(); i++) {
                    clean.append(rawText.charAt(i)); characterTimes.add(start);
                }
                text = clean.toString().trim();
                characterMillis = new int[characterTimes.size()];
                for (int i = 0; i < characterTimes.size(); i++) characterMillis[i] = characterTimes.get(i);
            }
            if (text.isEmpty()) continue;
            for (int time : times) lines.add(new Line(time, text, characterMillis));
        }
        lines.sort((a, b) -> Integer.compare(a.millis, b.millis));
        return lines;
    }

    static int active(List<Line> lines, int millis) {
        int low = 0, high = lines.size() - 1, result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).millis <= millis) { result = mid; low = mid + 1; }
            else high = mid - 1;
        }
        return result;
    }
    static int highlightedCharacters(Line line, int millis) {
        if (line.characterMillis.length == 0) return line.text.length();
        int count = 0;
        for (int time : line.characterMillis) if (time <= millis) count++;
        return Math.min(line.text.length(), count);
    }
}

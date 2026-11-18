package com.platform.ingestion.transformation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal path syntax: {@code $.a.b[0].c}. Field names are {@code [A-Za-z0-9_-]+}; array indexes are
 * non-negative integers. No wildcards, filters or recursion: deliberately smaller than JsonPath.
 */
public final class PathExpression {

    public sealed interface Segment permits Field, Index {}

    public record Field(String name) implements Segment {}

    public record Index(int index) implements Segment {}

    private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_-]+");

    private final String source;
    private final List<Segment> segments;

    private PathExpression(String source, List<Segment> segments) {
        this.source = source;
        this.segments = List.copyOf(segments);
    }

    /** @throws IllegalArgumentException with a human readable reason when the syntax is invalid. */
    public static PathExpression parse(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '$') {
            throw new IllegalArgumentException("path must start with '$'");
        }
        List<Segment> segments = new ArrayList<>();
        int i = 1;
        int n = path.length();
        while (i < n) {
            char c = path.charAt(i);
            if (c == '.') {
                int start = ++i;
                while (i < n && path.charAt(i) != '.' && path.charAt(i) != '[') {
                    i++;
                }
                String name = path.substring(start, i);
                if (!FIELD.matcher(name).matches()) {
                    throw new IllegalArgumentException("invalid field name '" + name + "' in path");
                }
                segments.add(new Field(name));
            } else if (c == '[') {
                int end = path.indexOf(']', i);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated '[' in path");
                }
                String digits = path.substring(i + 1, end);
                if (digits.isEmpty() || digits.length() > 6 || !digits.chars().allMatch(Character::isDigit)) {
                    throw new IllegalArgumentException("array index must be a non-negative integer");
                }
                segments.add(new Index(Integer.parseInt(digits)));
                i = end + 1;
            } else {
                throw new IllegalArgumentException("unexpected character '" + c + "' in path");
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("path must select at least one field");
        }
        return new PathExpression(path, segments);
    }

    public List<Segment> segments() {
        return segments;
    }

    /** Returns the selected node, or {@code null} when any step is missing. */
    public JsonNode evaluate(JsonNode root) {
        JsonNode current = root;
        for (Segment segment : segments) {
            if (current == null) {
                return null;
            }
            if (segment instanceof Field f) {
                current = current.isObject() ? current.get(f.name()) : null;
            } else {
                int idx = ((Index) segment).index();
                current = current.isArray() ? current.get(idx) : null;
            }
        }
        return current;
    }

    @Override
    public String toString() {
        return source;
    }
}

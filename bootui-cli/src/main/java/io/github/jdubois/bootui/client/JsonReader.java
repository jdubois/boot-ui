package io.github.jdubois.bootui.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict, recursive-descent JSON reader producing {@link JsonValue} trees.
 *
 * <p>Strict on purpose: it accepts RFC 8259 and nothing else, so a truncated or non-JSON response (an HTML
 * error page from a proxy, say) fails loudly here instead of being rendered as a plausible-looking result.
 * Nesting is bounded so a hostile or pathological document cannot exhaust the stack.
 */
final class JsonReader {

    /** Deep enough for any BootUI report, shallow enough that a crafted document cannot overflow the stack. */
    private static final int MAX_DEPTH = 200;

    private final String source;
    private int position;

    private JsonReader(String source) {
        this.source = source;
    }

    static JsonValue read(String json) {
        if (json == null) {
            throw new JsonParseException("No content to parse", 0);
        }
        JsonReader reader = new JsonReader(json);
        reader.skipWhitespace();
        JsonValue value = reader.readValue(0);
        reader.skipWhitespace();
        if (reader.position < reader.source.length()) {
            throw new JsonParseException("Unexpected trailing content", reader.position);
        }
        return value;
    }

    private JsonValue readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonParseException("Nesting is too deep", position);
        }
        char current = peek();
        switch (current) {
            case '{':
                return readObject(depth);
            case '[':
                return readArray(depth);
            case '"':
                return JsonValue.string(readString());
            case 't':
                expect("true");
                return JsonValue.bool(true);
            case 'f':
                expect("false");
                return JsonValue.bool(false);
            case 'n':
                expect("null");
                return JsonValue.NULL;
            default:
                return readNumber();
        }
    }

    private JsonValue readObject(int depth) {
        position++;
        Map<String, JsonValue> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return JsonValue.objectOf(members);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new JsonParseException("Expected a member name", position);
            }
            String name = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw new JsonParseException("Expected ':' after a member name", position);
            }
            position++;
            skipWhitespace();
            members.put(name, readValue(depth + 1));
            skipWhitespace();
            char separator = peek();
            position++;
            if (separator == '}') {
                return JsonValue.objectOf(members);
            }
            if (separator != ',') {
                throw new JsonParseException("Expected ',' or '}' in an object", position - 1);
            }
        }
    }

    private JsonValue readArray(int depth) {
        position++;
        List<JsonValue> elements = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return JsonValue.arrayOf(elements);
        }
        while (true) {
            skipWhitespace();
            elements.add(readValue(depth + 1));
            skipWhitespace();
            char separator = peek();
            position++;
            if (separator == ']') {
                return JsonValue.arrayOf(elements);
            }
            if (separator != ',') {
                throw new JsonParseException("Expected ',' or ']' in an array", position - 1);
            }
        }
    }

    private String readString() {
        position++;
        StringBuilder text = new StringBuilder();
        while (true) {
            if (position >= source.length()) {
                throw new JsonParseException("Unterminated string", position);
            }
            char current = source.charAt(position++);
            if (current == '"') {
                return text.toString();
            }
            if (current != '\\') {
                if (current < 0x20) {
                    throw new JsonParseException("Unescaped control character in a string", position - 1);
                }
                text.append(current);
                continue;
            }
            if (position >= source.length()) {
                throw new JsonParseException("Unterminated escape sequence", position);
            }
            char escape = source.charAt(position++);
            switch (escape) {
                case '"':
                    text.append('"');
                    break;
                case '\\':
                    text.append('\\');
                    break;
                case '/':
                    text.append('/');
                    break;
                case 'b':
                    text.append('\b');
                    break;
                case 'f':
                    text.append('\f');
                    break;
                case 'n':
                    text.append('\n');
                    break;
                case 'r':
                    text.append('\r');
                    break;
                case 't':
                    text.append('\t');
                    break;
                case 'u':
                    text.append(readUnicodeEscape());
                    break;
                default:
                    throw new JsonParseException("Unknown escape sequence '\\" + escape + "'", position - 1);
            }
        }
    }

    private char readUnicodeEscape() {
        if (position + 4 > source.length()) {
            throw new JsonParseException("Truncated unicode escape", position);
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            char digit = source.charAt(position++);
            int value = Character.digit(digit, 16);
            if (value < 0) {
                throw new JsonParseException("Invalid unicode escape", position - 1);
            }
            code = code * 16 + value;
        }
        return (char) code;
    }

    private JsonValue readNumber() {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        readDigits();
        // RFC 8259 allows a single leading zero only as the whole integer part, so '01' is not a number.
        if (source.charAt(start) == '0' && position - start > 1) {
            throw new JsonParseException("Leading zeros are not allowed in a number", start);
        }
        if (source.charAt(start) == '-' && position - start > 2 && source.charAt(start + 1) == '0') {
            throw new JsonParseException("Leading zeros are not allowed in a number", start + 1);
        }
        if (position < source.length() && source.charAt(position) == '.') {
            position++;
            readDigits();
        }
        if (position < source.length()) {
            char exponent = source.charAt(position);
            if (exponent == 'e' || exponent == 'E') {
                position++;
                if (position < source.length() && (source.charAt(position) == '+' || source.charAt(position) == '-')) {
                    position++;
                }
                readDigits();
            }
        }
        return JsonValue.number(source.substring(start, position));
    }

    private void readDigits() {
        int start = position;
        while (position < source.length() && source.charAt(position) >= '0' && source.charAt(position) <= '9') {
            position++;
        }
        if (position == start) {
            throw new JsonParseException("Expected a digit", position);
        }
    }

    private void expect(String literal) {
        if (!source.startsWith(literal, position)) {
            throw new JsonParseException("Expected '" + literal + "'", position);
        }
        position += literal.length();
    }

    private char peek() {
        if (position >= source.length()) {
            throw new JsonParseException("Unexpected end of input", position);
        }
        return source.charAt(position);
    }

    private void skipWhitespace() {
        while (position < source.length()) {
            char current = source.charAt(position);
            if (current != ' ' && current != '\t' && current != '\n' && current != '\r') {
                return;
            }
            position++;
        }
    }
}

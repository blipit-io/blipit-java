package io.blipit.nuggets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {}

    public static String encode(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            string(out, (String) value);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            out.append(value.toString());
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                out.append("null");
            } else if (d == Math.floor(d) && Math.abs(d) < 1e15) {
                out.append((long) d);
            } else {
                out.append(d);
            }
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (e.getValue() == null) continue;
                if (!first) out.append(',');
                first = false;
                string(out, String.valueOf(e.getKey()));
                out.append(':');
                write(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object v : (Iterable<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, v);
            }
            out.append(']');
        } else if (value instanceof Object[]) {
            List<Object> list = new ArrayList<Object>();
            for (Object v : (Object[]) value) list.add(v);
            write(out, list);
        } else {
            string(out, value.toString());
        }
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.value();
        p.skip();
        if (p.i != text.length()) throw new IllegalArgumentException("trailing data at " + p.i);
        return v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        char peek() {
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            return s.charAt(i);
        }

        void expect(String word) {
            if (!s.startsWith(word, i)) throw new IllegalArgumentException("bad json at " + i);
            i += word.length();
        }

        Object value() {
            skip();
            char c = peek();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw new IllegalArgumentException("bad json at " + i);
        }

        Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            i++;
            skip();
            if (peek() == '}') { i++; return out; }
            while (true) {
                skip();
                if (peek() != '"') throw new IllegalArgumentException("bad key at " + i);
                String key = string();
                skip();
                if (peek() != ':') throw new IllegalArgumentException("bad json at " + i);
                i++;
                out.put(key, value());
                skip();
                char c = peek();
                i++;
                if (c == '}') return out;
                if (c != ',') throw new IllegalArgumentException("bad json at " + i);
            }
        }

        List<Object> array() {
            List<Object> out = new ArrayList<Object>();
            i++;
            skip();
            if (peek() == ']') { i++; return out; }
            while (true) {
                out.add(value());
                skip();
                char c = peek();
                i++;
                if (c == ']') return out;
                if (c != ',') throw new IllegalArgumentException("bad json at " + i);
            }
        }

        String string() {
            StringBuilder out = new StringBuilder();
            i++;
            while (true) {
                char c = peek();
                i++;
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = peek();
                i++;
                switch (e) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("bad escape at " + i);
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: out.append(e);
                }
            }
        }

        Number number() {
            int start = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String text = s.substring(start, i);
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return Double.parseDouble(text);
            }
        }
    }
}

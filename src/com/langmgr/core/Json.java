package com.langmgr.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON 解析 / 序列化工具，零第三方依赖。
 * 用于解析 pip、npm、conda 等命令输出的 JSON，以及导出报告。
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /* ------------------------------------------------------------------ 解析 */

    /** 宽松解析：自动跳过 JSON 之前的噪声（如 npm 的告警行）。失败返回 null。 */
    public static Object parse(String text) {
        if (text == null) {
            return null;
        }
        int start = firstStructural(text);
        if (start < 0) {
            return null;
        }
        Json j = new Json(text);
        j.pos = start;
        try {
            j.skipWs();
            return j.readValue();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 解析连续的多个 JSON 值（NDJSON / go list -m -json all / yarn list 等）。
     * 单个值解析失败时跳过该位置继续，尽量多拿到后面的记录。
     */
    public static List<Object> parseAll(String text) {
        List<Object> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int search = 0;
        while (search < text.length()) {
            int start = nextStructural(text, search);
            if (start < 0) {
                break;
            }
            Json j = new Json(text);
            j.pos = start;
            try {
                j.skipWs();
                out.add(j.readValue());
                search = Math.max(j.pos, start + 1);
            } catch (RuntimeException ex) {
                search = start + 1;
            }
        }
        return out;
    }

    /** 从 from 开始找下一个 '{' 或 '['（调用方已保证不在字符串内）。 */
    private static int nextStructural(String text, int from) {
        for (int i = Math.max(0, from); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                return i;
            }
        }
        return -1;
    }

    /** 找到第一个不在字符串字面量内的 '{' 或 '['。 */
    private static int firstStructural(String text) {
        boolean inStr = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                return i;
            }
        }
        return -1;
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Object readValue() {
        skipWs();
        if (pos >= src.length()) {
            return null;
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default:  return readNumber();
        }
    }

    private void expect(String lit) {
        if (src.startsWith(lit, pos)) {
            pos += lit.length();
        } else {
            throw new IllegalStateException("期望 " + lit + " 于位置 " + pos);
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // '{'
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (pos < src.length()) {
            skipWs();
            String key = readString();
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ':') {
                pos++;
            }
            Object value = readValue();
            map.put(key, value);
            skipWs();
            if (pos >= src.length()) {
                break;
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                break;
            } else {
                pos++;
            }
        }
        return map;
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // '['
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (pos < src.length()) {
            list.add(readValue());
            skipWs();
            if (pos >= src.length()) {
                break;
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                break;
            } else {
                pos++;
            }
        }
        return list;
    }

    private String readString() {
        StringBuilder sb = new StringBuilder();
        if (pos < src.length() && src.charAt(pos) == '"') {
            pos++;
        }
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\' && pos < src.length()) {
                char e = src.charAt(pos++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (pos + 4 <= src.length()) {
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object readNumber() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                pos++;
            } else {
                break;
            }
        }
        String num = src.substring(start, pos);
        if (num.isEmpty()) {
            pos++;
            return null;
        }
        try {
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        } catch (NumberFormatException ex) {
            return num;
        }
    }

    /* ------------------------------------------------------------------ 访问 */

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String asString(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof Double) {
            double d = (Double) o;
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
        }
        return String.valueOf(o);
    }

    /** 按 "a.b.c" 路径取值，任何一级缺失返回 null。 */
    public static Object get(Object root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            Map<String, Object> m = asMap(cur);
            if (m == null) {
                return null;
            }
            cur = m.get(seg);
        }
        return cur;
    }

    public static String getStr(Object root, String path) {
        return asString(get(root, path));
    }

    /* -------------------------------------------------------------- 序列化 */

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0, true);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, int depth, boolean pretty) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v);
        } else if (v instanceof Map) {
            writeMap(sb, asMap(v), depth, pretty);
        } else if (v instanceof List) {
            writeList(sb, asList(v), depth, pretty);
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeMap(StringBuilder sb, Map<String, Object> m, int depth, boolean pretty) {
        if (m.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newline(sb, depth + 1, pretty);
            writeString(sb, e.getKey());
            sb.append(':');
            if (pretty) {
                sb.append(' ');
            }
            writeValue(sb, e.getValue(), depth + 1, pretty);
        }
        newline(sb, depth, pretty);
        sb.append('}');
    }

    private static void writeList(StringBuilder sb, List<Object> l, int depth, boolean pretty) {
        if (l.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object o : l) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newline(sb, depth + 1, pretty);
            writeValue(sb, o, depth + 1, pretty);
        }
        newline(sb, depth, pretty);
        sb.append(']');
    }

    private static void newline(StringBuilder sb, int depth, boolean pretty) {
        if (!pretty) {
            return;
        }
        sb.append('\n');
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}

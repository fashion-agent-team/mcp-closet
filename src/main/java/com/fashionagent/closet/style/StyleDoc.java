package com.fashionagent.closet.style;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * style_docs/*.md 하나. 맨 위 --- 사이에 key: value(occasion, minTemp, maxTemp), 그 아래 "# 제목"과 "## 소제목" 조각들.
 * 조각 하나가 검색 단위(chunk)이고, id는 source+chunkNo로 만든 UUID라 다시 넣으면 덮어쓴다(마이그레이션 문서와 같음).
 */
record StyleDoc(String source, String title, Map<String, Object> meta, List<String> chunks) {

    static StyleDoc parse(String source, String text) {
        String body = text.replace("\r\n", "\n").strip();
        var meta = new LinkedHashMap<String, Object>();
        if (body.startsWith("---\n")) {
            int end = body.indexOf("\n---", 4);
            if (end < 0) {
                throw new IllegalArgumentException(source + ": 머리말(---)이 닫히지 않았어요.");
            }
            for (String line : body.substring(4, end).split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    meta.put(key, key.endsWith("Temp") ? Double.parseDouble(value) : value);
                }
            }
            body = body.substring(end + 4).strip();
        }
        String title = source;
        if (body.startsWith("# ")) {
            int nl = body.indexOf('\n');
            title = (nl < 0 ? body : body.substring(0, nl)).substring(2).trim();
            body = nl < 0 ? "" : body.substring(nl + 1).strip();
        }
        var chunks = new ArrayList<String>();
        for (String part : ("\n" + body).split("\n## ")) {
            String p = part.strip();
            if (!p.isEmpty()) {
                // 조각만 떼어 읽어도 무슨 이야기인지 알게 제목을 붙인다
                chunks.add(title + " - " + p.replaceFirst("\n", ": ").replaceAll("\\s*\n\\s*", " "));
            }
        }
        meta.putIfAbsent("occasion", "any");
        return new StyleDoc(source, title, meta, chunks);
    }

    static String chunkId(String source, int chunkNo) {
        return UUID.nameUUIDFromBytes((source + "#" + chunkNo).getBytes(StandardCharsets.UTF_8)).toString();
    }
}

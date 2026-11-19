package com.fashionagent.closet;

/** 약속 문서 4절의 오류. MCP 도구는 {"error": {"code", "message"}}로 바꿔 돌려준다. */
public class ClosetException extends RuntimeException {

    private final String code;

    public ClosetException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ClosetException badRequest(String message) {
        return new ClosetException("bad_request", message);
    }

    public static ClosetException notFound(String message) {
        return new ClosetException("not_found", message);
    }

    public static ClosetException upstreamFailed(String message) {
        return new ClosetException("upstream_failed", message);
    }
}

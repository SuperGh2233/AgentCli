package com.haocli.llm;

import java.util.regex.Pattern;

/** 模型调用错误的分类（给内核用，内核看不到各家客户端的内部异常类型）。 */
public final class LlmErrors {

    private static final Pattern CONTEXT_OVERFLOW = Pattern.compile(
            "(?i)(context[ _-]?length|maximum context|context window|context_length_exceeded"
                    + "|too many tokens|prompt is too long|input is too long|request too large"
                    + "|exceeds? (the )?(model'?s? )?(maximum )?(context|token)|reduce the length"
                    + "|上下文.{0,6}(超|过长|超出)|超出.{0,6}(上下文|长度))");

    private LlmErrors() {
    }

    /** 模型因为输入超过上下文窗口而拒绝请求（HTTP 400 / 413，或错误文字里写明超长）。 */
    public static boolean isContextOverflow(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof LlmHttpException http) {
                int code = http.statusCode();
                if ((code == 400 || code == 413) && matches(http.getMessage())) return true;
            } else if (matches(t.getMessage()) && String.valueOf(t.getMessage()).matches("(?s).*\\b(400|413)\\b.*")) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static boolean matches(String message) {
        return message != null && CONTEXT_OVERFLOW.matcher(message).find();
    }
}

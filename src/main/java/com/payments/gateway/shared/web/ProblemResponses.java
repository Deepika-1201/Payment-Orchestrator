package com.payments.gateway.shared.web;

import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.json.JsonCodec;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;

/** Writes problem-details responses from servlet filters, matching the controller error format. */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static void write(HttpServletResponse response, JsonCodec json, ErrorCode code, String detail)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", code.title());
        body.put("status", code.httpStatus());
        body.put("detail", detail);
        body.put("code", code.code());
        String requestId = Mdc.requestId();
        if (requestId != null) {
            body.put("request_id", requestId);
        }
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.write(body));
    }
}

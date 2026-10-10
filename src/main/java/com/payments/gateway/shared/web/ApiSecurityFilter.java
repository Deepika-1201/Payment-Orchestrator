package com.payments.gateway.shared.web;

import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.json.JsonCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Hardening for the JSON APIs under {@code /v1} and {@code /admin} (ADR-022): responses are never cached, sniffed or
 * framed, HSTS is sent over HTTPS, and request bodies are capped before anything buffers them.
 */
public class ApiSecurityFilter extends OncePerRequestFilter {

    public static final String HSTS = "max-age=31536000; includeSubDomains";
    /** Dispute evidence uploads carry a file, so they get their own body limit (ADR-039). */
    public static final Pattern EVIDENCE_UPLOAD = Pattern.compile("/v1/disputes/[^/]+/evidence_files");

    /** Thrown while reading a chunked body that grows past the limit. */
    public static final class PayloadTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        PayloadTooLargeException(long limit) {
            super("request body exceeds " + limit + " bytes");
        }
    }

    private final long maxBodyBytes;
    private final Pattern largeBodyPath;
    private final long largeBodyBytes;
    private final JsonCodec json;

    public ApiSecurityFilter(long maxBodyBytes, JsonCodec json) {
        this(maxBodyBytes, Pattern.compile("(?!)"), maxBodyBytes, json);
    }

    /** {@code largeBodyPath}: requests to these paths may send up to {@code largeBodyBytes} instead. */
    public ApiSecurityFilter(long maxBodyBytes, Pattern largeBodyPath, long largeBodyBytes, JsonCodec json) {
        this.maxBodyBytes = maxBodyBytes;
        this.largeBodyPath = largeBodyPath;
        this.largeBodyBytes = largeBodyBytes;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", HSTS);
        }
        long limit = largeBodyPath.matcher(request.getRequestURI()).matches() ? largeBodyBytes : maxBodyBytes;
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            ProblemResponses.write(response, json, ErrorCode.PAYLOAD_TOO_LARGE,
                    "Request bodies are limited to " + limit + " bytes");
            return;
        }
        chain.doFilter(declared >= 0 ? request : new LimitedRequest(request, limit), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        LimitedInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws PayloadTooLargeException {
            read += n;
            if (read > limit) {
                throw new PayloadTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}

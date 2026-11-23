package id.yudha.ytshorts;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Downloader HTTP sederhana untuk NewPipeExtractor (tanpa OkHttp). */
public final class HttpDownloader extends Downloader {

    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0";

    @Override
    public Response execute(final Request request) throws IOException, ReCaptchaException {
        final HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod(request.httpMethod());
        c.setRequestProperty("User-Agent", USER_AGENT);

        for (Map.Entry<String, List<String>> h : request.headers().entrySet()) {
            boolean first = true;
            for (String v : h.getValue()) {
                if (first) { c.setRequestProperty(h.getKey(), v); first = false; }
                else c.addRequestProperty(h.getKey(), v);
            }
        }

        final byte[] data = request.dataToSend();
        if (data != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
        }

        try {
            final int code = c.getResponseCode();
            if (code == 429) throw new ReCaptchaException("reCaptcha Challenge requested", request.url());

            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = "";
            if (is != null) {
                if ("gzip".equalsIgnoreCase(c.getContentEncoding())) is = new GZIPInputStream(is);
                body = new String(readAll(is), StandardCharsets.UTF_8);
            }

            final Map<String, List<String>> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null) headers.put(e.getKey(), e.getValue());
            }
            return new Response(code, c.getResponseMessage(), headers, body, c.getURL().toString());
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }
}

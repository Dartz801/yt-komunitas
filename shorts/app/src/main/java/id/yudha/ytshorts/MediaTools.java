package id.yudha.ytshorts;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.provider.MediaStore;

import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;

/** Unduh stream YouTube, gabungkan video+audio, simpan ke Galeri. */
final class MediaTools {

    interface Progress { void onBytes(long added); }

    private static final long CHUNK = 5L * 1024 * 1024; // 5 MB per permintaan
    private static final byte[] POST_BODY = {0x78, 0};

    private MediaTools() { }

    /** Mengunduh stream googlevideo per potongan (&range=), seperti yang dilakukan NewPipe. */
    static void downloadStream(String url, long totalLen, File out, Progress p) throws IOException {
        final boolean playback = url.contains("/videoplayback");
        try (FileOutputStream fo = new FileOutputStream(out)) {
            if (!playback) {
                HttpURLConnection c = open(url, false);
                try { copy(c, fo, p); } finally { c.disconnect(); }
                return;
            }
            long pos = 0;
            int rn = 1;
            while (true) {
                long end = pos + CHUNK - 1;
                if (totalLen > 0 && end >= totalLen) end = totalLen - 1;
                final long want = end - pos + 1;
                String u = url + "&range=" + pos + "-" + end + "&rn=" + (rn++);
                HttpURLConnection c = open(u, true);
                long got;
                try { got = copy(c, fo, p); } finally { c.disconnect(); }
                pos += got;
                if (got <= 0) break;
                if (totalLen > 0 ? pos >= totalLen : got < want) break;
            }
            if (pos == 0) throw new IOException("File kosong");
        }
    }

    private static HttpURLConnection open(String url, boolean post) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        String ua = HttpDownloader.USER_AGENT;
        try {
            if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
                ua = YoutubeParsingHelper.getVisionOsUserAgent(null);
            } else if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
                c.setRequestProperty("Origin", "https://www.youtube.com");
                c.setRequestProperty("Referer", "https://www.youtube.com/");
            }
        } catch (Throwable ignored) { }
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (post) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(POST_BODY.length);
            try (OutputStream os = c.getOutputStream()) { os.write(POST_BODY); }
        }
        return c;
    }

    private static long copy(HttpURLConnection c, OutputStream out, Progress p) throws IOException {
        int code = c.getResponseCode();
        if (code != 200 && code != 206) throw new IOException("HTTP " + code);
        long n = 0;
        try (InputStream is = c.getInputStream()) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = is.read(buf)) > 0) {
                out.write(buf, 0, r);
                n += r;
                if (p != null) p.onBytes(r);
            }
        }
        return n;
    }

    /** Gabungkan video-only (mp4) dan audio (m4a) menjadi satu MP4 tanpa encode ulang. */
    static void mux(File video, File audio, File out) throws IOException {
        MediaExtractor ve = new MediaExtractor();
        MediaExtractor ae = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            ve.setDataSource(video.getPath());
            ae.setDataSource(audio.getPath());
            int vt = findTrack(ve, "video/");
            int at = findTrack(ae, "audio/");
            if (vt < 0 || at < 0) throw new IOException("Track tidak ditemukan");
            ve.selectTrack(vt);
            ae.selectTrack(at);
            MediaFormat vf = ve.getTrackFormat(vt);
            MediaFormat af = ae.getTrackFormat(at);

            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int mv = muxer.addTrack(vf);
            int ma = muxer.addTrack(af);
            muxer.start();

            int size = 4 * 1024 * 1024;
            if (vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                size = Math.max(size, vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            ByteBuffer buf = ByteBuffer.allocate(size);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            copySamples(ve, muxer, mv, buf, info);
            copySamples(ae, muxer, ma, buf, info);
            muxer.stop();
        } finally {
            if (muxer != null) try { muxer.release(); } catch (Exception ignored) { }
            ve.release();
            ae.release();
        }
    }

    private static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void copySamples(MediaExtractor ex, MediaMuxer m, int track,
                                    ByteBuffer buf, MediaCodec.BufferInfo info) {
        while (true) {
            buf.clear();
            int n = ex.readSampleData(buf, 0);
            if (n < 0) break;
            info.offset = 0;
            info.size = n;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            m.writeSampleData(track, buf, info);
            ex.advance();
        }
    }

    /** Simpan file ke Galeri (Movies/YT Shorts) atau Musik (Music/YT Shorts). */
    static boolean saveToGallery(Context ctx, File src, String fileName, boolean audioOnly) {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        Uri collection;
        if (audioOnly) {
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/YT Shorts");
        } else {
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/YT Shorts");
        }
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(collection, cv);
        if (uri == null) return false;
        try (InputStream in = new FileInputStream(src); OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("no stream");
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) os.write(b, 0, r);
        } catch (Exception e) {
            cr.delete(uri, null, null);
            return false;
        }
        cv.clear();
        cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, cv, null, null);
        return true;
    }
}

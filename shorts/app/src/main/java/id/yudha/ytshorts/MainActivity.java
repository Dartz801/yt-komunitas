package id.yudha.ytshorts;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.net.Uri;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int RED = Color.rgb(255, 0, 51);
    private static boolean npInit = false;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService imgPool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText input;
    private TextView status, titleView;
    private ImageView thumb;
    private LinearLayout options;
    private ProgressBar progress;
    private Button wpBtn;
    private static final int PICK_VIDEO = 42;
    private boolean busy = false;
    private int loadToken = 0;

    static class Option {
        String label;
        VideoStream video;   // video-only atau video+audio
        AudioStream audio;   // audio untuk digabung, atau audio saja
        boolean muxed;       // video sudah berisi audio
        long size;
        String suffix;
    }

    // ================= UI =================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 18));
        root.setPadding(pad, pad, pad, 0);
        root.setFitsSystemWindows(true);

        TextView head = text("Unduh YouTube Shorts", 18, Color.WHITE);
        head.setPadding(0, 0, 0, dp(8));
        root.addView(head);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("Tempel link Shorts…");
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button paste = button("Tempel", Color.DKGRAY);
        paste.setOnClickListener(v -> pasteAndLoad());
        row.addView(paste);
        Button go = button("Cari", RED);
        go.setOnClickListener(v -> load(input.getText().toString()));
        row.addView(go);
        root.addView(row);

        status = text("Bagikan Shorts dari aplikasi YouTube ke aplikasi ini, atau tempel link-nya.", 14, Color.LTGRAY);
        status.setPadding(0, dp(8), 0, dp(6));
        root.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));

        wpBtn = button("🖼  Jadikan wallpaper bergerak", Color.rgb(0, 120, 215));
        wpBtn.setTextSize(16);
        wpBtn.setVisibility(View.GONE);
        wpBtn.setOnClickListener(v -> applyWallpaper(Uri.fromFile(lastVideo())));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        wlp.setMargins(0, dp(6), 0, 0);
        root.addView(wpBtn, wlp);

        ScrollView sv = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(24));

        LinearLayout media = new LinearLayout(this);
        media.setGravity(Gravity.CENTER_VERTICAL);
        thumb = new ImageView(this);
        thumb.setAdjustViewBounds(true);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setVisibility(View.GONE);
        media.addView(thumb, new LinearLayout.LayoutParams(dp(110), dp(196)));
        titleView = text("", 15, Color.WHITE);
        titleView.setPadding(dp(12), 0, 0, 0);
        media.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        content.addView(media);

        options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(0, dp(12), 0, 0);
        content.addView(options);

        Button pick = button("Pilih video dari Galeri untuk wallpaper", Color.rgb(45, 45, 45));
        pick.setTextSize(14);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            startActivityForResult(i, PICK_VIDEO);
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        plp.setMargins(0, dp(20), 0, 0);
        content.addView(pick, plp);

        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent it) {
        if (it == null || !Intent.ACTION_SEND.equals(it.getAction())) return;
        String u = extractUrl(String.valueOf(it.getStringExtra(Intent.EXTRA_TEXT)));
        if (u != null) { input.setText(u); load(u); }
        else status.setText("Tidak menemukan link di teks yang dibagikan.");
    }

    private void pasteAndLoad() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData cd = cm != null ? cm.getPrimaryClip() : null;
        if (cd != null && cd.getItemCount() > 0) {
            String u = extractUrl(String.valueOf(cd.getItemAt(0).coerceToText(this)));
            if (u != null) { input.setText(u); load(u); return; }
        }
        toast("Clipboard tidak berisi link");
    }

    private static String extractUrl(String s) {
        Matcher m = Pattern.compile("https?://\\S+").matcher(s);
        return m.find() ? m.group() : null;
    }

    // ================= Ambil info video =================
    private void load(String raw) {
        if (busy) { toast("Tunggu unduhan selesai dulu"); return; }
        String u = extractUrl(raw == null ? "" : raw.trim());
        if (u == null) { toast("Link tidak valid"); return; }
        final String url = u.replaceFirst("^https?://m\\.youtube\\.com", "https://www.youtube.com");
        final int token = ++loadToken;
        options.removeAllViews();
        wpBtn.setVisibility(View.GONE);
        thumb.setVisibility(View.GONE);
        titleView.setText("");
        status.setText("Memuat info video…");
        worker.execute(() -> {
            try {
                if (!npInit) { NewPipe.init(new HttpDownloader()); npInit = true; }
                StreamInfo info = StreamInfo.getInfo(url);
                List<Option> opts = buildOptions(info);
                ui.post(() -> { if (token == loadToken) show(info, opts); });
            } catch (Throwable e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ui.post(() -> { if (token == loadToken) status.setText("Gagal memuat: " + msg); });
            }
        });
    }

    private static boolean usable(Stream s) {
        return s != null && s.isUrl() && s.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP;
    }

    private static long sizeOf(Stream s) {
        try {
            ItagItem it = s.getItagItem();
            return it == null ? -1 : it.getContentLength();
        } catch (Throwable e) { return -1; }
    }

    static List<Option> buildOptions(StreamInfo info) {
        List<Option> out = new ArrayList<>();

        AudioStream bestAudio = null;
        for (AudioStream a : info.getAudioStreams()) {
            if (!usable(a) || a.getFormat() != MediaFormat.M4A) continue;
            if (bestAudio == null || a.getAverageBitrate() > bestAudio.getAverageBitrate()) bestAudio = a;
        }

        // Video tanpa audio (kualitas tinggi), digabung dengan audio terbaik. Pilih H.264 agar bisa digabung.
        if (bestAudio != null) {
            Map<Integer, VideoStream> byHeight = new LinkedHashMap<>();
            for (VideoStream v : info.getVideoOnlyStreams()) {
                if (!usable(v) || v.getFormat() != MediaFormat.MPEG_4) continue;
                String codec = v.getCodec();
                if (codec != null && !codec.startsWith("avc")) continue;
                VideoStream cur = byHeight.get(v.getHeight());
                if (cur == null || v.getFps() > cur.getFps() || v.getBitrate() > cur.getBitrate())
                    byHeight.put(v.getHeight(), v);
            }
            List<VideoStream> list = new ArrayList<>(byHeight.values());
            list.sort((x, y) -> Integer.compare(y.getHeight(), x.getHeight()));
            for (VideoStream v : list) {
                Option o = new Option();
                o.video = v;
                o.audio = bestAudio;
                o.size = sizeOf(v) > 0 && sizeOf(bestAudio) > 0 ? sizeOf(v) + sizeOf(bestAudio) : -1;
                o.label = "Video " + v.getResolution();
                o.suffix = v.getHeight() + "p";
                out.add(o);
            }
        }

        // Video yang sudah berisi audio (biasanya 360p) sebagai cadangan.
        for (VideoStream v : info.getVideoStreams()) {
            if (!usable(v) || v.getFormat() != MediaFormat.MPEG_4) continue;
            Option o = new Option();
            o.video = v;
            o.muxed = true;
            o.size = sizeOf(v);
            o.label = "Video " + v.getResolution() + " (cadangan)";
            o.suffix = v.getHeight() + "p";
            out.add(o);
        }

        if (bestAudio != null) {
            Option o = new Option();
            o.audio = bestAudio;
            o.size = sizeOf(bestAudio);
            o.label = "Audio saja (M4A)";
            o.suffix = "audio";
            out.add(o);
        }
        return out;
    }

    private void show(StreamInfo info, List<Option> opts) {
        titleView.setText(info.getName() + "\n\n" + info.getUploaderName());
        String tu = bestThumb(info.getThumbnails());
        if (tu != null) {
            imgPool.execute(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(tu).openConnection();
                    byte[] d = HttpDownloader.readAll(c.getInputStream());
                    Bitmap bm = BitmapFactory.decodeByteArray(d, 0, d.length);
                    ui.post(() -> { thumb.setImageBitmap(bm); thumb.setVisibility(View.VISIBLE); });
                } catch (Exception ignored) { }
            });
        }
        if (opts.isEmpty()) {
            status.setText("Tidak ada format yang bisa diunduh untuk video ini.");
            return;
        }
        status.setText("Pilih kualitas. Disimpan ke Galeri › YT Shorts");
        final String base = safeName(info.getName());
        for (int i = 0; i < opts.size(); i++) {
            Option o = opts.get(i);
            String sz = o.size > 0 ? String.format(Locale.US, "  •  %.1f MB", o.size / 1048576.0) : "";
            Button b = button((i == 0 ? "⬇ " : "") + o.label + sz, i == 0 ? RED : Color.rgb(60, 60, 60));
            b.setTextSize(i == 0 ? 17 : 15);
            b.setOnClickListener(v -> start(o, base));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(i == 0 ? 58 : 48));
            lp.setMargins(0, 0, 0, dp(8));
            options.addView(b, lp);
        }
    }

    private static String bestThumb(List<Image> imgs) {
        Image best = null;
        for (Image im : imgs) if (best == null || im.getHeight() > best.getHeight()) best = im;
        return best == null ? null : best.getUrl();
    }

    private static String safeName(String s) {
        String n = (s == null ? "shorts" : s).replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_").trim();
        if (n.length() > 80) n = n.substring(0, 80).trim();
        return n.isEmpty() ? "shorts" : n;
    }

    // ================= Unduh =================
    private void start(Option o, String base) {
        if (busy) return;
        busy = true;
        setOptionsEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(o.size <= 0);
        progress.setProgress(0);
        status.setText("Mengunduh…");

        worker.execute(() -> {
            File dir = getCacheDir();
            File v = new File(dir, "v.tmp"), a = new File(dir, "a.tmp"), out = new File(dir, "out.tmp");
            boolean ok = false;
            String err = null;
            AtomicLong done = new AtomicLong();
            MediaTools.Progress p = add -> {
                long d = done.addAndGet(add);
                if (o.size > 0) ui.post(() -> {
                    progress.setProgress((int) Math.min(1000, d * 1000 / o.size));
                    status.setText(String.format(Locale.US, "Mengunduh… %.1f / %.1f MB", d / 1048576.0, o.size / 1048576.0));
                });
            };
            try {
                if (o.video == null) {                       // audio saja
                    MediaTools.downloadStream(o.audio.getContent(), sizeOf(o.audio), a, p);
                    ok = MediaTools.saveToGallery(this, a, base + ".m4a", true);
                } else if (o.muxed) {                        // video sudah ada audionya
                    MediaTools.downloadStream(o.video.getContent(), sizeOf(o.video), out, p);
                    ok = MediaTools.saveToGallery(this, out, base + "_" + o.suffix + ".mp4", false);
                } else {                                     // video + audio digabung
                    MediaTools.downloadStream(o.video.getContent(), sizeOf(o.video), v, p);
                    MediaTools.downloadStream(o.audio.getContent(), sizeOf(o.audio), a, p);
                    ui.post(() -> { progress.setIndeterminate(true); status.setText("Menggabungkan video & audio…"); });
                    MediaTools.mux(v, a, out);
                    ok = MediaTools.saveToGallery(this, out, base + "_" + o.suffix + ".mp4", false);
                }
                if (!ok) err = "Gagal menyimpan ke Galeri";
                else if (o.video != null) {
                    try { copyStream(new java.io.FileInputStream(out), lastVideo()); } catch (Exception ignored) { }
                }
            } catch (Throwable e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                v.delete(); a.delete(); out.delete();
            }
            final boolean fok = ok;
            final String ferr = err;
            ui.post(() -> {
                busy = false;
                setOptionsEnabled(true);
                progress.setIndeterminate(false);
                progress.setVisibility(View.GONE);
                if (fok) {
                    status.setText("✓ Tersimpan di Galeri › " + (o.video == null ? "Music/YT Shorts" : "Movies/YT Shorts"));
                    toast("Tersimpan!");
                    if (o.video != null && lastVideo().exists()) wpBtn.setVisibility(View.VISIBLE);
                } else {
                    status.setText("✗ Gagal: " + ferr + "\nCoba pilih kualitas lain (misalnya yang 'cadangan').");
                }
            });
        });
    }

    private void setOptionsEnabled(boolean en) {
        for (int i = 0; i < options.getChildCount(); i++) {
            View c = options.getChildAt(i);
            c.setEnabled(en);
            c.setAlpha(en ? 1f : 0.5f);
        }
    }

    // ================= Wallpaper bergerak =================
    private File lastVideo() { return new File(getFilesDir(), "last.mp4"); }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_VIDEO && res == RESULT_OK && data != null && data.getData() != null) {
            applyWallpaper(data.getData());
        }
    }

    /** Salin video ke penyimpanan aplikasi lalu buka layar pemasangan wallpaper. */
    private void applyWallpaper(Uri src) {
        status.setText("Menyiapkan wallpaper…");
        worker.execute(() -> {
            boolean ok;
            try {
                File tmp = new File(getFilesDir(), "wallpaper.tmp");
                copyStream(getContentResolver().openInputStream(src), tmp);
                ok = tmp.renameTo(VideoWallpaperService.videoFile(this));
            } catch (Exception e) { ok = false; }
            final boolean fok = ok;
            ui.post(() -> {
                if (!fok) { status.setText("✗ Gagal menyiapkan video wallpaper"); return; }
                status.setText("Tekan \"Pasang wallpaper\", lalu pilih layar utama dan/atau layar kunci.");
                Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(this, VideoWallpaperService.class));
                try { startActivity(i); }
                catch (Exception e) {
                    try { startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); }
                    catch (Exception e2) { toast("HP ini tidak mendukung wallpaper bergerak"); }
                }
            });
        });
    }

    private static void copyStream(InputStream in, File dst) throws Exception {
        if (in == null) throw new Exception("no input");
        try (InputStream is = in; OutputStream os = new FileOutputStream(dst)) {
            byte[] b = new byte[65536];
            int r;
            while ((r = is.read(b)) > 0) os.write(b, 0, r);
        }
    }

    // ================= util tampilan =================
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setPadding(dp(14), 0, dp(14), 0);
        return b;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}

package id.yudha.ytkomunitas;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final String FOLDER = "Pictures/YT Komunitas";
    private static final int RED = Color.rgb(255, 0, 51);

    private final ExecutorService net = Executors.newFixedThreadPool(3);
    private final ExecutorService dlQueue = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText input;
    private TextView status;
    private Button allBtn;
    private LinearLayout list;
    private final List<String[]> items = new ArrayList<>(); // {url, nama file}
    private int loadToken = 0;

    static class Post {
        String id, text;
        List<String> urls = new ArrayList<>();
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

        TextView title = text("Unduh Gambar Komunitas YouTube", 18, Color.WHITE);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("Tempel link postingan…");
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

        status = text("Bagikan postingan dari aplikasi YouTube ke aplikasi ini, atau tempel link-nya.", 14, Color.LTGRAY);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        allBtn = button("Unduh semua", RED);
        allBtn.setTextSize(17);
        allBtn.setVisibility(View.GONE);
        allBtn.setOnClickListener(v -> downloadAll());
        root.addView(allBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(24));
        sv.addView(list);
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
        if (it == null) return;
        String t = null;
        if (Intent.ACTION_SEND.equals(it.getAction())) t = it.getStringExtra(Intent.EXTRA_TEXT);
        else if (Intent.ACTION_VIEW.equals(it.getAction()) && it.getData() != null) t = it.getData().toString();
        if (t != null) {
            String u = extractUrl(t);
            if (u != null) { input.setText(u); load(u); }
            else status.setText("Tidak menemukan link di teks yang dibagikan.");
        }
    }

    private void pasteAndLoad() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData cd = cm != null ? cm.getPrimaryClip() : null;
        if (cd != null && cd.getItemCount() > 0) {
            CharSequence s = cd.getItemAt(0).coerceToText(this);
            String u = extractUrl(String.valueOf(s));
            if (u != null) { input.setText(u); load(u); return; }
        }
        toast("Clipboard tidak berisi link");
    }

    private static String extractUrl(String s) {
        Matcher m = Pattern.compile("https?://\\S+").matcher(s);
        return m.find() ? m.group() : null;
    }

    // ================= Ambil & parse =================
    private void load(String raw) {
        String u = extractUrl(raw == null ? "" : raw.trim());
        if (u == null) { toast("Link tidak valid"); return; }
        final String url = u.replaceFirst("^https?://(m\\.|www\\.)?youtube\\.com", "https://www.youtube.com");
        final int token = ++loadToken;
        list.removeAllViews();
        items.clear();
        allBtn.setVisibility(View.GONE);
        status.setText("Memuat postingan…");
        net.execute(() -> {
            List<Post> posts;
            String err = null;
            try {
                String html = new String(get(url), "UTF-8");
                JSONObject data = parseInitialData(html);
                posts = data == null ? new ArrayList<>() : findPosts(data);
                if (data == null) err = "Data halaman tidak terbaca (format YouTube mungkin berubah).";
            } catch (Exception e) {
                posts = new ArrayList<>();
                err = "Gagal memuat: " + e.getMessage();
            }
            final List<Post> fp = posts;
            final String fe = err;
            ui.post(() -> { if (token == loadToken) render(fp, fe); });
        });
    }

    static JSONObject parseInitialData(String html) throws Exception {
        Matcher m = Pattern.compile("ytInitialData\"?\\]?\\s*=\\s*\\{").matcher(html);
        if (!m.find()) return null;
        int start = m.end() - 1;
        int end = html.indexOf(";</script>", start);
        if (end < 0) return null;
        return new JSONObject(html.substring(start, end));
    }

    static String baseOf(String u) {
        if (u.startsWith("//")) u = "https:" + u;
        int i = u.indexOf('=');
        return i < 0 ? u : u.substring(0, i);
    }

    static String sized(String u, String s) { return baseOf(u) + "=" + s; }

    static String textOf(JSONObject t) {
        if (t == null) return "";
        if (t.has("simpleText")) return t.optString("simpleText");
        JSONArray runs = t.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) sb.append(r.optString("text"));
        }
        return sb.toString();
    }

    /** BFS supaya urutan gambar di carousel tetap sesuai. */
    static List<String> images(Object root) {
        List<String> out = new ArrayList<>();
        List<Object> q = new ArrayList<>();
        q.add(root);
        for (int i = 0; i < q.size(); i++) {
            Object n = q.get(i);
            if (n instanceof JSONObject) {
                JSONObject o = (JSONObject) n;
                JSONObject r = o.optJSONObject("backstageImageRenderer");
                if (r != null) {
                    JSONObject img = r.optJSONObject("image");
                    JSONArray th = img == null ? null : img.optJSONArray("thumbnails");
                    if (th != null && th.length() > 0) {
                        String u = th.optJSONObject(th.length() - 1).optString("url");
                        if (!u.isEmpty()) out.add(u);
                    }
                    continue;
                }
                Iterator<String> it = o.keys();
                while (it.hasNext()) q.add(o.opt(it.next()));
            } else if (n instanceof JSONArray) {
                JSONArray a = (JSONArray) n;
                for (int j = 0; j < a.length(); j++) q.add(a.opt(j));
            }
        }
        return out;
    }

    static List<Post> findPosts(JSONObject data) {
        List<Post> posts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Object> q = new ArrayList<>();
        q.add(data);
        for (int i = 0; i < q.size(); i++) {
            Object n = q.get(i);
            if (n instanceof JSONObject) {
                JSONObject o = (JSONObject) n;
                JSONObject p = o.optJSONObject("backstagePostRenderer");
                if (p == null) p = o.optJSONObject("postRenderer");
                if (p != null && p.has("postId")) {
                    Post post = new Post();
                    post.id = p.optString("postId");
                    post.text = textOf(p.optJSONObject("contentText"));
                    Object att = p.opt("backstageAttachment");
                    if (att != null) for (String u : images(att)) if (seen.add(baseOf(u))) post.urls.add(u);
                    if (!post.urls.isEmpty()) posts.add(post);
                    continue;
                }
                Iterator<String> it = o.keys();
                while (it.hasNext()) q.add(o.opt(it.next()));
            } else if (n instanceof JSONArray) {
                JSONArray a = (JSONArray) n;
                for (int j = 0; j < a.length(); j++) q.add(a.opt(j));
            }
        }
        Post loose = new Post();
        loose.id = "lainnya";
        loose.text = "Gambar lain";
        for (String u : images(data)) if (seen.add(baseOf(u))) loose.urls.add(u);
        if (!loose.urls.isEmpty()) posts.add(loose);
        return posts;
    }

    // ================= Tampilkan =================
    private void render(List<Post> posts, String err) {
        for (Post p : posts) {
            String cap = p.text == null ? "" : p.text.trim();
            if (cap.length() > 160) cap = cap.substring(0, 160) + "…";
            if (cap.isEmpty()) cap = "(tanpa teks)";
            TextView t = text(cap + "  •  " + p.urls.size() + " gambar", 13, Color.LTGRAY);
            t.setPadding(0, dp(16), 0, dp(8));
            list.addView(t);
            for (int i = 0; i < p.urls.size(); i++) {
                final String u = p.urls.get(i);
                final String name = "ytpost_" + p.id + "_" + (i + 1);
                items.add(new String[]{u, name});

                ImageView iv = new ImageView(this);
                iv.setAdjustViewBounds(true);
                iv.setBackgroundColor(Color.rgb(34, 34, 34));
                iv.setMinimumHeight(dp(120));
                list.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                net.execute(() -> {
                    try {
                        byte[] bytes = get(sized(u, "s800"));
                        Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        ui.post(() -> iv.setImageBitmap(bm));
                    } catch (Exception ignored) { }
                });

                Button b = button("Unduh gambar " + (i + 1), RED);
                b.setOnClickListener(v -> {
                    b.setEnabled(false);
                    b.setText("Mengunduh…");
                    dlQueue.execute(() -> {
                        boolean ok = download(u, name);
                        ui.post(() -> { b.setText(ok ? "✓ Tersimpan" : "✗ Gagal, coba lagi"); b.setEnabled(!ok); });
                    });
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
                lp.setMargins(0, dp(6), 0, dp(12));
                list.addView(b, lp);
            }
        }
        if (items.isEmpty()) {
            status.setText(err != null ? err : "Tidak ada gambar di link ini. Pastikan itu link postingan Komunitas.");
            return;
        }
        status.setText(items.size() + " gambar ditemukan • disimpan ke folder " + FOLDER);
        allBtn.setText("Unduh semua (" + items.size() + ")");
        allBtn.setEnabled(true);
        allBtn.setVisibility(View.VISIBLE);
    }

    private void downloadAll() {
        allBtn.setEnabled(false);
        final List<String[]> copy = new ArrayList<>(items);
        dlQueue.execute(() -> {
            int ok = 0;
            for (int i = 0; i < copy.size(); i++) {
                final int n = i + 1;
                ui.post(() -> allBtn.setText("Mengunduh " + n + "/" + copy.size() + "…"));
                if (download(copy.get(i)[0], copy.get(i)[1])) ok++;
            }
            final int fok = ok;
            ui.post(() -> {
                allBtn.setText("✓ " + fok + "/" + copy.size() + " tersimpan");
                allBtn.setEnabled(true);
                toast(fok + " gambar disimpan di Galeri › YT Komunitas");
            });
        });
    }

    // ================= Jaringan & simpan =================
    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally { c.disconnect(); }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.8");
        return c;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }

    /** Coba resolusi asli dulu (=s0), lalu ukuran besar sebagai cadangan. */
    private boolean download(String u, String name) {
        for (String s : new String[]{"s0", "s4096", "s2048"}) {
            HttpURLConnection c = null;
            try {
                c = open(sized(u, s));
                if (c.getResponseCode() != 200) continue;
                String mime = c.getContentType();
                if (mime == null || !mime.startsWith("image/")) mime = "image/jpeg";
                mime = mime.split(";")[0].trim();
                String ext = mime.substring(6).replace("jpeg", "jpg");
                byte[] data = readAll(c.getInputStream());
                return save(data, name + "." + ext, mime);
            } catch (Exception ignored) {
            } finally { if (c != null) c.disconnect(); }
        }
        return false;
    }

    private boolean save(byte[] data, String fileName, String mime) {
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        cv.put(MediaStore.Images.Media.MIME_TYPE, mime);
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER);
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) return false;
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new Exception("no stream");
            os.write(data);
        } catch (Exception e) {
            cr.delete(uri, null, null);
            return false;
        }
        cv.clear();
        cv.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, cv, null, null);
        return true;
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

    private void toast(String s) {
        if (!TextUtils.isEmpty(s)) Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

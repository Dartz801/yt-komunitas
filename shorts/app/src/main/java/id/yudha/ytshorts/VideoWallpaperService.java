package id.yudha.ytshorts;

import android.content.Context;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.wallpaper.WallpaperService;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Wallpaper bergerak: memutar video berulang tanpa suara.
 * Digambar dengan OpenGL supaya ukurannya mengikuti layar HP yang sebenarnya,
 * walaupun launcher membuat area wallpaper lebih lebar dari layar.
 */
public class VideoWallpaperService extends WallpaperService {

    static final String PREFS = "wallpaper";
    static final String KEY_FILL = "fill";

    static File videoFile(Context c) {
        return new File(c.getFilesDir(), "wallpaper.mp4");
    }

    static boolean isFill(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FILL, true);
    }

    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    private static final String VS =
            "attribute vec4 aPos;\n" +
            "attribute vec4 aTex;\n" +
            "uniform mat4 uTex;\n" +
            "varying vec2 vTex;\n" +
            "void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }\n";

    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main() { gl_FragColor = texture2D(sTex, vTex); }\n";

    private static final float[] QUAD = {
            // x, y, u, v
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
    };

    class VideoEngine extends Engine implements SurfaceTexture.OnFrameAvailableListener {
        private HandlerThread thread;
        private Handler gl;

        // semua field di bawah hanya disentuh dari thread GL
        private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurf = EGL14.EGL_NO_SURFACE;
        private int prog, tex, aPos, aTex, uTex;
        private FloatBuffer quad;
        private SurfaceTexture st;
        private Surface videoSurface;
        private MediaPlayer mp;
        private final float[] texMatrix = new float[16];
        private int surfW, surfH, vidW, vidH;
        private float xOffset = 0.5f;
        private boolean fill = true;
        private long loadedStamp = -1;
        private boolean visible = false;

        @Override
        public void onCreate(SurfaceHolder h) {
            super.onCreate(h);
            thread = new HandlerThread("wallpaper-gl");
            thread.start();
            gl = new Handler(thread.getLooper());
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder h) {
            super.onSurfaceCreated(h);
            final Surface s = h.getSurface();
            gl.post(() -> { initGl(s); startPlayer(); });
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder h, int format, int w, int hh) {
            super.onSurfaceChanged(h, format, w, hh);
            gl.post(() -> { surfW = w; surfH = hh; draw(false); });
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder h) {
            super.onSurfaceRedrawNeeded(h);
            gl.post(() -> draw(false));
        }

        @Override
        public void onOffsetsChanged(float xOff, float yOff, float xStep, float yStep, int xPx, int yPx) {
            gl.post(() -> { xOffset = xOff; draw(false); });
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            gl.post(() -> {
                visible = v;
                if (v) {
                    File f = videoFile(VideoWallpaperService.this);
                    boolean newFill = isFill(VideoWallpaperService.this);
                    if (mp == null || f.lastModified() != loadedStamp) startPlayer();
                    else mp.start();
                    if (newFill != fill) { fill = newFill; draw(false); }
                } else if (mp != null) {
                    mp.pause(); // hemat baterai saat tidak terlihat
                }
            });
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder h) {
            // surface harus dilepas sebelum fungsi ini selesai
            final CountDownLatch done = new CountDownLatch(1);
            gl.post(() -> { releaseAll(); done.countDown(); });
            try { done.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            super.onSurfaceDestroyed(h);
        }

        @Override
        public void onDestroy() {
            gl.post(this::releaseAll);
            thread.quitSafely();
            super.onDestroy();
        }

        @Override
        public void onFrameAvailable(SurfaceTexture s) {
            draw(true); // dipanggil di thread GL
        }

        // ---------------- GL ----------------
        private void initGl(Surface s) {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            EGL14.eglInitialize(dpy, ver, 0, ver, 1);
            int[] attr = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, n, 0);
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            eglSurf = EGL14.eglCreateWindowSurface(dpy, cfg[0], s, new int[]{EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(dpy, eglSurf, eglSurf, ctx);

            prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, VS));
            GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, FS));
            GLES20.glLinkProgram(prog);
            aPos = GLES20.glGetAttribLocation(prog, "aPos");
            aTex = GLES20.glGetAttribLocation(prog, "aTex");
            uTex = GLES20.glGetUniformLocation(prog, "uTex");

            quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(QUAD).position(0);

            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            tex = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            st = new SurfaceTexture(tex);
            st.setOnFrameAvailableListener(this, gl);
            videoSurface = new Surface(st);
            fill = isFill(VideoWallpaperService.this);
        }

        private int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }

        /** Lebar layar HP sebenarnya (bukan lebar area wallpaper). */
        private int screenWidth() {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                Display d = wm.getDefaultDisplay();
                Point p = new Point();
                d.getRealSize(p);
                return p.x;
            } catch (Exception e) {
                return surfW;
            }
        }

        private void draw(boolean newFrame) {
            if (eglSurf == EGL14.EGL_NO_SURFACE || st == null || surfW == 0 || surfH == 0) return;
            if (newFrame) {
                try { st.updateTexImage(); } catch (Exception e) { return; }
            }
            st.getTransformMatrix(texMatrix);

            GLES20.glViewport(0, 0, surfW, surfH);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            if (vidW > 0 && vidH > 0) {
                // Area yang benar-benar terlihat di layar
                int screenW = Math.min(surfW, screenWidth());
                int left = Math.round(xOffset * (surfW - screenW));
                float scale = fill
                        ? Math.max((float) screenW / vidW, (float) surfH / vidH)   // penuh, sisi terpotong sedikit
                        : Math.min((float) screenW / vidW, (float) surfH / vidH);  // utuh, ada garis hitam
                int dw = Math.round(vidW * scale);
                int dh = Math.round(vidH * scale);
                int x0 = left + (screenW - dw) / 2;
                int y0 = (surfH - dh) / 2;
                GLES20.glViewport(x0, y0, dw, dh);

                GLES20.glUseProgram(prog);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
                GLES20.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0);
                quad.position(0);
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
                GLES20.glEnableVertexAttribArray(aPos);
                quad.position(2);
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
                GLES20.glEnableVertexAttribArray(aTex);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }
            EGL14.eglSwapBuffers(dpy, eglSurf);
        }

        // ---------------- Player ----------------
        private void startPlayer() {
            releasePlayer();
            File f = videoFile(VideoWallpaperService.this);
            if (videoSurface == null || !f.exists()) { draw(false); return; }
            fill = isFill(VideoWallpaperService.this);
            try {
                mp = new MediaPlayer();
                mp.setSurface(videoSurface);
                mp.setDataSource(f.getPath());
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
                mp.setOnVideoSizeChangedListener((p, w, h) -> gl.post(() -> {
                    vidW = w; vidH = h; draw(false);
                }));
                mp.prepare();
                vidW = mp.getVideoWidth();
                vidH = mp.getVideoHeight();
                mp.start();
                loadedStamp = f.lastModified();
                if (!visible && !isPreview()) mp.pause();
            } catch (Exception e) {
                releasePlayer();
            }
        }

        private void releasePlayer() {
            if (mp != null) {
                try { mp.release(); } catch (Exception ignored) { }
                mp = null;
            }
        }

        private void releaseAll() {
            releasePlayer();
            if (videoSurface != null) { videoSurface.release(); videoSurface = null; }
            if (st != null) { st.release(); st = null; }
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, eglSurf);
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
            }
            dpy = EGL14.EGL_NO_DISPLAY;
            ctx = EGL14.EGL_NO_CONTEXT;
            eglSurf = EGL14.EGL_NO_SURFACE;
            loadedStamp = -1;
        }
    }
}

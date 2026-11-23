package id.yudha.ytshorts;

import android.content.Context;
import android.media.MediaPlayer;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.io.File;

/** Wallpaper bergerak: memutar video terpilih berulang-ulang tanpa suara. */
public class VideoWallpaperService extends WallpaperService {

    static File videoFile(Context c) {
        return new File(c.getFilesDir(), "wallpaper.mp4");
    }

    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    class VideoEngine extends Engine {
        private MediaPlayer mp;
        private SurfaceHolder holder;
        private long loadedStamp = -1;

        @Override
        public void onSurfaceCreated(SurfaceHolder h) {
            super.onSurfaceCreated(h);
            holder = h;
            startPlayer();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder h) {
            releasePlayer();
            holder = null;
            super.onSurfaceDestroyed(h);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (visible) {
                File f = videoFile(VideoWallpaperService.this);
                // muat ulang kalau videonya baru diganti
                if (mp == null || f.lastModified() != loadedStamp) startPlayer();
                else mp.start();
            } else if (mp != null) {
                mp.pause(); // hemat baterai saat tidak terlihat
            }
        }

        @Override
        public void onDestroy() {
            releasePlayer();
            super.onDestroy();
        }

        private void startPlayer() {
            releasePlayer();
            File f = videoFile(VideoWallpaperService.this);
            if (holder == null || !f.exists()) return;
            try {
                mp = new MediaPlayer();
                mp.setSurface(holder.getSurface());
                mp.setDataSource(f.getPath());
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
                mp.prepare();
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                mp.start();
                loadedStamp = f.lastModified();
                if (!isVisible()) mp.pause();
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
    }
}

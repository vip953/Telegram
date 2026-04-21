package org.telegram.messenger.voip;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;
import org.webrtc.CapturerObserver;
import org.webrtc.NV21Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;

public class LocalVideoFileCapturer implements VideoCapturer {

    public interface FailureCallback {
        void onFailure(String reason);
    }

    private final Context context;
    private final Uri uri;
    private final FailureCallback failureCallback;

    private MediaMetadataRetriever retriever;
    private CapturerObserver capturerObserver;
    private HandlerThread workerThread;
    private Handler workerHandler;

    private volatile boolean running;
    private long durationUs;
    private int targetWidth = 640;
    private int targetHeight = 360;
    private int targetFps = 30;
    private long startRealtimeMs;

    @Nullable
    public static LocalVideoFileCapturer create(Context context, Uri uri, FailureCallback callback) {
        if (uri == null) {
            return null;
        }
        LocalVideoFileCapturer capturer = new LocalVideoFileCapturer(context, uri, callback);
        if (!capturer.prepare()) {
            return null;
        }
        return capturer;
    }

    private LocalVideoFileCapturer(Context context, Uri uri, FailureCallback failureCallback) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.failureCallback = failureCallback;
    }

    private boolean prepare() {
        retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationMs != null) {
                long parsed = Long.parseLong(durationMs);
                durationUs = Math.max(parsed * 1000L, 33_333L);
            } else {
                durationUs = 10_000_000L;
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            reportFailure("Unable to read selected file");
            releaseRetriever();
            return false;
        }
    }

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context context, CapturerObserver capturerObserver) {
        this.capturerObserver = capturerObserver;
    }

    @Override
    public void startCapture(int width, int height, int framerate) {
        targetWidth = width > 0 ? width : targetWidth;
        targetHeight = height > 0 ? height : targetHeight;
        targetFps = framerate > 0 ? framerate : 30;
        if (workerThread == null) {
            workerThread = new HandlerThread("LocalVideoFileCapturer");
            workerThread.start();
            workerHandler = new Handler(workerThread.getLooper());
        }
        startRealtimeMs = SystemClock.elapsedRealtime();
        running = true;
        if (capturerObserver != null) {
            capturerObserver.onCapturerStarted(true);
        }
        scheduleNextFrame();
    }

    private void scheduleNextFrame() {
        if (!running || workerHandler == null) {
            return;
        }
        long frameDelay = Math.max(8L, 1000L / Math.max(1, targetFps));
        workerHandler.postDelayed(this::deliverFrame, frameDelay);
    }

    private void deliverFrame() {
        if (!running || capturerObserver == null || retriever == null) {
            return;
        }
        try {
            long elapsedUs = ((SystemClock.elapsedRealtime() - startRealtimeMs) * 1000L) % durationUs;
            Bitmap bitmap = retriever.getFrameAtTime(elapsedUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bitmap == null) {
                reportFailure("Decoder failure");
                stopCaptureInternal();
                return;
            }
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
            if (scaled != bitmap) {
                bitmap.recycle();
            }
            byte[] nv21 = argbToNv21(scaled);
            VideoFrame.Buffer buffer = new NV21Buffer(nv21, scaled.getWidth(), scaled.getHeight(), null);
            VideoFrame frame = new VideoFrame(buffer, 0, System.nanoTime());
            capturerObserver.onFrameCaptured(frame);
            frame.release();
            scaled.recycle();
        } catch (Exception e) {
            FileLog.e(e);
            reportFailure("Unsupported or unreadable video format");
            stopCaptureInternal();
            return;
        }
        scheduleNextFrame();
    }

    private byte[] argbToNv21(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        byte[] yuv = new byte[width * height * 3 / 2];
        int yIndex = 0;
        int uvIndex = width * height;
        int index = 0;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int c = argb[index++];
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

                yuv[yIndex++] = (byte) (Math.max(0, Math.min(255, y)));
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvIndex++] = (byte) (Math.max(0, Math.min(255, v)));
                    yuv[uvIndex++] = (byte) (Math.max(0, Math.min(255, u)));
                }
            }
        }
        return yuv;
    }

    @Override
    public void stopCapture() {
        stopCaptureInternal();
    }

    private void stopCaptureInternal() {
        running = false;
        if (capturerObserver != null) {
            capturerObserver.onCapturerStopped();
        }
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        targetWidth = width;
        targetHeight = height;
        targetFps = framerate;
    }

    @Override
    public void dispose() {
        running = false;
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }
        releaseRetriever();
    }

    private void releaseRetriever() {
        if (retriever != null) {
            try {
                retriever.release();
            } catch (Exception ignore) {
            }
            retriever = null;
        }
    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    private void reportFailure(String reason) {
        if (failureCallback != null) {
            failureCallback.onFailure(reason);
        }
    }
}

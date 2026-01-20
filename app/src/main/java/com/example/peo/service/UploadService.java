package com.example.peo.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.peo.model.VideoModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;
import okio.Buffer;

public class UploadService extends Service {

    public static final String EXTRA_VIDEO_URIS = "com.example.peo.EXTRA_VIDEO_URIS";
    public static final String EXTRA_VIDEO_NAMES = "com.example.peo.EXTRA_VIDEO_NAMES";
    public static final String EXTRA_PROJECT_ID = "com.example.peo.EXTRA_PROJECT_ID";
    public static final String EXTRA_CAMERA_ID = "com.example.peo.EXTRA_CAMERA_ID";

    // Matching SettingFragment Keys
    private static final String PREF_NAME = "ProjectSettings";
    private static final String KEY_BASE_URL = "custom_base_url";

    private static final String NOTIFICATION_CHANNEL_ID = "UploadServiceChannel";
    private static final int NOTIFICATION_ID = 101;
    private static final int MAX_RETRIES = 5;

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private final Queue<VideoModel> uploadQueue = new LinkedList<>();
    private final Map<String, Integer> retryTracker = new HashMap<>();
    private final IBinder binder = new UploadBinder();
    private boolean isUploading = false;
    private String currentProjectId;
    private String currentCameraId;

    public interface UploadProgressListener {
        void onProgressUpdate(String videoName, String status);
        void onUploadFinished(String message);
    }
    private UploadProgressListener progressListener;

    public class UploadBinder extends Binder {
        public UploadService getService() { return UploadService.this; }
        public void setProgressListener(UploadProgressListener listener) { progressListener = listener; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        startForeground(NOTIFICATION_ID, buildNotification("Starting upload service...", 0));

        currentProjectId = intent.getStringExtra(EXTRA_PROJECT_ID);
        currentCameraId = intent.getStringExtra(EXTRA_CAMERA_ID);
        ArrayList<String> videoUris = intent.getStringArrayListExtra(EXTRA_VIDEO_URIS);
        ArrayList<String> videoNames = intent.getStringArrayListExtra(EXTRA_VIDEO_NAMES);

        if (videoUris != null && videoNames != null && videoUris.size() == videoNames.size()) {
            synchronized (uploadQueue) {
                for (int i = 0; i < videoUris.size(); i++) {
                    VideoModel video = new VideoModel(videoUris.get(i), videoNames.get(i), 0, "", "", "PENDING");
                    if (!uploadQueue.contains(video)) {
                        uploadQueue.add(video);
                        retryTracker.put(video.getName(), 0);
                    }
                }
            }
        }

        if (!isUploading && !uploadQueue.isEmpty()) {
            isUploading = true;
            processNextUpload();
        }

        return START_NOT_STICKY;
    }

    private synchronized void processNextUpload() {
        VideoModel videoToUpload;
        synchronized (uploadQueue) {
            if (uploadQueue.isEmpty()) {
                isUploading = false;
                if (progressListener != null) progressListener.onUploadFinished("All uploads complete.");
                stopForeground(true);
                stopSelf();
                return;
            }
            videoToUpload = uploadQueue.peek();
        }

        if (videoToUpload != null) {
            int videosLeft = uploadQueue.size() - 1;
            updateNotification("Uploading: " + videoToUpload.getName() + " (" + videosLeft + " left)", 0);
            uploadVideo(videoToUpload);
        }
    }

    private synchronized void handleUploadCompletion(VideoModel video, String finalStatus) {
        boolean shouldRemoveFromQueue = "SUCCESSFULLY UPLOAD".equals(finalStatus) ||
                "ALREADY UPLOADED".equals(finalStatus) ||
                "UPLOAD FAILED".equals(finalStatus);

        synchronized (uploadQueue) {
            if (!uploadQueue.isEmpty() && uploadQueue.peek().equals(video)) {
                if (shouldRemoveFromQueue) {
                    uploadQueue.poll();
                    retryTracker.remove(video.getName());
                }
            }
        }
        processNextUpload();
    }

    private void uploadVideo(VideoModel video) {
        // Fetch dynamic URL from SharedPreferences
        SharedPreferences sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String baseUrl = sharedPref.getString(KEY_BASE_URL, "http://apps.leyteprovince.gov.ph:70/gov_peo/index.php/");
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        String finalFileName = video.getName();
        int currentRetryCount = retryTracker.getOrDefault(finalFileName, 0);

        if (progressListener != null) {
            String initialStatus = currentRetryCount > 0 ? "RETRY #" + currentRetryCount + " (0%)" : "0%";
            progressListener.onProgressUpdate(finalFileName, "UPLOADING (" + initialStatus + ")");
        }

        File videoFile = new File(video.getPath());
        if (!videoFile.exists()) {
            handleUploadCompletion(video, "UPLOAD FAILED");
            return;
        }

        long finalFileSize = videoFile.length();
        String formattedDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(videoFile.lastModified()));

        ProgressRequestBody progressFileRequestBody = createProgressRequestBody(video, finalFileSize, "application/octet-stream");

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("camera_id", currentCameraId)
                .addFormDataPart("project_id", currentProjectId)
                .addFormDataPart("video_date", formattedDate)
                .addFormDataPart("video_size", String.valueOf(finalFileSize))
                .addFormDataPart("video", finalFileName, progressFileRequestBody)
                .build();

        Request request = new Request.Builder()
                .url(baseUrl + "Api/upload_time_lapse_video")
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                boolean isNetworkReset = e instanceof SocketException || e instanceof ConnectException ||
                        (e.getCause() != null && (e.getCause() instanceof SocketException || e.getCause() instanceof ConnectException));

                if (isNetworkReset && currentRetryCount < MAX_RETRIES) {
                    retryTracker.put(finalFileName, currentRetryCount + 1);
                    uploadVideo(video);
                    return;
                }
                if (progressListener != null) progressListener.onProgressUpdate(finalFileName, "UPLOAD FAILED");
                handleUploadCompletion(video, "UPLOAD FAILED");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String finalStatus = "UPLOAD FAILED";
                try (ResponseBody responseBody = response.body()) {
                    String responseString = (responseBody != null) ? responseBody.string() : "";
                    if (response.isSuccessful()) {
                        if (responseString.contains("\"Video file already exists\"")) finalStatus = "ALREADY UPLOADED";
                        else if (responseString.contains("\"success\"")) finalStatus = "SUCCESSFULLY UPLOAD";
                    }
                } finally {
                    if (progressListener != null) progressListener.onProgressUpdate(finalFileName, finalStatus);
                    handleUploadCompletion(video, finalStatus);
                }
            }
        });
    }

    // --- Helper Methods ---
    private void updateNotification(String title, int progress) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(title, progress));
    }

    private Notification buildNotification(String title, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Video Upload Queue")
                .setContentText(title)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (progress > 0 && progress < 100) builder.setProgress(100, progress, false);
        else if (progress == 100) builder.setProgress(0, 0, false).setContentText("Completed: " + title);
        else builder.setProgress(0, 0, true);

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "Video Uploads", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private ProgressRequestBody createProgressRequestBody(VideoModel video, long fileSize, String mimeType) {
        RequestBody rawBody = new RequestBody() {
            @Override public MediaType contentType() { return MediaType.parse(mimeType); }
            @Override public long contentLength() { return fileSize; }
            @Override public void writeTo(BufferedSink sink) throws IOException {
                try (FileInputStream in = new FileInputStream(new File(video.getPath()))) {
                    sink.writeAll(Okio.source(in));
                }
            }
        };

        return new ProgressRequestBody(rawBody, (bytesWritten, total) -> {
            int progress = (total > 0) ? (int) ((100 * bytesWritten) / total) : 0;
            if (progressListener != null) progressListener.onProgressUpdate(video.getName(), "UPLOADING (" + progress + "%)");
            if (progress % 10 == 0) updateNotification("Uploading " + video.getName(), progress);
        });
    }

    private interface ProgressListener { void onProgressUpdate(long bytesWritten, long total); }

    private class ProgressRequestBody extends RequestBody {
        private final RequestBody delegate;
        private final ProgressListener listener;
        public ProgressRequestBody(RequestBody delegate, ProgressListener listener) { this.delegate = delegate; this.listener = listener; }
        @Override public MediaType contentType() { return delegate.contentType(); }
        @Override public long contentLength() throws IOException { return delegate.contentLength(); }
        @Override public void writeTo(BufferedSink sink) throws IOException {
            BufferedSink bufferedSink = Okio.buffer(new ForwardingSink(sink) {
                private long bytesWritten = 0;
                @Override public void write(Buffer source, long byteCount) throws IOException {
                    super.write(source, byteCount);
                    bytesWritten += byteCount;
                    listener.onProgressUpdate(bytesWritten, contentLength());
                }
            });
            delegate.writeTo(bufferedSink);
            bufferedSink.flush();
        }
    }
}
package com.example.peo.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.peo.R; // Assumed to be accessible
import com.example.peo.model.VideoModel; // Assumed to be accessible

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
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
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

public class UploadService extends Service {

    // Intent Extras for passing data from Fragment
    public static final String EXTRA_VIDEO_URIS = "com.example.peo.EXTRA_VIDEO_URIS";
    public static final String EXTRA_VIDEO_NAMES = "com.example.peo.EXTRA_VIDEO_NAMES";
    public static final String EXTRA_PROJECT_ID = "com.example.peo.EXTRA_PROJECT_ID";
    public static final String EXTRA_CAMERA_ID = "com.example.peo.EXTRA_CAMERA_ID";

    private static final String NOTIFICATION_CHANNEL_ID = "UploadServiceChannel";
    private static final int NOTIFICATION_ID = 101;
    private static final String BASE_URL = "http://apps.leyteprovince.gov.ph:70/gov_peo/index.php/";
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private final Queue<VideoModel> uploadQueue = new LinkedList<>();
    private final IBinder binder = new UploadBinder();
    private boolean isUploading = false;
    private String currentProjectId;
    private String currentCameraId;

    // Listener/Callback Interface
    public interface UploadProgressListener {
        void onProgressUpdate(String videoName, String status);
        void onUploadFinished(String message);
    }
    private UploadProgressListener progressListener;

    // Binder Implementation
    public class UploadBinder extends Binder {
        public UploadService getService() {
            return UploadService.this;
        }
        public void setProgressListener(UploadProgressListener listener) {
            progressListener = listener;
            // Optionally, provide current status upon binding
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        // 1. Start Foreground Service
        startForeground(NOTIFICATION_ID, buildNotification("Starting upload service...", 0));

        // 2. Get data and populate queue (only if not already uploading)
        currentProjectId = intent.getStringExtra(EXTRA_PROJECT_ID);
        currentCameraId = intent.getStringExtra(EXTRA_CAMERA_ID);
        ArrayList<String> videoUris = intent.getStringArrayListExtra(EXTRA_VIDEO_URIS);
        ArrayList<String> videoNames = intent.getStringArrayListExtra(EXTRA_VIDEO_NAMES);

        if (videoUris != null && videoNames != null && videoUris.size() == videoNames.size()) {
            synchronized (uploadQueue) {
                for (int i = 0; i < videoUris.size(); i++) {
                    VideoModel video = new VideoModel(
                            videoUris.get(i),
                            videoNames.get(i),
                            0, "", "", "PENDING");
                    if (!uploadQueue.contains(video)) {
                        uploadQueue.add(video);
                    }
                }
            }
        }

        // 3. Start processing the queue if not already running
        if (!isUploading && !uploadQueue.isEmpty()) {
            isUploading = true;
            processNextUpload();
        } else if (uploadQueue.isEmpty() && !isUploading) {
            // If service was started with empty queue (e.g., system restart)
            if (progressListener != null) {
                progressListener.onUploadFinished("Upload queue is empty. Service stopping.");
            }
            stopForeground(true);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    /**
     * Attempts to process the video at the head of the queue.
     * Uses peek() instead of poll() to keep the video in the queue until confirmed successful.
     */
    private synchronized void processNextUpload() {
        VideoModel videoToUpload;
        synchronized (uploadQueue) {
            if (uploadQueue.isEmpty()) {
                isUploading = false;
                if (progressListener != null) {
                    progressListener.onUploadFinished("All uploads complete. Scanning...");
                }
                stopForeground(true);
                stopSelf();
                return;
            }
            // CRITICAL CHANGE: Peek instead of poll to keep video in queue if it fails
            videoToUpload = uploadQueue.peek();
        }

        if (videoToUpload != null) {
            // Calculate remaining count (excluding the one currently uploading)
            int videosLeft = uploadQueue.size() - 1;
            updateNotification("Uploading: " + videoToUpload.getName() + " (" + videosLeft + " left)", 0);
            uploadVideo(videoToUpload);
        }
    }

    /**
     * Centralized logic to manage the queue after an upload attempt.
     * Ensures video is only removed on success or immediate failure.
     * * @param video The video that was processed.
     * @param finalStatus The resulting status of the upload.
     */
    private synchronized void handleUploadCompletion(VideoModel video, String finalStatus) {
        // CRITICAL CHANGE: Remove video on UPLOAD COMPLETE, ALREADY UPLOADED, OR UPLOAD FAILED
        boolean shouldRemoveFromQueue = "SUCCESSFULLY UPLOAD".equals(finalStatus) ||
                "ALREADY UPLOADED".equals(finalStatus) ||
                "UPLOAD FAILED".equals(finalStatus);

        synchronized (uploadQueue) {
            // Only proceed if the item at the head is the one we just processed
            if (!uploadQueue.isEmpty() && uploadQueue.peek().equals(video)) {

                if (shouldRemoveFromQueue) {
                    // Success OR Failure: Remove it from the queue permanently for this service run.
                    uploadQueue.poll();
                    if ("UPLOAD FAILED".equals(finalStatus)) {
                        Log.d("UPLOAD_SERVICE", "Upload failed for " + video.getName() + ". Removed from queue. Will retry on next app start/refresh.");
                    }
                }
            } else {
                Log.e("UPLOAD_SERVICE", "Queue head mismatch or queue empty after upload attempt for " + video.getName());
            }
        }

        // Always call to process the next item
        processNextUpload();
    }


    private void uploadVideo(VideoModel video) {
        if (progressListener != null) {
            progressListener.onProgressUpdate(video.getName(), "UPLOADING (0%)");
        }

        // --- CORRECTED METADATA RETRIEVAL (Using standard File I/O) ---
        File videoFile = new File(video.getPath());

        if (!videoFile.exists()) {
            Log.e("UPLOAD_SERVICE", "Internal file not found: " + video.getPath());
            if (progressListener != null) {
                progressListener.onProgressUpdate(video.getName(), "UPLOAD FAILED");
            }
            // Use handler on failure to remove it from the queue
            handleUploadCompletion(video, "UPLOAD FAILED");
            return;
        }

        String finalFileName = video.getName();
        // A generic mime type is used since we cannot reliably check using ContentResolver now
        String finalMimeType = "application/octet-stream";
        long finalFileSize = videoFile.length();
        long lastModifiedTime = videoFile.lastModified(); // Use the local file's modification time.

        if (finalFileSize <= 0) { // Check for zero size as well
            Log.e("UPLOAD_SERVICE", "File size could not be determined or is zero for: " + finalFileName);
            if (progressListener != null) {
                progressListener.onProgressUpdate(video.getName(), "UPLOAD FAILED");
            }
            // Use handler on failure to remove it from the queue
            handleUploadCompletion(video, "UPLOAD FAILED");
            return;
        }

        SimpleDateFormat serverDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String formattedDate = serverDateFormat.format(new Date(lastModifiedTime));

        // 2. Prepare listeners and RequestBody
        ProgressRequestBody progressFileRequestBody = createProgressRequestBody(video, finalFileSize, finalMimeType);

        // 3. Build Multipart Request
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("camera_id", currentCameraId)
                .addFormDataPart("project_id", currentProjectId)
                .addFormDataPart("video_date", formattedDate)
                .addFormDataPart("video_size", String.valueOf(finalFileSize))
                .addFormDataPart("video", finalFileName, progressFileRequestBody)
                .build();

        // 4. Build Request
        Request request = new Request.Builder()
                .url(BASE_URL + "Api/upload_time_lapse_video")
                .post(requestBody)
                .build();

        // 5. Enqueue the call
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("UPLOAD_SERVICE", "Upload Failed for " + finalFileName + ": " + e.getMessage());
                if (progressListener != null) {
                    progressListener.onProgressUpdate(video.getName(), "UPLOAD FAILED");
                }
                // Use handler on failure to remove it from the queue
                handleUploadCompletion(video, "UPLOAD FAILED");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String finalStatus = "UPLOAD FAILED";
                try (ResponseBody responseBody = response.body()) {
                    String responseString = (responseBody != null) ? responseBody.string() : "";
                    Log.d("UPLOAD_SERVICE", "Response UPLOAD: " + response.code() + " - " + responseString);

                    if (response.isSuccessful()) {
                        if (responseString.contains("\"Video file already exists\"")) {
                            finalStatus = "ALREADY UPLOADED";
                        } else if (responseString.contains("\"success\"")) {
                            finalStatus = "SUCCESSFULLY UPLOAD";
                        } else {
                            finalStatus = "UPLOAD FAILED";
                        }
                    } else {
                        finalStatus = "UPLOAD FAILED";
                    }
                } finally {
                    if (progressListener != null) {
                        progressListener.onProgressUpdate(video.getName(), finalStatus);
                    }
                    response.close();
                    // Use handler to manage queue
                    handleUploadCompletion(video, finalStatus);
                }
            }
        });
    }

    // --- Service Communication and Notification Methods ---

    private void updateNotification(String title, int progress) {
        Notification notification = buildNotification(title, progress);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String title, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Video Upload Queue")
                .setContentText(title)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (progress > 0 && progress < 100) {
            builder.setProgress(100, progress, false);
        } else if (progress == 100) {
            builder.setProgress(0, 0, false)
                    .setContentText("Completed: " + title);
        } else { // 0 or starting
            builder.setProgress(0, 0, true); // Indeterminate progress
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Video Uploads",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    // --- Progress Tracking Classes ---
    private ProgressRequestBody createProgressRequestBody(VideoModel video, long finalFileSize, String finalMimeType) {
        // 1. Raw File Body
        RequestBody rawFileRequestBody = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse(finalMimeType);
            }
            @Override
            public long contentLength() {
                return finalFileSize;
            }
            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                // --- CORRECTED FILE READING LOGIC ---
                File videoFile = new File(video.getPath());
                try (FileInputStream inputStream = new FileInputStream(videoFile)) {
                    if (inputStream == null) throw new IOException("Cannot open FileInputStream for internal file.");
                    sink.writeAll(Okio.source(inputStream));
                } catch (Exception e) {
                    throw new IOException("Failed to write file stream for " + video.getName() + ".", e);
                }
                // --- END CORRECTED FILE READING LOGIC ---
            }
        };

        // 2. Progress Listener
        ProgressListener progressListener = (bytesWritten, contentLength) -> {
            int progress = (contentLength > 0) ? (int) ((100 * bytesWritten) / contentLength) : 0;

            // Call the registered Fragment listener directly
            if (this.progressListener != null) {
                String newStatus = "UPLOADING (" + progress + "%)";
                this.progressListener.onProgressUpdate(video.getName(), newStatus);
            }

            // Update notification every 10%
            if (progress % 10 == 0 || progress == 1 || progress == 99) {
                // The uploadQueue.size() includes the video currently uploading
                updateNotification("Uploading " + video.getName() + " (" + (uploadQueue.size() - 1) + " left)", progress);
            }
        };

        return new ProgressRequestBody(rawFileRequestBody, progressListener);
    }

    private interface ProgressListener {
        void onProgressUpdate(long bytesWritten, long contentLength);
    }

    private class ProgressRequestBody extends RequestBody {
        private final RequestBody delegate;
        private final ProgressListener listener;

        public ProgressRequestBody(RequestBody delegate, ProgressListener listener) {
            this.delegate = delegate;
            this.listener = listener;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            CountingSink countingSink = new CountingSink(sink);
            BufferedSink bufferedSink = Okio.buffer(countingSink);
            delegate.writeTo(bufferedSink);
            bufferedSink.flush();
        }

        private final class CountingSink extends ForwardingSink {
            private long bytesWritten = 0;

            public CountingSink(Sink delegate) {
                super(delegate);
            }

            @Override
            public void write(Buffer source, long byteCount) throws IOException {
                super.write(source, byteCount);
                bytesWritten += byteCount;
                long contentLength = contentLength();
                if (contentLength > 0) {
                    listener.onProgressUpdate(bytesWritten, contentLength);
                }
            }
        }
    }
}
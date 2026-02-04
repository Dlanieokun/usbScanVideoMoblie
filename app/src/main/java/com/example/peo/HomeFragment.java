package com.example.peo;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.content.SharedPreferences;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.core.content.ContextCompat;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.example.peo.service.UploadService;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.example.peo.adapter.VideoAdapter;
import com.example.peo.model.VideoModel;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;

public class HomeFragment extends Fragment implements
        UploadService.UploadProgressListener,
        ServiceConnection
{
    private static final OkHttpClient client = new OkHttpClient.Builder().build();
    private String currentBaseUrl;

    private static final String PREFS_NAME = "usb_prefs";
    private static final String KEY_PERSISTED_URIS = "persisted_uris";
    private static final String APP_PREFS_FILE = "app_local_data";
    private static final String PROJECT_NAME_KEY = "name_of_project";
    private static final String PREFS_NAME_SETTINGS = "ProjectSettings";
    private static final String KEY_PROJECT_ID = "selected_project_id";
    private static final String KEY_CAMERA_1_ID = "camera3ID";
    private static final String KEY_BASE_URL = "custom_base_url";
    private static final String CON_CAMERA = "camera 3";
    private static final String PREFS_QUEUE_STATUS = "video_queue_status";
    private static final String KEY_STATUS_PREFIX = "status_";

    private TextView tvStatus, tvProjectName, tvSelectedFolder, tvError, tvQueueCount, btnSeeMore;
    private Button btnRequestUsb;
    private ImageButton btnResetQueue;
    private RecyclerView rvVideos;
    private SwipeRefreshLayout swipeRefreshLayout;
    private VideoAdapter videoAdapter;
    private final List<VideoModel> videoList = new ArrayList<>();

    private Handler observerHandler = new Handler(Looper.getMainLooper());
    private ContentObserver fileChangeObserver;
    private Uri currentUsbUri = null;

    private UploadService uploadService;
    private boolean isBound = false;

    private ActivityResultLauncher<Intent> openUsbLauncher;
    private ActivityResultLauncher<String> requestNotificationPermissionLauncher;

    String camera1Id;
    String projectId;
    JSONArray jsonArrayCheck = new JSONArray();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (!isGranted) Toast.makeText(requireContext(), "Upload notifications blocked.", Toast.LENGTH_SHORT).show();
                    scanAllPersistedUris();
                }
        );

        fileChangeObserver = new ContentObserver(observerHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                if (!selfChange && currentUsbUri != null) scanAllPersistedUris();
            }
        };

        openUsbLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == getActivity().RESULT_OK && result.getData() != null) {
                        Uri treeUri = result.getData().getData();
                        if (treeUri != null) {
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            savePersistedUri(treeUri);
                            scanAllPersistedUris();
                        }
                    } else { updateUiStatus("Storage access denied.", true); }
                }
        );
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        SharedPreferences settingsPrefs = requireContext().getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE);
        currentBaseUrl = settingsPrefs.getString(KEY_BASE_URL, "http://apps.leyteprovince.gov.ph:70/gov_peo/index.php/");

        SharedPreferences sharedPrefs = requireContext().getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE);
        if (!sharedPrefs.contains(PROJECT_NAME_KEY)) {
            navigateToSettingFragment();
            return inflater.inflate(R.layout.fragment_home, container, false);
        }

        View view = inflater.inflate(R.layout.fragment_home, container, false);
        tvStatus = view.findViewById(R.id.tvStatus);
        tvProjectName = view.findViewById(R.id.tvProjectName);
        btnSeeMore = view.findViewById(R.id.btnSeeMore);
        tvSelectedFolder = view.findViewById(R.id.tvSelectedFolder);
        tvError = view.findViewById(R.id.tvError);
        rvVideos = view.findViewById(R.id.rvVideos);
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        tvQueueCount = view.findViewById(R.id.tvQueueCount);
        btnResetQueue = view.findViewById(R.id.btnResetQueue);

        rvVideos.setLayoutManager(new LinearLayoutManager(requireContext()));
        videoAdapter = new VideoAdapter(requireContext(), videoList);
        rvVideos.setAdapter(videoAdapter);

        btnRequestUsb = view.findViewById(R.id.btnRequestUsb);
        btnRequestUsb.setOnClickListener(v -> requestStorageAccess());

        btnResetQueue.setOnClickListener(v -> confirmResetQueue());

        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (isAdded()) {
                currentBaseUrl = requireContext().getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE).getString(KEY_BASE_URL, currentBaseUrl);
                scanAllPersistedUris();
            }
        });

        checkExistingUris();
        updateInternalQueueCount();
        updateProjectAndFolderInfo();
        bindUploadService();

        return view;
    }

    private void updateProjectUIOnly() {
        if (!isAdded()) return;
        SharedPreferences appSharedPrefs = requireContext().getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE);
        String projectName = appSharedPrefs.getString(PROJECT_NAME_KEY, "N/A");
        String fullText = "Project: " + projectName + " (ID: " + projectId + ")";

        safeRunOnUiThread(() -> {
            if (tvProjectName != null) {
                tvProjectName.setText(fullText);

                // IMPORTANT: Wait for layout to measure line count
                tvProjectName.post(() -> {
                    if (tvProjectName.getLineCount() > 2) {
                        btnSeeMore.setVisibility(View.VISIBLE);
                        btnSeeMore.setOnClickListener(v -> {
                            if (tvProjectName.getMaxLines() == 2) {
                                tvProjectName.setMaxLines(Integer.MAX_VALUE);
                                btnSeeMore.setText("See less");
                            } else {
                                tvProjectName.setMaxLines(2);
                                btnSeeMore.setText("See more");
                            }
                        });
                    } else {
                        btnSeeMore.setVisibility(View.GONE);
                    }
                });
            }
            if (tvSelectedFolder != null) tvSelectedFolder.setText("\n" + CON_CAMERA + " Folder ID: " + camera1Id);
        });
    }

    private void confirmResetQueue() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Reset Internal Queue")
                .setMessage("Delete all locally cached videos awaiting upload?")
                .setPositiveButton("Reset", (dialog, which) -> resetInternalQueue())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void resetInternalQueue() {
        new Thread(() -> {
            File internalDir = new File(requireContext().getFilesDir(), "upload_cache");
            if (internalDir.exists() && internalDir.isDirectory()) {
                File[] files = internalDir.listFiles();
                if (files != null) for (File f : files) f.delete();
            }
            requireContext().getSharedPreferences(PREFS_QUEUE_STATUS, Context.MODE_PRIVATE).edit().clear().apply();
            safeRunOnUiThread(() -> {
                Toast.makeText(requireContext(), "Queue reset.", Toast.LENGTH_SHORT).show();
                scanAllPersistedUris();
            });
        }).start();
    }

    private void bindUploadService() {
        Intent serviceIntent = new Intent(requireContext(), UploadService.class);
        requireContext().bindService(serviceIntent, this, Context.BIND_AUTO_CREATE);
    }

    @Override public void onServiceConnected(ComponentName name, IBinder service) {
        UploadService.UploadBinder binder = (UploadService.UploadBinder) service;
        uploadService = binder.getService();
        binder.setProgressListener(this);
        isBound = true;
    }

    @Override public void onServiceDisconnected(ComponentName name) { uploadService = null; isBound = false; }

    @Override
    public void onProgressUpdate(String videoName, String newStatus) {
        saveVideoStatus(videoName, newStatus);
        if ("SUCCESSFULLY UPLOAD".equals(newStatus) || "ALREADY UPLOADED".equals(newStatus)) deleteInternalFile(videoName);
        safeRunOnUiThread(() -> {
            for (VideoModel video : videoList) {
                if (video.getName().equals(videoName)) {
                    video.setStatus_upload(newStatus);
                    int pos = videoList.indexOf(video);
                    if (pos != -1) videoAdapter.notifyItemChanged(pos);
                    break;
                }
            }
            updateInternalQueueCount();
        });
    }

    @Override public void onUploadFinished(String msg) { safeRunOnUiThread(() -> { updateUiStatus(msg, btnRequestUsb.getVisibility() == View.VISIBLE); updateInternalQueueCount(); }); }

    private void updateProjectAndFolderInfo() {
        if (!isAdded()) return;
        SharedPreferences settingsPrefs = requireContext().getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE);
        projectId = settingsPrefs.getString(KEY_PROJECT_ID, "N/A");
        camera1Id = settingsPrefs.getString(KEY_CAMERA_1_ID, "Not Set");
        currentBaseUrl = settingsPrefs.getString(KEY_BASE_URL, currentBaseUrl);
        updateProjectUIOnly();
        if (!"N/A".equals(projectId)) loadVideoUploaded("/Api/getUploadedVideo", projectId);
        else if (!getPersistedUris().isEmpty()) scanAllPersistedUris();
    }

    private void navigateToSettingFragment() { if (isAdded() && getActivity() instanceof MainActivity) ((MainActivity) requireActivity()).loadFragment(new SettingFragment()); }

    private void savePersistedUri(Uri uri) {
        Set<String> uriSet = getPersistedUris();
        uriSet.add(uri.toString());
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putStringSet(KEY_PERSISTED_URIS, uriSet).apply();
    }

    private Set<String> getPersistedUris() { return new HashSet<>(requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(KEY_PERSISTED_URIS, new HashSet<>())); }

    private void saveVideoStatus(String name, String status) { if (isAdded()) requireContext().getSharedPreferences(PREFS_QUEUE_STATUS, Context.MODE_PRIVATE).edit().putString(KEY_STATUS_PREFIX + name, status).apply(); }

    private String loadVideoStatus(String name) { if (!isAdded()) return "PENDING"; return requireContext().getSharedPreferences(PREFS_QUEUE_STATUS, Context.MODE_PRIVATE).getString(KEY_STATUS_PREFIX + name, "PENDING"); }

    private void deleteInternalFile(String name) {
        if (!isAdded()) return;
        File internalDir = new File(requireContext().getFilesDir(), "upload_cache");
        File file = new File(internalDir, name);
        if (file.exists() && file.delete()) {
            requireContext().getSharedPreferences(PREFS_QUEUE_STATUS, Context.MODE_PRIVATE).edit().remove(KEY_STATUS_PREFIX + name).apply();
            updateInternalQueueCount();
        }
    }

    private void checkExistingUris() { if (!getPersistedUris().isEmpty()) updateUiStatus("Awaiting server history...", false); else updateUiStatus("Select a folder to begin scanning.", true); }

    private void requestStorageAccess() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        openUsbLauncher.launch(intent);
    }

    private void scanAllPersistedUris() {
        if (!isAdded()) return;
        updateUiStatus("Scanning storage devices...", false);
        safeRunOnUiThread(() -> swipeRefreshLayout.setRefreshing(true));
        List<VideoModel> externalVideos = new ArrayList<>();
        new Thread(() -> {
            Set<String> uriSet = getPersistedUris();
            Uri firstValidUri = null;
            for (String uriString : uriSet) {
                if (!isAdded()) return;
                Uri rootUri = Uri.parse(uriString);
                try {
                    DocumentFile root = DocumentFile.fromTreeUri(requireContext(), rootUri);
                    if (root != null && root.isDirectory() && root.canRead()) {
                        scanFolder(root, externalVideos);
                        if (firstValidUri == null) firstValidUri = rootUri;
                    }
                } catch (SecurityException e) { Log.e("SAF_SCAN", "Permission issue", e); }
            }
            List<VideoModel> internalQueuedVideos = findInternalQueuedVideos();
            Map<String, VideoModel> uniqueVideosMap = new HashMap<>();
            for (VideoModel v : externalVideos) uniqueVideosMap.put(v.getName(), v);
            for (VideoModel v : internalQueuedVideos) if (!uniqueVideosMap.containsKey(v.getName())) uniqueVideosMap.put(v.getName(), v);
            List<VideoModel> combined = new ArrayList<>(uniqueVideosMap.values());
            updateContentObserver(firstValidUri);
            combined.sort((v1, v2) -> Long.compare(v1.getLastModified(), v2.getLastModified()));
            List<VideoModel> toUpload = new ArrayList<>();
            for (VideoModel v : combined) if (List.of("PENDING", "UPLOAD FAILED", "UPLOADING").contains(v.getStatus_upload())) toUpload.add(v);
            safeRunOnUiThread(() -> {
                videoList.clear(); videoList.addAll(combined);
                videoAdapter.notifyDataSetChanged();
                swipeRefreshLayout.setRefreshing(false);
                updateInternalQueueCount();
                updateUiStatus("Scan completed.", uriSet.isEmpty() || combined.isEmpty());
                updateProjectUIOnly();
                if (!toUpload.isEmpty()) startUploadService(toUpload);
            });
        }).start();
    }

    private void doStartUploadService(List<VideoModel> toUpload) {
        if (!isAdded() || toUpload.isEmpty()) return;
        ArrayList<String> uris = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (VideoModel v : toUpload) {
            uris.add(v.getPath()); names.add(v.getName());
            v.setStatus_upload("UPLOADING"); saveVideoStatus(v.getName(), "UPLOADING");
        }
        videoAdapter.notifyDataSetChanged();
        Intent serviceIntent = new Intent(requireContext(), UploadService.class);
        serviceIntent.putStringArrayListExtra(UploadService.EXTRA_VIDEO_URIS, uris);
        serviceIntent.putStringArrayListExtra(UploadService.EXTRA_VIDEO_NAMES, names);
        serviceIntent.putExtra(UploadService.EXTRA_PROJECT_ID, projectId);
        serviceIntent.putExtra(UploadService.EXTRA_CAMERA_ID, camera1Id);
        serviceIntent.putExtra("EXTRA_BASE_URL", currentBaseUrl);
        requireActivity().startService(serviceIntent);
        if (!isBound) bindUploadService();
    }

    private void startUploadService(List<VideoModel> toUpload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        doStartUploadService(toUpload);
    }

    private String copyFileToInternalStorage(DocumentFile file) {
        if (!isAdded() || file.getName() == null) return null;
        try {
            File internalDir = new File(requireContext().getFilesDir(), "upload_cache");
            if (!internalDir.exists()) internalDir.mkdirs();
            File destFile = new File(internalDir, file.getName());
            if (destFile.exists()) return destFile.getAbsolutePath();
            try (ParcelFileDescriptor pfd = requireContext().getContentResolver().openFileDescriptor(file.getUri(), "r");
                 FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                return destFile.getAbsolutePath();
            }
        } catch (IOException e) { return null; }
    }

    private void scanFolder(DocumentFile folder, List<VideoModel> externalVideos) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
        for (DocumentFile file : folder.listFiles()) {
            if (!isAdded()) return;
            if (file.isDirectory()) scanFolder(file, externalVideos);
            else {
                String name = file.getName() == null ? "" : file.getName().toLowerCase();
                if (List.of(".mp4", ".mkv", ".avi").stream().anyMatch(name::endsWith)) {
                    String localPath = copyFileToInternalStorage(file);
                    if (localPath == null) continue;
                    String status = loadVideoStatus(file.getName());
                    long size = file.length();
                    try {
                        for (int i = 0; i < jsonArrayCheck.length(); i++) {
                            JSONObject cv = jsonArrayCheck.getJSONObject(i);
                            if (cv.getString("original_filename").equals(file.getName()) && cv.optLong("file_size") == size) {
                                status = "ALREADY UPLOADED"; saveVideoStatus(file.getName(), "ALREADY UPLOADED");
                                deleteInternalFile(file.getName()); break;
                            }
                        }
                    } catch (JSONException e) { Log.e("JSON", "Parse error", e); }
                    externalVideos.add(new VideoModel(localPath, file.getName(), file.lastModified(), sdf.format(new Date(file.lastModified())), CON_CAMERA, status));
                }
            }
        }
    }

    private void updateContentObserver(Uri rootUri) {
        safeRunOnUiThread(() -> {
            if (rootUri != null && !rootUri.equals(currentUsbUri)) {
                if (currentUsbUri != null) requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
                currentUsbUri = rootUri;
                requireContext().getContentResolver().registerContentObserver(rootUri, true, fileChangeObserver);
            }
        });
    }

    private void updateUiStatus(String msg, boolean showButton) { safeRunOnUiThread(() -> { if (tvStatus != null) tvStatus.setText(msg); if (btnRequestUsb != null) btnRequestUsb.setVisibility(showButton ? View.VISIBLE : View.GONE); }); }

    private String formatFileSize(long size) {
        if (size <= 0) return "0B";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new java.text.DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private List<VideoModel> findInternalQueuedVideos() {
        List<VideoModel> queue = new ArrayList<>();
        File internalDir = new File(requireContext().getFilesDir(), "upload_cache");
        File[] files = internalDir.listFiles();
        if (files == null) return queue;
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
        for (File f : files) {
            String status = loadVideoStatus(f.getName());
            if (List.of("PENDING", "UPLOAD FAILED", "UPLOADING").contains(status)) queue.add(new VideoModel(f.getAbsolutePath(), f.getName(), f.lastModified(), sdf.format(new Date(f.lastModified())), CON_CAMERA, status));
        }
        return queue;
    }

    private void updateInternalQueueCount() {
        if (!isAdded()) return;
        new Thread(() -> {
            File internalDir = new File(requireContext().getFilesDir(), "upload_cache");
            File[] files = internalDir.listFiles();
            int count = 0; long size = 0;
            if (files != null) {
                for (File f : files) {
                    String status = loadVideoStatus(f.getName());
                    if (!"ALREADY UPLOADED".equals(status) && !"SUCCESSFULLY UPLOAD".equals(status)) { count++; size += f.length(); }
                }
            }
            final int fCount = count; final String fSize = formatFileSize(size);
            safeRunOnUiThread(() -> { if (tvQueueCount != null) tvQueueCount.setText("Internal Queue: " + fCount + " videos (" + fSize + ")"); });
        }).start();
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (isAdded()) {
            client.dispatcher().cancelAll();
            if (currentUsbUri != null) requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
            if (isBound) { requireContext().unbindService(this); isBound = false; }
        }
    }

    private void loadVideoUploaded(String url, String vId){
        if (!isAdded()) return;
        String finalUrl = Uri.parse(currentBaseUrl + (url.startsWith("/") ? url.substring(1) : url)).toString();
        RequestBody body = new FormBody.Builder().add("project_id", vId).build();
        Request req = new Request.Builder().url(finalUrl).post(body).build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { safeRunOnUiThread(() -> { if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false); scanAllPersistedUris(); }); }
            @Override public void onResponse(Call call, Response res) throws IOException {
                try (ResponseBody b = res.body()) { if (res.isSuccessful() && b != null) { jsonArrayCheck = new JSONArray(b.string()); scanAllPersistedUris(); } }
                catch (Exception e) { scanAllPersistedUris(); }
            }
        });
    }

    private void safeRunOnUiThread(Runnable action) { if (isAdded()) requireActivity().runOnUiThread(action); }
}
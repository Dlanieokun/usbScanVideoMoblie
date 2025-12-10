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
import android.provider.DocumentsContract;
import android.content.SharedPreferences;
import android.database.Cursor;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class HomeFragment extends Fragment implements
        UploadService.UploadProgressListener,
        ServiceConnection
{

    private static final OkHttpClient client = new OkHttpClient.Builder().build();

    private static final String REQUEST_TAG = "VIDEO_UPLOAD_REQUEST";
    final String base_url = "http://apps.leyteprovince.gov.ph:70/gov_peo/index.php/";
    private static final String PREFS_NAME = "usb_prefs";
    private static final String KEY_PERSISTED_URIS = "persisted_uris";
    private static final String APP_PREFS_FILE = "app_local_data";
    private static final String PROJECT_NAME_KEY = "name_of_project";
    private static final String PREFS_NAME_SETTINGS = "ProjectSettings";
    private static final String KEY_PROJECT_ID = "selected_project_id";
    private static final String KEY_CAMERA_1_ID = "camera1ID";
    private static final String CON_CAMERA = "camera 1";

    private TextView tvStatus;
    private TextView tvProjectName;
    private TextView tvSelectedFolder;
    private TextView tvError;
    private Button btnRequestUsb;
    private RecyclerView rvVideos;
    private SwipeRefreshLayout swipeRefreshLayout;
    private VideoAdapter videoAdapter;
    private final List<VideoModel> videoList = new ArrayList<>();

    // REMOVED: private UsbStorageReceiver usbStorageReceiver;

    private Handler observerHandler = new Handler(Looper.getMainLooper());
    private ContentObserver fileChangeObserver;
    private Uri currentUsbUri = null;

    // Service Communication (Bound Service)
    private UploadService uploadService; // Reference to the service
    private boolean isBound = false;      // Service binding state

    /** SAF Launcher */
    private ActivityResultLauncher<Intent> openUsbLauncher;

    // Notification Permission Launcher
    private ActivityResultLauncher<String> requestNotificationPermissionLauncher;

    String camera1Id;
    String projectId;

    JSONArray jsonArrayCheck = new JSONArray();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // updateProjectAndFolderInfo() is called later in onCreateView/start.

        // Notification Permission Launcher Initialization
        requestNotificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (!isGranted) {
                        Toast.makeText(requireContext(), "Upload notifications blocked. Proceeding with service start.", Toast.LENGTH_SHORT).show();
                    }
                    // Re-run the scan. It will re-evaluate the queue and proceed to doStartUploadService.
                    scanAllPersistedUris();
                }
        );

        fileChangeObserver = new ContentObserver(observerHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                if (!selfChange && currentUsbUri != null) {
                    // This observer handles file changes within the selected directory
                    scanAllPersistedUris();
                }
            }
            @Override
            public boolean deliverSelfNotifications() {
                return true;
            }
        };

        openUsbLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == getActivity().RESULT_OK && result.getData() != null) {

                        Uri treeUri = result.getData().getData();
                        if (treeUri != null) {
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    treeUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                            savePersistedUri(treeUri);
                            scanAllPersistedUris();
                        }
                    } else {
                        updateUiStatus("Storage access denied. Click button to try again.", true);
                    }
                }
        );
    }



    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        SharedPreferences sharedPrefs = requireContext().getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE);

        if (!sharedPrefs.contains(PROJECT_NAME_KEY)) {
            navigateToSettingFragment();
            return inflater.inflate(R.layout.fragment_home, container, false);
        }

        View view = inflater.inflate(R.layout.fragment_home, container, false);

        tvStatus = view.findViewById(R.id.tvStatus);
        tvProjectName = view.findViewById(R.id.tvProjectName);
        tvSelectedFolder = view.findViewById(R.id.tvSelectedFolder);
        tvError = view.findViewById(R.id.tvError);
        rvVideos = view.findViewById(R.id.rvVideos);
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);

        rvVideos.setLayoutManager(new LinearLayoutManager(requireContext()));
        videoAdapter = new VideoAdapter(requireContext(), videoList);
        rvVideos.setAdapter(videoAdapter);

        btnRequestUsb = view.findViewById(R.id.btnRequestUsb);
        btnRequestUsb.setOnClickListener(v -> requestStorageAccess());


        // Pull-to-Refresh listener
        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (isAdded()) {
                scanAllPersistedUris();
            }
        });

        // Check for URIs and set initial status, but DON'T trigger a scan yet.
        checkExistingUris();

        // This initiates the ONE-TIME startup logic: load prefs, update UI, and either
        // fetch server history OR start scan.
        updateProjectAndFolderInfo();

        // Also bind on startup to establish connection if service is already running
        bindUploadService();

        return view;
    }

    /** Binds to the UploadService. Used when the Fragment starts. */
    private void bindUploadService() {
        Intent serviceIntent = new Intent(requireContext(), UploadService.class);
        // Note: Context.BIND_AUTO_CREATE ensures the service is started if it's not running,
        // but we still call startService() explicitly in doStartUploadService() for foreground state.
        requireContext().bindService(serviceIntent, this, Context.BIND_AUTO_CREATE);
    }

    /** ServiceConnection Implementation **/

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        UploadService.UploadBinder binder = (UploadService.UploadBinder) service;
        uploadService = binder.getService();
        binder.setProgressListener(this); // Register this fragment as the listener
        isBound = true;
        Log.d("SERVICE_BINDING", "UploadService connected and listener registered.");
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        uploadService = null;
        isBound = false;
        Log.d("SERVICE_BINDING", "UploadService disconnected.");
    }

    /** UploadService.UploadProgressListener Implementation **/

    // Updates the progress of a single video
    @Override
    public void onProgressUpdate(String videoName, String newStatus) {
        safeRunOnUiThread(() -> {
            for (VideoModel video : videoList) {
                if (video.getName().equals(videoName)) {
                    video.setStatus_upload(newStatus);
                    int position = videoList.indexOf(video);
                    if (position != -1) {
                        videoAdapter.notifyItemChanged(position);
                    }
                    break;
                }
            }
        });
    }

    // Called when the entire queue is finished
    @Override
    public void onUploadFinished(String message) {
        safeRunOnUiThread(() -> {
            // FIX: Automatic full list scan/reload removed.
            updateUiStatus(message, btnRequestUsb.getVisibility() == View.VISIBLE);
        });
    }

    /** Loads project ID/Camera ID and initiates the ONE-TIME startup scan logic. */
    private void updateProjectAndFolderInfo() {
        if (!isAdded()) return;

        SharedPreferences settingsPrefs = requireContext().getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE);
        projectId = settingsPrefs.getString(KEY_PROJECT_ID, "N/A");
        camera1Id = settingsPrefs.getString(KEY_CAMERA_1_ID, "Not Set");

        // UI status is updated first
        updateProjectUIOnly();

        Log.i("test", "Checking Project ID: " + projectId);

        // --- STARTUP SCAN LOGIC CONSOLIDATION (Only runs once on startup) ---
        if (!"N/A".equals(projectId)) {
            // Case 1: Project ID is valid. Load server history, which triggers scan in onResponse/onFailure.
            loadVideoUploaded("/Api/getUploadedVideo", projectId);
        } else if (!getPersistedUris().isEmpty()) {
            // Case 2: Project ID is NOT valid, but a folder IS selected. We must scan anyway.
            scanAllPersistedUris();
        }
        // Case 3: Project ID invalid AND no folder selected. UI status already set by checkExistingUris.
        // --- END STARTUP SCAN LOGIC CONSOLIDATION ---
    }

    /** NEW: Updates Project Name and Camera ID display only, without triggering network/scan logic. */
    private void updateProjectUIOnly() {
        if (!isAdded()) return;

        SharedPreferences appSharedPrefs = requireContext().getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE);
        String projectName = appSharedPrefs.getString(PROJECT_NAME_KEY, "N/A");

        SharedPreferences settingsPrefs = requireContext().getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE);
        // Ensure projectId and camera1Id are loaded for potential use by other methods
        projectId = settingsPrefs.getString(KEY_PROJECT_ID, "N/A");
        camera1Id = settingsPrefs.getString(KEY_CAMERA_1_ID, "Not Set");

        safeRunOnUiThread(() -> {
            if (tvProjectName != null) {
                tvProjectName.setText("Project: " + projectName + " (ID: " + projectId + ")");
            }

            if (tvSelectedFolder != null) {
                tvSelectedFolder.setText("\n"+CON_CAMERA+" Folder ID: " + camera1Id);
            }
        });
    }

    /**
     * Navigates to the SettingFragment and updates the bottom navigation selection.
     */
    private void navigateToSettingFragment() {
        if (isAdded() && getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) requireActivity();
            activity.loadFragment(new SettingFragment());
        }
    }

    /** Shows a dialog prompting the user to grant storage access via SAF. */
    private void showStorageAccessDialog() {
        if (isAdded()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Storage Access Required")
                    // Updated message to reflect lack of auto-detection
                    .setMessage("A storage device (USB/local) needs permission. Tap 'Grant Access', then select the ROOT directory. Note: Automatic detection of USB insertion/removal is disabled; please use the refresh/scan button.")
                    .setPositiveButton("Grant Access", (dialog, which) -> {
                        requestStorageAccess();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        updateUiStatus("Storage scan needed. Click Request Storage Access or Refresh.", true);
                        dialog.dismiss();
                    })
                    .show();
        }
    }

    /** SAVE URI: Adds a new URI to the set of persisted URIs. */
    private void savePersistedUri(Uri uri) {
        Set<String> uriSet = getPersistedUris();
        uriSet.add(uri.toString());

        requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_PERSISTED_URIS, uriSet)
                .apply();
    }

    /** GET URIS: Retrieves the set of all saved persisted URIs. */
    private Set<String> getPersistedUris() {
        Set<String> savedSet = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_PERSISTED_URIS, new HashSet<>());
        return new HashSet<>(savedSet);
    }

    /** CHECK URIS: Checks if URIs exist and updates UI, but defers the scan. */
    private void checkExistingUris() {
        if (!getPersistedUris().isEmpty()) {
            // If URIs exist, update UI status but DO NOT call scanAllPersistedUris() yet.
            updateUiStatus("Project settings loaded. Awaiting server history to start scan...", false);
        } else {
            // If no URIs, inform user to grant access
            updateUiStatus("Select a folder to begin scanning or click 'Request USB Access'.", true);
        }
    }

    /** * SAF REQUEST: Launches the system document tree picker.
     * Uses the last known URI as a hint.
     */
    private void requestStorageAccess() {
        Uri initialUri = null;
        for (String uriString : getPersistedUris()) {
            initialUri = Uri.parse(uriString);
            break;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        if (initialUri != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }

        openUsbLauncher.launch(intent);
    }

    /** Master scan function: Iterates over all saved URIs and calls scanFolder for each. */
    private void scanAllPersistedUris() {
        if (!isAdded()) return;

        updateUiStatus("Scanning storage devices...", false);

        // Ensure refresh layout is running when the background thread starts
        safeRunOnUiThread(() -> {
            swipeRefreshLayout.setRefreshing(true);
        });

        List<VideoModel> tempVideoList = new ArrayList<>();

        new Thread(() -> {

            Set<String> uriSet = getPersistedUris();

            Uri firstValidUri = null;

            for (String uriString : uriSet) {
                if (!isAdded()) return;

                Uri rootUri = Uri.parse(uriString);

                try {
                    DocumentFile root = DocumentFile.fromTreeUri(requireContext(), rootUri);

                    if (root != null && root.isDirectory() && root.canRead()) {
                        scanFolder(root, tempVideoList);
                        if (firstValidUri == null) {
                            firstValidUri = rootUri;
                        }
                    } else {
                        Log.w("SAF_SCAN", "Skipping URI: " + uriString + ". Root is null, not a directory, or cannot be read.");
                    }
                } catch (SecurityException e) {
                    Log.e("SAF_SCAN", "Security Exception reading URI: " + uriString + ". Needs re-permission.", e);
                    // Add logic here to prompt user for re-permission or remove the URI
                }
            }

            // Must call on the main thread
            updateContentObserver(firstValidUri);

            final int foundCount = tempVideoList.size();
            // Show button only if no URIs are persisted OR if no videos were found
            final boolean showButton = uriSet.isEmpty() || foundCount == 0;

            tempVideoList.sort((v1, v2) -> Long.compare(v1.getLastModified(), v2.getLastModified()));

            // --- Find videos that need uploading ---
            List<VideoModel> videosToUpload = new ArrayList<>();
            for (VideoModel video : tempVideoList) {
                // Only consider PENDING, UPLOAD FAILED, or UPLOADING (if service crashed)
                if ("PENDING".equals(video.getStatus_upload()) ||
                        "UPLOAD FAILED".equals(video.getStatus_upload()) ||
                        "UPLOADING".equals(video.getStatus_upload())) {

                    videosToUpload.add(video);
                }
            }
            // --------------------------------------

            safeRunOnUiThread(() -> {
                // 1. UPDATE AND DISPLAY THE LIST
                videoList.clear();
                videoList.addAll(tempVideoList);
                videoAdapter.notifyDataSetChanged();
                swipeRefreshLayout.setRefreshing(false);

                // Show the button if no videos were found or no folders selected
                updateUiStatus("Videos found: " + foundCount, showButton);

                // FIX: Use the UI-only update method to prevent the infinite refresh loop
                updateProjectUIOnly();

                // 2. START THE UPLOAD SERVICE IF VIDEOS ARE FOUND
                if (!videosToUpload.isEmpty()) {
                    startUploadService(videosToUpload);
                }
            });
        }).start();
    }

    /** Contains the actual service start logic. Called only after permission check. */
    private void doStartUploadService(List<VideoModel> videosToUpload) {
        if (!isAdded() || videosToUpload.isEmpty()) return;

        // Serialize the list of video URIs/names to pass to the Service
        ArrayList<String> videoUris = new ArrayList<>();
        ArrayList<String> videoNames = new ArrayList<>();

        for (VideoModel video : videosToUpload) {
            videoUris.add(video.getPath());
            videoNames.add(video.getName());
            // Immediately set the status to PENDING in the UI list before starting the service
            // NOTE: Changing this to "UPLOADING" is usually better if the service is about to start immediately
            video.setStatus_upload("PENDING");
        }
        videoAdapter.notifyDataSetChanged();
        updateUiStatus("Starting background upload service. Processing " + videosToUpload.size() + " videos.", false);


        Intent serviceIntent = new Intent(requireContext(), UploadService.class);
        serviceIntent.putStringArrayListExtra(UploadService.EXTRA_VIDEO_URIS, videoUris);
        serviceIntent.putStringArrayListExtra(UploadService.EXTRA_VIDEO_NAMES, videoNames);
        serviceIntent.putExtra(UploadService.EXTRA_PROJECT_ID, projectId);
        serviceIntent.putExtra(UploadService.EXTRA_CAMERA_ID, camera1Id);

        // Start the service in the foreground (Ensures it keeps running)
        requireActivity().startService(serviceIntent);

        // Bind to the service (Ensures communication is established/re-established)
        if (!isBound) {
            bindUploadService();
        }
    }

    /** Starts the Foreground Service to handle sequential uploads, checking notification permission first. */
    private void startUploadService(List<VideoModel> videosToUpload) {
        if (!isAdded() || videosToUpload.isEmpty()) return;

        // --- Permission Check for Android 13 (API 33) and higher ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {

                // Request permission and exit. The launcher callback will re-run the scan
                // after the user makes a decision, which will call this function again.
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }

        // Permission is not required (API < 33) or is already granted. Proceed to start and bind.
        doStartUploadService(videosToUpload);
    }

    /** Scans a single folder recursively. Videos are added to the shared tempVideoList. */
    private void scanFolder(DocumentFile folder, List<VideoModel> tempVideoList) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());

        for (DocumentFile file : folder.listFiles()) {
            if (!isAdded()) return;

            if (file.isDirectory()) {
                scanFolder(file, tempVideoList);
            } else {
                String name = file.getName() == null ? "" : file.getName().toLowerCase();
                if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")) {

                    long lastModified = file.lastModified();
                    String lastModifiedString = sdf.format(new Date(lastModified));
                    try {
                        String currentStatus = "PENDING";

                        // Check server JSON array for uploaded status
                        for (int i = 0; i < jsonArrayCheck.length(); i++) {
                            JSONObject cv = jsonArrayCheck.getJSONObject(i);
                            if (cv.getString("original_filename").equals(file.getName())) {
                                currentStatus = "ALREADY UPLOADED"; // CORRECTED STATUS
                                break;
                            }
                        }

                        // Check if the video is currently in the local list and keep its status
                        for (VideoModel existingVideo : videoList) {
                            if (existingVideo.getName().equals(file.getName())) {
                                // Prioritize ALREADY UPLOADED status if found in server list
                                if (!"ALREADY UPLOADED".equals(currentStatus)) {
                                    currentStatus = existingVideo.getStatus_upload();
                                }
                                break;
                            }
                        }

                        tempVideoList.add(new VideoModel(
                                file.getUri().toString(),
                                file.getName(),
                                lastModified,
                                lastModifiedString,
                                CON_CAMERA,
                                currentStatus
                        ));

                    } catch (JSONException e) {
                        Log.e("OKHTTP", "JSON Parsing Error: " + e.getMessage());
                    }
                }
            }
        }
    }

    /** * Registers or unregisters the ContentObserver based on the provided URI.
     * Must be called on the Main Thread.
     */
    private void updateContentObserver(Uri rootUri) {
        safeRunOnUiThread(() -> {
            if (rootUri != null && !rootUri.equals(currentUsbUri)) {
                // 1. Unregister old observer if it exists
                if (currentUsbUri != null) {
                    try {
                        requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
                    } catch (IllegalArgumentException e) {
                        Log.e("OBSERVER", "Observer was not registered/already unregistered.", e);
                    }
                }
                // 2. Register new observer
                currentUsbUri = rootUri;
                requireContext().getContentResolver().registerContentObserver(
                        rootUri,
                        true,
                        fileChangeObserver
                );
                Log.d("OBSERVER", "Registered observer for URI: " + currentUsbUri.toString());
            } else if (rootUri == null && currentUsbUri != null) {
                // 1. Unregister old observer if URI is now null
                try {
                    requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
                    currentUsbUri = null;
                    Log.d("OBSERVER", "Unregistered observer.");
                } catch (IllegalArgumentException e) {
                    Log.e("OBSERVER", "Observer was not registered/already unregistered.", e);
                }
            }
        });
    }

    /** UI UPDATE (Sets status text and button visibility) */
    private void updateUiStatus(String msg, boolean showButton) {
        safeRunOnUiThread(() -> {
            if (tvStatus != null) {
                tvStatus.setText(msg);
                if (btnRequestUsb != null) {
                    btnRequestUsb.setVisibility(showButton ? View.VISIBLE : View.GONE);
                }
                updateErrorStatus(null);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Canceled existing OkHttp calls
        if (isAdded()) {
            for (Call call : client.dispatcher().queuedCalls()) {
                if (REQUEST_TAG.equals(call.request().tag())) {
                    call.cancel();
                }
            }
            for (Call call : client.dispatcher().runningCalls()) {
                if (REQUEST_TAG.equals(call.request().tag())) {
                    call.cancel();
                }
            }
            Log.d("OKHTTP", "Cancelled pending upload requests with tag: " + REQUEST_TAG);
        }

        if (isAdded()) {
            // Unregister observers
            if (fileChangeObserver != null && currentUsbUri != null) {
                try {
                    requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
                    currentUsbUri = null; // FIX: Ensure currentUri is cleared after unregistration
                } catch (IllegalArgumentException e) {
                    Log.e("OBSERVER", "Observer not registered/already unregistered.", e);
                }
            }

            // SERVICE CLEANUP: Unbind the service
            if (isBound) {
                requireContext().unbindService(this);
                isBound = false;
                uploadService = null;
                Log.d("SERVICE_BINDING", "UploadService unbound.");
            }
        }
    }

    /**
     * Loads the list of already uploaded videos from the server.
     */
    private void loadVideoUploaded(String url, String vId){
        if (!isAdded()) return;

        String finalUrl = Uri.parse(base_url + url.substring(1)).toString();
        Log.d("OKHTTP", "Final URL: " + finalUrl);

        RequestBody requestBody = new FormBody.Builder()
                .add("project_id", vId)
                .build();

        Request request = new Request.Builder()
                .url(finalUrl)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("OKHTTP", "OkHttp Error: " + e.getMessage());
                // Stop refresh animation on network failure
                safeRunOnUiThread(() -> {
                    if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                        swipeRefreshLayout.setRefreshing(false);
                    }

                    // If fetching server history fails, proceed with the local scan anyway
                    if (getPersistedUris().isEmpty()) {
                        updateErrorStatus("Network error. Cannot verify uploaded history. Check connectivity.");
                        updateUiStatus("Network error. Cannot verify uploaded history. Check connectivity.", true);
                    } else {
                        updateErrorStatus("Network error. Cannot verify uploaded history. Scanning local storage now.");
                        scanAllPersistedUris(); // Trigger scan even on failure
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e("OKHTTP", "Unexpected code " + response);
                    safeRunOnUiThread(() -> {
                        updateErrorStatus("Failed to load upload history: Server returned " + response.code());
                        scanAllPersistedUris(); // Trigger scan on HTTP failure too
                    });
                    return;
                }

                try (ResponseBody responseBody = response.body()) {
                    if (responseBody == null) return;
                    String responseString = responseBody.string();
                    Log.d("OKHTTP", "Response: " + responseString);

                    try {
                        jsonArrayCheck = new JSONArray(responseString);
                        // Trigger the ONE TIME initial scan refresh after loading upload history
                        scanAllPersistedUris();
                    } catch (JSONException e){
                        Log.e("OKHTTP", "JSON Parsing Error: " + e.getMessage());
                        safeRunOnUiThread(() -> {
                            updateErrorStatus("Error parsing upload history. Scanning local storage now.");
                            scanAllPersistedUris(); // Trigger scan on JSON error
                        });
                    }
                } finally {
                    response.close();
                }
            }
        });

        Log.d("OKHTTP", "Request queued for Project ID: " + vId);
    }

    private void updateErrorStatus(String msg) {
        safeRunOnUiThread(() -> {
            if (tvError != null) {
                tvError.setText(msg);
                tvError.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
    }

    /** Utility to safely run code on the UI thread only if the fragment is attached. */
    private void safeRunOnUiThread(Runnable action) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(action);
    }
}
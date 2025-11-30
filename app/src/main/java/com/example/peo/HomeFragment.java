package com.example.peo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.content.SharedPreferences;
import android.database.Cursor; // NEW: For reading file metadata from Uri

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

// --- OKHTTP Imports ---
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.MediaType; // NEW: For multipart content type
import okhttp3.MultipartBody; // NEW: For file uploads
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink; // NEW: For efficient stream writing
import okio.Okio; // NEW: For stream utilities
// --- END OKHTTP Imports ---

import com.example.peo.adapter.VideoAdapter;
import com.example.peo.model.VideoModel;
import com.example.peo.utility.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream; // NEW: For reading file stream
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class HomeFragment extends Fragment {

    // --- OkHttp client and tag ---
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS) // Increased timeout for large video files
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    private static final String REQUEST_TAG = "VIDEO_UPLOAD_REQUEST";
    // ------------------------------------------

    // FIX: Base URL ends with a slash (/)
    final String base_url = "http://apps.leyteprovince.gov.ph:70/gov_peo/index.php/";
    private static final String PREFS_NAME = "usb_prefs";
    private static final String KEY_PERSISTED_URIS = "persisted_uris";

    // --- CONSTANTS FOR PROJECT NAME CHECK (app_local_data) ---
    private static final String APP_PREFS_FILE = "app_local_data";
    private static final String PROJECT_NAME_KEY = "name_of_project";

    // --- NEW CONSTANTS FOR PROJECT SETTINGS (ProjectSettings) ---
    private static final String PREFS_NAME_SETTINGS = "ProjectSettings";
    private static final String KEY_PROJECT_ID = "selected_project_id";
    private static final String KEY_CAMERA_1_ID = "camera1ID"; // Camera 2
    private static final String CON_CAMERA = "camera 1";
    // ------------------------------------------

    private TextView tvStatus;
    private TextView tvProjectName;
    private TextView tvSelectedFolder;
    private Button btnRequestUsb;
    private RecyclerView rvVideos;
    private SwipeRefreshLayout swipeRefreshLayout;
    private VideoAdapter videoAdapter;
    private final List<VideoModel> videoList = new ArrayList<>();

    private UsbStorageReceiver usbStorageReceiver;

    private Handler observerHandler = new Handler(Looper.getMainLooper());
    private ContentObserver fileChangeObserver;
    private Uri currentUsbUri = null;

    /** SAF Launcher */
    private ActivityResultLauncher<Intent> openUsbLauncher;
    String camera1Id;
    String projectId;

    JSONArray jsonArrayCheck = new JSONArray();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // [Existing ContentObserver setup]
        fileChangeObserver = new ContentObserver(observerHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                if (!selfChange && currentUsbUri != null) {
                    scanAllPersistedUris();
                }
            }
            @Override
            public boolean deliverSelfNotifications() {
                return true;
            }
        };

        // [Existing ActivityResultLauncher setup]
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
                            // Update folder name display after a successful selection
                            updateProjectAndFolderInfo();
                        }
                    } else {
                        // If access denied/canceled, button shows
                        updateUiStatus("Storage access denied. Click button to try again.", true);
                    }
                }
        );
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        // --- NEW LOGIC: Check for 'name_of_project' first ---
        SharedPreferences sharedPrefs = requireContext().getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE);

        if (!sharedPrefs.contains(PROJECT_NAME_KEY)) {
            // Key is missing, redirect to SettingFragment immediately
            navigateToSettingFragment();
            // Return a dummy view or null, as the fragment will be replaced
            return inflater.inflate(R.layout.fragment_home, container, false);
        }
        // --- END NEW LOGIC ---

        // Proceed with normal setup if the project name exists
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        SharedPreferences settingsPrefs = requireContext().getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE);
        projectId = settingsPrefs.getString(KEY_PROJECT_ID, "N/A"); // Load project ID here too
        camera1Id = settingsPrefs.getString(KEY_CAMERA_1_ID, "Not Set");

        // Load video already uploaded (Only run once on fragment creation)
        if (!"N/A".equals(projectId)) {
            loadVideoUploaded("/Api/getUploadedVideo", projectId);
        }

        tvStatus = view.findViewById(R.id.tvStatus);
        tvProjectName = view.findViewById(R.id.tvProjectName);
        tvSelectedFolder = view.findViewById(R.id.tvSelectedFolder);
        rvVideos = view.findViewById(R.id.rvVideos);
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);

        rvVideos.setLayoutManager(new LinearLayoutManager(requireContext()));
        videoAdapter = new VideoAdapter(requireContext(), videoList);
        rvVideos.setAdapter(videoAdapter);

        btnRequestUsb = view.findViewById(R.id.btnRequestUsb);
        btnRequestUsb.setOnClickListener(v -> requestStorageAccess());

        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (!getPersistedUris().isEmpty()) {
                scanAllPersistedUris();
            } else {
                swipeRefreshLayout.setRefreshing(false);
                updateUiStatus("Cannot refresh. Please grant storage access first.", true);
            }
        });

        registerUsbStorageReceiver();
        checkExistingUris();
        updateProjectAndFolderInfo(); // Call to update the new TextViews

        return view;
    }

    /** Loads project name, project ID, camera 1 ID, and selected folder URI to display on the UI. */
    private void updateProjectAndFolderInfo() {
        if (!isAdded()) return;

        // Load Project Name from the common app file
        SharedPreferences appSharedPrefs = requireContext().getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE);
        String projectName = appSharedPrefs.getString(PROJECT_NAME_KEY, "N/A");

        // Load Project ID and Camera 1 ID from ProjectSettings
        SharedPreferences settingsPrefs = requireContext().getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE);
        projectId = settingsPrefs.getString(KEY_PROJECT_ID, "N/A");
        camera1Id = settingsPrefs.getString(KEY_CAMERA_1_ID, "Not Set");

        // NOTE: loadVideoUploaded is only called in onCreateView for initial data load.

        requireActivity().runOnUiThread(() -> {
            if (tvProjectName != null) {
                // Display Project Name and ID
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
            // 1. Call the Activity's method to replace the fragment
            activity.loadFragment(new SettingFragment());
            // 2. Update the BottomNavigationView selection
            activity.bottomNavigationView.setSelectedItemId(R.id.setting);
        }
    }

    /** Shows a dialog prompting the user to grant storage access via SAF. */
    private void showStorageAccessDialog() {
        if (isAdded()) { // Safety check
            new AlertDialog.Builder(requireContext())
                    .setTitle("Storage Access Required")
                    .setMessage("A storage device (USB/local) needs permission. Tap 'Grant Access', and in the next screen, select the ROOT directory (e.g., 'My USB Drive' or 'Internal Storage') to grant access to all files.")
                    .setPositiveButton("Grant Access", (dialog, which) -> {
                        requestStorageAccess();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        // Leaves the button visible if the user cancels the dialog
                        updateUiStatus("Storage detected. Access not granted. Click Request Storage Access to scan.", true);
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

    /** CHECK URIS: Checks and scans all existing URIs. */
    private void checkExistingUris() {
        if (!getPersistedUris().isEmpty()) {
            scanAllPersistedUris();
        } else {
            // Button visible if no access granted initially
            updateUiStatus("Waiting for USB connection or manual request.", true);
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

    /** USB BROADCAST RECEIVER */
    private void registerUsbStorageReceiver() {
        if (usbStorageReceiver == null) {
            usbStorageReceiver = new UsbStorageReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
            filter.addAction(Intent.ACTION_MEDIA_REMOVED);
            filter.addDataScheme("file");

            // Check if context is available
            if (isAdded()) {
                requireContext().registerReceiver(usbStorageReceiver, filter);
            }
        }
    }

    private class UsbStorageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Check if fragment is attached before running UI operations
            if (!isAdded()) return;

            if (Intent.ACTION_MEDIA_MOUNTED.equals(intent.getAction())) {
                if (!getPersistedUris().isEmpty()) {
                    scanAllPersistedUris();
                } else {
                    requireActivity().runOnUiThread(() -> showStorageAccessDialog());
                }

            } else if (Intent.ACTION_MEDIA_REMOVED.equals(intent.getAction())) {

                videoList.clear();
                videoAdapter.notifyDataSetChanged();
                updateUiStatus("USB removed.", true);
                updateProjectAndFolderInfo(); // Update folder info on removal

                if (fileChangeObserver != null && currentUsbUri != null) {
                    // Unregister ContentObserver on removal
                    requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
                    currentUsbUri = null;
                }
            }
        }
    }

    /** Master scan function: Iterates over all saved URIs and calls scanFolder for each. */
    private void scanAllPersistedUris() {
        if (!isAdded()) return; // Critical: Ensure fragment is attached

        // Hide button during scan (must be on main thread)
        updateUiStatus("Scanning storage devices...", false);

        // This setter does not require an Activity context, so it's safe to call.
        swipeRefreshLayout.setRefreshing(true);
        List<VideoModel> tempVideoList = new ArrayList<>();

        new Thread(() -> {

            Set<String> uriSet = getPersistedUris();

            Uri firstValidUri = null;

            for (String uriString : uriSet) {
                if (!isAdded()) return; // Exit thread if fragment is detached

                Uri rootUri = Uri.parse(uriString);

                // Use try-catch for permission/read exceptions during DocumentFile operations
                try {
                    DocumentFile root = DocumentFile.fromTreeUri(requireContext(), rootUri);

                    if (root != null && root.isDirectory() && root.canRead()) {
                        scanFolder(root, tempVideoList);
                        if (firstValidUri == null) {
                            firstValidUri = rootUri;
                        }
                    }
                } catch (SecurityException e) {
                    Log.e("SAF_SCAN", "Security Exception reading URI: " + uriString, e);
                    // Handle case where permission was revoked externally
                }
            }

            // Must ensure Fragment is attached before registering observer
            if (isAdded()) {
                updateContentObserver(firstValidUri);
            }

            final int foundCount = tempVideoList.size();
            // DETERMINE BUTTON VISIBILITY: Show button if no videos were found (count == 0)
            final boolean showButton = foundCount == 0;

            // Sort by last modified time
            tempVideoList.sort((v1, v2) -> Long.compare(v1.getLastModified(), v2.getLastModified()));

            // CRITICAL FIX: Ensure fragment is still attached before running UI updates
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    // 1. UPDATE AND DISPLAY THE LIST
                    videoList.clear();
                    videoList.addAll(tempVideoList);
                    videoAdapter.notifyDataSetChanged();
                    swipeRefreshLayout.setRefreshing(false);

                    // Show the button if no videos were found
                    updateUiStatus("Videos found: " + foundCount, showButton);
                    updateProjectAndFolderInfo(); // Update folder info after scan

                    // 2. NOW INITIATE THE UPLOADS AFTER THE LIST IS VISIBLE
                    for (VideoModel video: tempVideoList){
                        // Check if the video is already uploaded before starting the upload
                        if (!"ALREADY UPLOADED".equals(video.getStatus_upload())) {
                            uploadVideoAsync(video);
                        }
                    }
                });
            }
        }).start();
    }

    private void uploadVideoAsync(VideoModel video) {
        new Thread(() -> {
            try {
                // This part runs on a background thread
//                String base64 = FileUtils.convertUriToBase64(getContext(), Uri.parse(video.getPath()));

                // --- NEW: Test Upload Functionality (Commented out by default) ---
                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), "Running Upload...", Toast.LENGTH_SHORT).show());
                TestUpload("/Api/upload_time_lapse_video", Uri.parse(video.getPath()), video);
                // ------------------------------------------------------------------

            } catch (Exception e) {
                // Handle local errors (e.g., file not found, encoding failed)
                // ❌ FAILED - Local Processing Error
                video.setStatus_upload("UPLOAD FAILED"); // Simplified error status

                int index = findVideoModelIndex(video.getPath());
                if (index != -1) {
                    requireActivity().runOnUiThread(() -> videoAdapter.notifyItemChanged(index));
                }

                Log.e("UPLOAD", e.getMessage());
            }
        }).start();
    }


    /** Scans a single folder recursively. Videos are added to the shared tempVideoList. */
    private void scanFolder(DocumentFile folder, List<VideoModel> tempVideoList) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());

        for (DocumentFile file : folder.listFiles()) {
            if (!isAdded()) return; // Safety check inside recursion

            if (file.isDirectory()) {
                scanFolder(file, tempVideoList);
            } else {
                String name = file.getName() == null ? "" : file.getName().toLowerCase();
                if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")) {

                    long lastModified = file.lastModified();
                    String lastModifiedString = sdf.format(new Date(lastModified));
                    try {
                        boolean flag = true;
                        for (int i = 0; i < jsonArrayCheck.length(); i++) {
                            JSONObject cv = jsonArrayCheck.getJSONObject(i);
                            if (cv.getString("original_filename").equals(file.getName())) {
                                tempVideoList.add(new VideoModel(
                                        file.getUri().toString(),
                                        file.getName(),
                                        lastModified,
                                        lastModifiedString,
                                        cv.getString("folder_name"),
                                        "ALREADY UPLOADED"
                                ));
                                flag = false;
                                break;
                            }
                        }
                        if (flag) {
                            tempVideoList.add(new VideoModel(
                                    file.getUri().toString(),
                                    file.getName(),
                                    lastModified,
                                    lastModifiedString,
                                    CON_CAMERA,
                                    "PENDING" // Initial status before upload starts
                            ));
                        }
                    } catch (JSONException e) {
                        Log.e("OKHTTP", "JSON Parsing Error: " + e.getMessage());
                    }
                }
            }
        }
    }

    /** Registers or unregisters the ContentObserver based on the provided URI. */
    private void updateContentObserver(Uri rootUri) {
        // Only run if the fragment is attached
        if (!isAdded()) return;

        if (rootUri != null && !rootUri.equals(currentUsbUri)) {
            if (currentUsbUri != null) {
                // Safely unregister the old observer
                try {
                    requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
                } catch (IllegalArgumentException e) {
                    Log.e("OBSERVER", "Observer was not registered/already unregistered.", e);
                }
            }
            currentUsbUri = rootUri;
            requireContext().getContentResolver().registerContentObserver(
                    rootUri,
                    true,
                    fileChangeObserver
            );
        } else if (rootUri == null && currentUsbUri != null) {
            // Safely unregister the observer if URI is null
            try {
                requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
            } catch (IllegalArgumentException e) {
                Log.e("OBSERVER", "Observer was not registered/already unregistered.", e);
            }
            currentUsbUri = null;
        }
    }

    /** UI UPDATE (Sets status text and button visibility) */
    private void updateUiStatus(String msg, boolean showButton) {
        // CRITICAL FIX: Only run UI updates if the fragment is attached AND the view is available
        if (tvStatus != null && isAdded()) {
            requireActivity().runOnUiThread(() -> {
                // Must ensure view is not null before accessing UI elements
                if (getView() != null) {
                    tvStatus.setText(msg);
                    if (btnRequestUsb != null) {
                        btnRequestUsb.setVisibility(showButton ? View.VISIBLE : View.GONE);
                    }
                }
            });
        }
    }

    /** Helper to find the index of a video model based on its path (URI) */
    private int findVideoModelIndex(String path) {
        for (int i = 0; i < videoList.size(); i++) {
            if (videoList.get(i).getPath().equals(path)) {
                return i;
            }
        }
        return -1;
    }

    /** Helper to get the current time formatted for check-in status */
    private String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // --- OKHTTP REPLACEMENT: Cancel all pending requests with the upload tag ---
        if (isAdded()) {
            // Iterate over the dispatcher's running and queued calls and cancel them
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
        // ----------------------------------------------------------------------

        // Check if context is available before unregistering
        if (isAdded()) {
            if (usbStorageReceiver != null) {
                try {
                    requireContext().unregisterReceiver(usbStorageReceiver);
                } catch (IllegalArgumentException e) {
                    Log.e("RECEIVER", "Receiver not registered/already unregistered.", e);
                }
            }
            if (fileChangeObserver != null && currentUsbUri != null) {
                try {
                    requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
                } catch (IllegalArgumentException e) {
                    Log.e("OBSERVER", "Observer not registered/already unregistered.", e);
                }
            }
        }
    }

    /**
     * OKHTTP REPLACEMENT for loadVideoUploaded (Volley StringRequest)
     * Loads the list of already uploaded videos from the server.
     */
    private void loadVideoUploaded(String url, String vId){
        if (!isAdded()) return; // Safety check before using context

        // FIX: Remove the leading slash from 'url' to prevent double slashes in the final path
        String finalUrl = Uri.parse(base_url + url.substring(1)).toString();
        Log.d("OKHTTP", "Final URL: " + finalUrl);

        // Build the POST request body
        RequestBody requestBody = new FormBody.Builder()
                .add("project_id", vId)
                .build();

        // Build the Request object
        Request request = new Request.Builder()
                .url(finalUrl)
                .post(requestBody)
                .build();

        // Enqueue the request asynchronously
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("OKHTTP", "OkHttp Error: " + e.getMessage());
                // Handle network failure or request cancellation
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e("OKHTTP", "Unexpected code " + response);
                    return;
                }

                try (ResponseBody responseBody = response.body()) {
                    if (responseBody == null) return;
                    String responseString = responseBody.string();
                    Log.d("OKHTTP", "Response: " + responseString);

                    try {
                        // Response is expected to be a JSON Array
                        jsonArrayCheck = new JSONArray(responseString);
                    } catch (JSONException e){
                        Log.e("OKHTTP", "JSON Parsing Error: " + e.getMessage());
                    }
                } finally {
                    response.close();
                }
            }
        });

        Log.d("OKHTTP", "Request queued for Project ID: " + vId);
    }


    /**
     * OKHTTP REPLACEMENT for UploadVideo (Volley StringRequest)
     * Uploads the video file (base64) to the server.
     */
    private void UploadVideo(String url, String base64, String file_name, final VideoModel video) {
        // This method is not used in the new file upload flow but is kept for context
        if (!isAdded()) return; // Safety check before using context

        video.setStatus_upload("UPLOADING"); // Old status, kept for this unused method
        // Update the UI
        requireActivity().runOnUiThread(() -> videoAdapter.notifyDataSetChanged());

        // FIX: Remove the leading slash from 'url' to prevent double slashes in the final path
        String finalUrl = Uri.parse(base_url + url.substring(1)).toString();
        Log.d("OKHTTP", "Final URL: " + finalUrl);

        // Build the POST request body (FormBody for form-encoded data)
        RequestBody requestBody = new FormBody.Builder()
                .add("camera_id", camera1Id)
                .add("project_id", projectId)
                .add("video_base64", base64)
                // FIX: Added the missing 'video_filename' parameter back
                .add("video_filename", file_name)
                .build();

        // Build the Request object
        Request request = new Request.Builder()
                .url(finalUrl)
                .tag(REQUEST_TAG) // Tag for cancellation
                .post(requestBody)
                .build();

        // Enqueue the request asynchronously
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Run on UI thread to update status
                requireActivity().runOnUiThread(() -> {
                    Log.e("OKHTTP", "OkHttp Error: " + e.getMessage());
                    video.setStatus_upload("UPLOAD FAILED"); // Old status, kept for this unused method
                    videoAdapter.notifyDataSetChanged();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (okhttp3.ResponseBody responseBody = response.body()) {
                    // Check for general HTTP error (e.g., 404, 500)
                    if (!response.isSuccessful()) {
                        Log.e("OKHTTP", "Unexpected code " + response);
                        // Run on UI thread to update status
                        requireActivity().runOnUiThread(() -> {
                            // Changed status message to be less specific as 500 is now handled generically
                            video.setStatus_upload("UPLOAD FAILED: HTTP Error " + response.code());
                            videoAdapter.notifyDataSetChanged();
                        });
                        return;
                    }

                    if (responseBody == null) {
                        Log.e("OKHTTP", "Empty response body");
                        // Run on UI thread to update status
                        requireActivity().runOnUiThread(() -> {
                            video.setStatus_upload("UPLOAD FAILED: Empty Response"); // Old status, kept for this unused method
                            videoAdapter.notifyDataSetChanged();
                        });
                        return;
                    }

                    String responseString = responseBody.string();
                    Log.d("OKHTTP", "Response UPLOAD VIDEO : " + responseString);

                    try {
                        JSONObject jsonResponse = new JSONObject(responseString);
                        // Check the "status" field in the server response
                        String status = jsonResponse.optString("status", "error");

                        if ("error".equalsIgnoreCase(status)) {
                            // CONDITION MET: status is "error"
                            video.setStatus_upload("UPLOAD FAILED"); // Old status, kept for this unused method
                            // Toast must run on main thread
                            requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), "Upload failed for " + file_name, Toast.LENGTH_SHORT).show());
                        } else {
                            // Status is "success" or any other successful status
                            video.setStatus_upload("UPLOADING COMPLETE"); // Old status, kept for this unused method
                        }
                    } catch (JSONException e) {
                        // JSON parsing failed, treat as an error
                        Log.e("OKHTTP", "JSON Parsing Error on Upload Response: " + e.getMessage());
                        video.setStatus_upload("UPLOAD FAILED"); // Old status, kept for this unused method
                    }
                } finally {
                    response.close();
                    // Update the UI regardless of success or failure
                    requireActivity().runOnUiThread(() -> {
                        videoAdapter.notifyDataSetChanged();
                    });
                }
            }
        });

        Log.d("OKHTTP", "Request queued for Project ID: " + projectId);
    }

    /**
     * OKHTTP IMPLEMENTATION for TestUpload (Multipart File Upload)
     * Uploads the actual video file as multipart form data.
     * Parameter name is "video".
     */
    private void TestUpload(String url, Uri videoUri, VideoModel video) {
        if (!isAdded()) return;

        // 1. URL Construction Fix
        String finalUrl = Uri.parse(base_url + url.substring(1)).toString();
        Log.d("OKHTTP", "Test Upload URL: " + finalUrl);

        // 2. Get file metadata (filename and mimeType)
        String fileName = video.getName(); // Use the name already extracted
        String mimeType = "application/octet-stream";

        try (Cursor cursor = requireContext().getContentResolver().query(videoUri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                if (mimeIndex != -1) {
                    String extractedMimeType = cursor.getString(mimeIndex);
                    // Use extracted mimeType only if it's available
                    if (extractedMimeType != null && !extractedMimeType.isEmpty()) {
                        mimeType = extractedMimeType;
                    }
                }
            }
        } catch (Exception e) {
            Log.e("OKHTTP", "Error getting file metadata: " + e.getMessage());
            // ❌ FAILED - Metadata Error
            video.setStatus_upload("UPLOAD FAILED");
            int index = findVideoModelIndex(video.getPath());
            if (index != -1) requireActivity().runOnUiThread(() -> videoAdapter.notifyItemChanged(index));
            return; // Exit if file metadata retrieval fails
        }

        final String finalFileName = fileName;
        final String finalMimeType = mimeType;

        // 3. Create the RequestBody for the file stream
        RequestBody fileRequestBody = new RequestBody() {
            @Override
            public MediaType contentType() {
                // Use the determined MIME type
                return MediaType.parse(finalMimeType);
            }

            @Override
            public long contentLength() {
                // Return -1 to signal unknown length, forcing streaming
                return -1;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                try (InputStream inputStream = requireContext().getContentResolver().openInputStream(videoUri)) {
                    if (inputStream == null) {
                        throw new IOException("Cannot open InputStream for URI: " + videoUri);
                    }
                    // Use Okio to read from InputStream and write to the sink
                    long bytesWritten = sink.writeAll(Okio.source(inputStream));
                    Log.d("OKHTTP", "Bytes written for " + finalFileName + ": " + bytesWritten);
                    // CRITICAL FIX: Removed incorrect status update from the successful stream write block.
                } catch (Exception e) {
                    // This block catches exceptions during the *streaming* of the file.
                    Log.e("OKHTTP", "Error writing file to request body: " + e.getMessage());
                    // ❌ FAILED - Stream Write Error: Must update model status for UI refresh
                    // Note: The UI update must happen on the main thread, but this method is on OkHttp's thread.
                    // The main onFailure will handle the final status. Setting the status here is a temporary step.
                    video.setStatus_upload("UPLOAD FAILED");

                    // Re-throw the IOException so OkHttp correctly treats this as a network failure
                    throw new IOException("Failed to write file stream.", e);
                }
            }
        };

        // 4. Construct the MultipartBody
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("camera_id", camera1Id)
                .addFormDataPart("project_id", projectId)
                .addFormDataPart("video", finalFileName, fileRequestBody)
                .build();

        // 5. Build the Request
        Request request = new Request.Builder()
                .url(finalUrl)
                .tag(REQUEST_TAG)
                .post(requestBody)
                .build();

        // Set status to UPLOADING before starting the call
        // 🔄 UPLOADING
        video.setStatus_upload("UPLOADING");
        int index = findVideoModelIndex(video.getPath());
        if (index != -1) requireActivity().runOnUiThread(() -> videoAdapter.notifyItemChanged(index));

        // 6. Enqueue the request
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // ❌ FAILED - Network Error
                requireActivity().runOnUiThread(() -> {
                    Log.e("OKHTTP", "Test Upload Failed: " + e.getMessage());
                    video.setStatus_upload("UPLOAD FAILED");
                    int index = findVideoModelIndex(video.getPath());
                    if (index != -1) videoAdapter.notifyItemChanged(index);
                    Toast.makeText(requireContext(), "Test Upload Failed for " + finalFileName + " (Network)", Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    final String responseString = (responseBody != null) ? responseBody.string() : "Empty Response";
                    Log.d("OKHTTP", "Response TEST UPLOAD: " + response.code() + " - " + responseString);

                    requireActivity().runOnUiThread(() -> {
                        int index = findVideoModelIndex(video.getPath());

                        if (response.isSuccessful()) {
                            // ✅ SUCCESS
                            video.setStatus_upload("UPLOAD COMPLETE");
                        } else {
                            // ❌ FAILED - HTTP Error
                            video.setStatus_upload("UPLOAD FAILED");
                        }

                        if (index != -1) videoAdapter.notifyItemChanged(index);

                        String statusMsg = response.isSuccessful() ? "Test Upload Success!" : "Test Upload Failed: HTTP " + response.code();
                        String toastText = statusMsg + " | " + responseString.substring(0, Math.min(responseString.length(), 100)) + "...";
                        Toast.makeText(requireContext(), toastText, Toast.LENGTH_LONG).show();
                    });
                } finally {
                    response.close();
                }
            }
        });
    }
}
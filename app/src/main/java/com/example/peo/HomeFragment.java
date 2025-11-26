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

import com.android.volley.Request;
import com.android.volley.toolbox.StringRequest;
// NOTE: We will be using the VolleySingleton class, not direct Volley.newRequestQueue()
import com.android.volley.toolbox.Volley;
import com.example.peo.adapter.VideoAdapter;
import com.example.peo.model.VideoModel;
// import com.example.peo.utility.FileUtils; // FileUtils is not directly used in the improved scan
import com.example.peo.utility.FileUtils;
import com.example.peo.utility.VolleySingleton; // <-- Import the new VolleySingleton

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class HomeFragment extends Fragment {

    // NOTE: Ensure your project has a working VolleySingleton class to execute requests.
    // Replace 'VolleySingleton' with your actual class name if different.

    final String base_url = "http://services.leyteprovince.gov.ph/gov_peo/index.php";
    private static final String PREFS_NAME = "usb_prefs";
    private static final String KEY_PERSISTED_URIS = "persisted_uris";

    // --- CONSTANTS FOR PROJECT NAME CHECK (app_local_data) ---
    private static final String APP_PREFS_FILE = "app_local_data";
    private static final String PROJECT_NAME_KEY = "name_of_project";

    // --- NEW CONSTANTS FOR PROJECT SETTINGS (ProjectSettings) ---
    private static final String PREFS_NAME_SETTINGS = "ProjectSettings";
    private static final String KEY_PROJECT_ID = "selected_project_id";
//    private static final String KEY_CAMERA_1_ID = "camera1ID"; // Camera 1
    private static final String KEY_CAMERA_1_ID = "camera2ID"; // Camera 2
    private static final String CON_CAMERA = "camera 2";
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

    JSONArray jsonArrayCheck;

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

        Log.i("test", "Checking: " + projectId);
        // Load video already uploaded
        if (!"N/A".equals(projectId)) {
            loadVideoUploaded("/Api/getUploadedVideo", projectId);
        }


        requireActivity().runOnUiThread(() -> {
            if (tvProjectName != null) {
                // Display Project Name and ID
                tvProjectName.setText("Project: " + projectName + " (ID: " + projectId + ")");
            }

            if (tvSelectedFolder != null) {
//                tvSelectedFolder.setText("\nCamera 1 Folder ID: " + camera1Id);
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

        new Thread(() -> {

            List<VideoModel> tempVideoList = new ArrayList<>();
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
                    videoList.clear();
                    videoList.addAll(tempVideoList);
                    videoAdapter.notifyDataSetChanged();
                    swipeRefreshLayout.setRefreshing(false);

                    // Show the button if no videos were found
                    updateUiStatus("Videos found: " + foundCount, showButton);
                    updateProjectAndFolderInfo(); // Update folder info after scan
                });
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

                            // --- uploading -------------------------------------
                             String base64String = FileUtils.convertUriToBase64(getContext(), file.getUri());
                             UploadVideo("/Api/upload_time_lapse", base64String, file.getName());
                            // ----------------------------------------------------
                            tempVideoList.add(new VideoModel(
                                    file.getUri().toString(),
                                    file.getName(),
                                    lastModified,
                                    lastModifiedString,
                                    "camera 1",
                                    "UPLOADED COMPLETE"
                            ));
                        }
                    } catch (JSONException e) {
                        Log.e("VOLLEY", "JSON Parsing Error: " + e.getMessage());
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
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

    private void loadVideoUploaded(String url, String vId){
        if (!isAdded()) return; // Safety check before using context

        String finalUrl = Uri.parse(base_url + url).toString();
        Log.d("VOLLEY", "Final URL: " + finalUrl);

        StringRequest stringRequest = new StringRequest(
                Request.Method.POST,
                finalUrl,
                response -> {
                    Log.d("VOLLEY", "Response: " + response);
                    try {
                        jsonArrayCheck = new JSONArray(response);
                    } catch (JSONException e){
                        Log.e("VOLLEY", "JSON Parsing Error: " + e.getMessage());
                    }
                },
                error -> {
                    Log.e("VOLLEY", "Volley Error: " + error.toString());
                }
        ){
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("project_id", vId);
                return params;
            }
        };

        // 🚀 FIX: Use VolleySingleton for efficient queue management
        VolleySingleton.getInstance(requireContext()).addToRequestQueue(stringRequest);

        Log.d("VOLLEY", "Request queued for Project ID: " + vId);
    }


    private void UploadVideo(String url, String base64, String file_name) {
        if (!isAdded()) return; // Safety check before using context

        String finalUrl = Uri.parse(base_url + url).toString();
        Log.d("VOLLEY", "Final URL: " + finalUrl);

        StringRequest stringRequest = new StringRequest(
                Request.Method.POST,
                finalUrl,
                response -> {
                    Log.d("VOLLEY", "Response UPLOAD VIDEO : " + response);
                },
                error -> {
                    Log.e("VOLLEY", "Volley Error: " + error.toString());
                }
        ){
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("camera_id", camera1Id);
                params.put("project_id", projectId);
                params.put("video_base64", base64);
                params.put("video_filename", file_name);
                return params;
            }
        };

        Volley.newRequestQueue(requireContext()).add(stringRequest);

        Log.d("VOLLEY", "Request queued for Project ID: " + projectId);
    }

}
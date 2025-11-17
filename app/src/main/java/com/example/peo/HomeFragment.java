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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.example.peo.adapter.VideoAdapter;
import com.example.peo.model.VideoModel;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class HomeFragment extends Fragment {

    private static final String PREFS_NAME = "usb_prefs";
    private static final String KEY_PERSISTED_URIS = "persisted_uris";

    private TextView tvStatus;
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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
                        // If access denied/canceled, button shows
                        updateUiStatus("Storage access denied. Click button to try again.", true);
                    }
                }
        );
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_home, container, false);

        tvStatus = view.findViewById(R.id.tvStatus);
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

        return view;
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

                if (fileChangeObserver != null && currentUsbUri != null) {
                    // Logic to unregister old observer is handled in updateContentObserver()
                }
            }
        }
    }

    /** Master scan function: Iterates over all saved URIs and calls scanFolder for each. */
    private void scanAllPersistedUris() {

        // Hide button during scan (must be on main thread)
        updateUiStatus("Scanning storage devices...", false);

        // This setter does not require an Activity context, so it's safe to call.
        swipeRefreshLayout.setRefreshing(true);

        new Thread(() -> {

            List<VideoModel> tempVideoList = new ArrayList<>();
            Set<String> uriSet = getPersistedUris();

            Uri firstValidUri = null;

            for (String uriString : uriSet) {
                // Must ensure Fragment is attached for requireContext() calls inside the loop
                if (!isAdded()) return;

                Uri rootUri = Uri.parse(uriString);

                DocumentFile root = DocumentFile.fromTreeUri(requireContext(), rootUri);

                if (root != null && root.isDirectory() && root.canRead()) {
                    scanFolder(root, tempVideoList);
                    if (firstValidUri == null) {
                        firstValidUri = rootUri;
                    }
                }
            }

            // Must ensure Fragment is attached before registering observer
            if (isAdded()) {
                updateContentObserver(firstValidUri);
            }


            final int foundCount = tempVideoList.size();
            // DETERMINE BUTTON VISIBILITY: Show button if no videos were found (count == 0)
            final boolean showButton = foundCount == 0;

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
                });
            }


        }).start();
    }

    /** Scans a single folder recursively. Videos are added to the shared tempVideoList. */
    private void scanFolder(DocumentFile folder, List<VideoModel> tempVideoList) {
        // ... (existing scanFolder logic)
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());

        for (DocumentFile file : folder.listFiles()) {
            if (file.isDirectory()) {
                scanFolder(file, tempVideoList);
            } else {
                String name = file.getName() == null ? "" : file.getName().toLowerCase();
                if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")) {

                    long lastModified = file.lastModified();
                    String lastModifiedString = sdf.format(new Date(lastModified));

                    tempVideoList.add(new VideoModel(
                            file.getUri().toString(),
                            file.getName(),
                            lastModified,
                            lastModifiedString
                    ));
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
                requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
            }
            currentUsbUri = rootUri;
            requireContext().getContentResolver().registerContentObserver(
                    rootUri,
                    true,
                    fileChangeObserver
            );
        } else if (rootUri == null && currentUsbUri != null) {
            requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
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
                requireContext().unregisterReceiver(usbStorageReceiver);
            }
            if (fileChangeObserver != null && currentUsbUri != null) {
                requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
            }
        }
    }
}
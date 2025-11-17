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
import android.provider.DocumentsContract; // Import for EXTRA_INITIAL_URI

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
import java.util.List;
import java.util.Locale;

public class HomeFragment extends Fragment {

    private static final String PREFS_NAME = "usb_prefs";
    private static final String KEY_USB_URI = "usb_uri";

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
                    scanUsbVideos(currentUsbUri);
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
                            // Stage 1: Permission is granted by the user via SAF. We persist it.
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    treeUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );

                            saveUsbUri(treeUri);
                            // Stage 2: Use the newly acquired URL to scan the USB
                            scanUsbVideos(treeUri);
                        }
                    } else {
                        updateUiStatus("USB access denied. Click button to try again.", true);
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
        btnRequestUsb.setOnClickListener(v -> requestUsbAccess());

        swipeRefreshLayout.setOnRefreshListener(() -> {
            Uri savedUri = getSavedUsbUri();
            if (savedUri != null) {
                scanUsbVideos(savedUri);
            } else {
                swipeRefreshLayout.setRefreshing(false);
                updateUiStatus("Cannot refresh. Please grant USB access first.", true);
            }
        });

        registerUsbStorageReceiver();
        checkExistingUsb();

        return view;
    }

    /** Shows a dialog prompting the user to grant USB access via SAF. */
    private void showUsbAccessDialog() {
        if (isAdded()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("USB Storage Access Required")
                    .setMessage("A USB device has been detected. To view all videos on the drive, please tap 'Grant Access', and in the next screen, **select the ROOT directory (e.g., 'My USB Drive')** to grant access to all files.")
                    .setPositiveButton("Grant Access", (dialog, which) -> {
                        requestUsbAccess();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        updateUiStatus("USB detected. Access not granted. Click Request USB Access to scan.", true);
                        dialog.dismiss();
                    })
                    .show();
        }
    }

    /** SAVE USB URI */
    private void saveUsbUri(Uri uri) {
        requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_USB_URI, uri.toString())
                .apply();
    }

    private Uri getSavedUsbUri() {
        String saved = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_USB_URI, null);
        return saved != null ? Uri.parse(saved) : null;
    }

    /** CHECK USB STATUS */
    private void checkExistingUsb() {
        Uri uri = getSavedUsbUri();
        // Automatically use saved URI if valid
        if (uri != null) {
            scanUsbVideos(uri);
        } else {
            updateUiStatus("Waiting for USB connection or manual request.", true);
        }
    }

    /** * SAF REQUEST: Launches the system document tree picker.
     * Uses the last known USB URI as a hint for the system picker.
     */
    private void requestUsbAccess() {
        Uri initialUri = getSavedUsbUri(); // Get the last known valid URI

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        // Use the saved URI to suggest a starting location for the picker (best effort hint)
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

            requireContext().registerReceiver(usbStorageReceiver, filter);
        }
    }

    private class UsbStorageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (Intent.ACTION_MEDIA_MOUNTED.equals(intent.getAction())) {

                Uri saved = getSavedUsbUri();

                if (saved != null) {
                    // If URI is saved, access is already granted, so scan immediately.
                    scanUsbVideos(saved);
                } else {
                    // If no URI is saved, prompt user to grant access
                    requireActivity().runOnUiThread(() -> showUsbAccessDialog());
                }

            } else if (Intent.ACTION_MEDIA_REMOVED.equals(intent.getAction())) {

                videoList.clear();
                videoAdapter.notifyDataSetChanged();
                updateUiStatus("USB removed.", true);

                if (fileChangeObserver != null && currentUsbUri != null) {
                    requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
                    currentUsbUri = null;
                }
            }
        }
    }

    /** SCAN USB FILES (Runs on a background thread) */
    private void scanUsbVideos(Uri rootUri) {

        updateUiStatus("Scanning USB storage...", false);
        swipeRefreshLayout.setRefreshing(true);

        // ContentObserver Registration/Update
        if (fileChangeObserver != null && !rootUri.equals(currentUsbUri)) {
            if (currentUsbUri != null) {
                requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
            }
            currentUsbUri = rootUri;
            requireContext().getContentResolver().registerContentObserver(
                    rootUri,
                    true,
                    fileChangeObserver
            );
        }

        new Thread(() -> {

            DocumentFile root = DocumentFile.fromTreeUri(requireContext(), rootUri);

            if (root == null || !root.isDirectory() || !root.canRead()) {
                requireActivity().runOnUiThread(() -> {
                    swipeRefreshLayout.setRefreshing(false);
                    updateUiStatus("Error: Saved USB access is invalid or disk removed. Please grant access again.", true);
                });
                return;
            }

            videoList.clear();
            scanFolder(root);

            // SORTING: Sort by lastModified, oldest to newest (ascending order)
            videoList.sort((v1, v2) -> Long.compare(v1.getLastModified(), v2.getLastModified()));

            requireActivity().runOnUiThread(() -> {
                videoAdapter.notifyDataSetChanged();
                swipeRefreshLayout.setRefreshing(false);
                updateUiStatus("Videos found: " + videoList.size(), false);
            });

        }).start();
    }

    private void scanFolder(DocumentFile folder) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());

        for (DocumentFile file : folder.listFiles()) {
            if (file.isDirectory()) {
                scanFolder(file);
            } else {
                String name = file.getName() == null ? "" : file.getName().toLowerCase();
                if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")) {

                    long lastModified = file.lastModified();
                    String lastModifiedString = sdf.format(new Date(lastModified));

                    videoList.add(new VideoModel(
                            file.getUri().toString(),
                            file.getName(),
                            lastModified,
                            lastModifiedString
                    ));
                }
            }
        }
    }

    /** UI UPDATE (Sets status text and button visibility) */
    private void updateUiStatus(String msg, boolean showButton) {
        if (tvStatus != null) {
            requireActivity().runOnUiThread(() -> {
                tvStatus.setText(msg);
                if (btnRequestUsb != null) {
                    btnRequestUsb.setVisibility(showButton ? View.VISIBLE : View.GONE);
                }
            });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (usbStorageReceiver != null) {
            requireContext().unregisterReceiver(usbStorageReceiver);
        }
        if (fileChangeObserver != null && currentUsbUri != null) {
            requireContext().getContentResolver().unregisterContentObserver(fileChangeObserver);
        }
    }
}
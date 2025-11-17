package com.example.peo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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
    private Button btnRequestUsb; // Class member for dynamic visibility
    private RecyclerView rvVideos;
    private VideoAdapter videoAdapter;
    private final List<VideoModel> videoList = new ArrayList<>();

    private UsbStorageReceiver usbStorageReceiver;

    /** SAF Launcher */
    private ActivityResultLauncher<Intent> openUsbLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

                            saveUsbUri(treeUri);
                            scanUsbVideos(treeUri); // Start scan immediately after getting URI
                        }
                    } else {
                        // Use unified status update on denial
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
        rvVideos.setLayoutManager(new LinearLayoutManager(requireContext()));
        videoAdapter = new VideoAdapter(requireContext(), videoList);
        rvVideos.setAdapter(videoAdapter);

        btnRequestUsb = view.findViewById(R.id.btnRequestUsb); // Initialize the button
        btnRequestUsb.setOnClickListener(v -> requestUsbAccess());

        registerUsbStorageReceiver();
        checkExistingUsb();

        return view;
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
        if (uri != null) {
            scanUsbVideos(uri);
        } else {
            // Use unified status update
            updateUiStatus("Waiting for USB connection or manual request.", true);
        }
    }

    /** SAF REQUEST */
    private void requestUsbAccess() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
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
                    scanUsbVideos(saved);
                } else {
                    // Automatic access request: if USB is mounted and no access is saved, request it.
                    updateUiStatus("USB detected. Automatically requesting access...", false);
                    requestUsbAccess();
                }

            } else if (Intent.ACTION_MEDIA_REMOVED.equals(intent.getAction())) {

                videoList.clear();
                videoAdapter.notifyDataSetChanged();
                // Use unified status update
                updateUiStatus("USB removed.", true);
            }
        }
    }

    /** SCAN USB FILES (Runs on a background thread) */
    private void scanUsbVideos(Uri rootUri) {

        updateUiStatus("Scanning USB storage...", false);

        // File I/O must run on a background thread
        new Thread(() -> {

            DocumentFile root = DocumentFile.fromTreeUri(requireContext(), rootUri);

            if (root == null || !root.isDirectory() || !root.canRead()) {
                // If the URI is invalid, prompt user to re-grant access
                updateUiStatus("Error: Saved USB access is invalid or disk removed. Please grant access again.", true);
                return;
            }

            videoList.clear();
            scanFolder(root);

            // SORTING: Sort by lastModified, oldest to newest (ascending order)
            videoList.sort((v1, v2) -> Long.compare(v1.getLastModified(), v2.getLastModified()));

            // Return to UI thread to update the adapter and final status
            requireActivity().runOnUiThread(() -> {
                videoAdapter.notifyDataSetChanged();
                // Scanning successful, hide the button
                updateUiStatus("Videos found: " + videoList.size(), false);
            });

        }).start();
    }

    private void scanFolder(DocumentFile folder) {
        // Date formatter for display
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());

        for (DocumentFile file : folder.listFiles()) {
            if (file.isDirectory()) {
                scanFolder(file);
            } else {
                String name = file.getName() == null ? "" : file.getName().toLowerCase();
                if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")) {

                    long lastModified = file.lastModified(); // Get the timestamp
                    String lastModifiedString = sdf.format(new Date(lastModified)); // Format for display

                    // Use new VideoModel constructor with date
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
                    // Set button visibility
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
    }
}
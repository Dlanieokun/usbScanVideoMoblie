package com.example.peo;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import androidx.fragment.app.Fragment;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

// --- ADDED OkHttp Imports ---
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import java.io.IOException;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// Assuming MainActivity, HomeFragment, and R.layout.fragment_setting are defined elsewhere
public class SettingFragment extends Fragment {

    // NOTE: For production use, define base_url in a constants file or strings.xml
    final String base_url = "http://apps.leyteprovince.gov.ph:70/gov_peo/index.php/";

    private AutoCompleteTextView projectDropdown;
    private MaterialButton configButton;
    // UI elements for showing saved status
    private TextView savedProjectTextView;
    private TextInputLayout projectDropdownContainer;
    private ProgressBar configProgressBar;


    // List to hold the entire project data (name and ID)
    private List<ProjectItem> projectList = new ArrayList<>();

    // Define keys for SharedPreferences
    private static final String PREF_NAME = "ProjectSettings";
    private static final String KEY_PROJECT_NAME = "selected_project_name";
    private static final String KEY_PROJECT_ID = "selected_project_id";
    //    private static final String KEY_CAMERA_1_ID = "camera1ID"; // Camera 1
    private static final String KEY_CAMERA_1_ID = "camera5ID"; // Camera 2
    private static final String CON_CAMERA = "camera 5";

    // Constants for the project check file (used by MainActivity and HomeFragment)
    private static final String APP_PREFS_FILE = "app_local_data";
    private static final String PROJECT_NAME_KEY = "name_of_project";

    // OkHttp Client instance (Replaces Volley RequestQueue)
    private OkHttpClient okHttpClient;


    /**
     * Helper class to store Project ID and Name together.
     */
    private static class ProjectItem {
        String id;
        String name;

        public ProjectItem(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Initialize OkHttpClient
        // Best practice: Reuse a single OkHttpClient instance throughout your app.
        okHttpClient = new OkHttpClient();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // R.layout.fragment_setting is assumed to exist
        View view = inflater.inflate(R.layout.fragment_setting, container, false);

        projectDropdown = view.findViewById(R.id.projectAutoCompleteTextView);
        configButton = view.findViewById(R.id.configButton);
        // Initialize new UI elements
        savedProjectTextView = view.findViewById(R.id.savedProjectTextView);
        projectDropdownContainer = view.findViewById(R.id.project_dropdown_container);
        configProgressBar = view.findViewById(R.id.configProgressBar);


        // Set OnClickListener to call the saving logic
        configButton.setOnClickListener(v -> saveSelectedProject());

        // Initial UI check and populate (if not configured)
        checkAndSetUI();

        // Fetch data only if the project is not already saved
        if (getSavedProjectName() == null) {
            processAllProject("/Api/listOfProjects");
        }


        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        checkAndSetUI();
    }

    /**
     * Displays the loading indicator and disables configuration UI elements.
     */
    private void showLoadingState() {
        if (!isAdded()) return;
        configButton.setText("Loading...");
        configButton.setEnabled(false);
        projectDropdown.setEnabled(false);
        configProgressBar.setVisibility(View.VISIBLE);
    }

    /**
     * Hides the loading indicator and restores configuration UI elements.
     * Called on API success or failure.
     */
    private void hideLoadingState(String buttonText) {
        if (!isAdded()) return;
        configButton.setText(buttonText);
        configButton.setEnabled(true);
        projectDropdown.setEnabled(true);
        configProgressBar.setVisibility(View.GONE);
    }

    /**
     * Checks SharedPreferences for a saved project and updates the UI.
     */
    private void checkAndSetUI() {
        if (!isAdded()) return;

        String savedProject = getSavedProjectName();

        if (savedProject != null) {
            // Project is saved: Hide config, show confirmation
            projectDropdownContainer.setVisibility(View.GONE);
            configButton.setVisibility(View.GONE);
            configProgressBar.setVisibility(View.GONE); // Ensure it's hidden
            savedProjectTextView.setVisibility(View.VISIBLE);
            savedProjectTextView.setText("✅ Project configured: \n" + savedProject);
        } else {
            // Project is NOT saved: Show config, hide confirmation
            projectDropdownContainer.setVisibility(View.VISIBLE);
            configButton.setVisibility(View.VISIBLE);
            configProgressBar.setVisibility(View.GONE); // Ensure it's hidden
            savedProjectTextView.setVisibility(View.GONE);
        }
    }

    /**
     * Retrieves the currently saved project name from SharedPreferences,
     * but only if the critical KEY_CAMERA_1_ID is also present.
     * @return The saved project name, or null if configuration is incomplete.
     */
    private String getSavedProjectName() {
        if (!isAdded()) return null;
        SharedPreferences sharedPref = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        // 1. Check for Project Name/ID
        String projectName = sharedPref.getString(KEY_PROJECT_NAME, null);
        String projectId = sharedPref.getString(KEY_PROJECT_ID, null);

        // 2. Check for Camera ID (The critical step)
        String camera1Id = sharedPref.getString(KEY_CAMERA_1_ID, null);

        // Configuration is complete only if we have all three crucial pieces of data
        boolean isFullyConfigured = projectName != null
                && !projectName.isEmpty()
                && !projectName.equals("--- Choose a Project ---")
                && projectId != null
                && camera1Id != null;

        if (isFullyConfigured) {
            return projectName;
        }

        return null;
    }


    /**
     * Finds the selected project's ID, saves the ID and Name to SharedPreferences,
     * and then calls the API to save the project folder.
     */
    private void saveSelectedProject() {
        // FIX 1: Ensure fragment is attached before accessing context
        if (!isAdded()) return;

        // 1. Get the currently selected text from the dropdown
        String selectedProjectName = projectDropdown.getText().toString();

        // Check if the placeholder is selected
        if (selectedProjectName.equals("--- Choose a Project ---") || selectedProjectName.isEmpty()) {
            Toast.makeText(requireContext(), "Please select a valid project first.", Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. Find the matching Project ID from the stored list
        String selectedProjectId = null;
        for (ProjectItem item : projectList) {
            if (item.name.equals(selectedProjectName)) {
                selectedProjectId = item.id;
                break;
            }
        }

        // 3. Save to SharedPreferences and call external API
        if (selectedProjectId != null) {
            // Get SharedPreferences for 'ProjectSettings'
            SharedPreferences sharedPref = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPref.edit();

            // Save the currently selected project details
            editor.putString(KEY_PROJECT_NAME, selectedProjectName);
            editor.putString(KEY_PROJECT_ID, selectedProjectId);

            // IMPORTANT: Clear the camera ID temporarily. It will be set by saveProjectFolder.
            // This ensures getSavedProjectName() is false until the second API succeeds.
            editor.remove(KEY_CAMERA_1_ID);

            // FIX 2: Also save the project name to the local file that HomeFragment and MainActivity check (app_local_data)
            SharedPreferences appSharedPrefs = requireContext().getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE);
            appSharedPrefs.edit().putString(PROJECT_NAME_KEY, selectedProjectName).apply();


            // Use commit() to ensure synchronous save and check for success
            if (editor.commit()) {
                // NEW: Show loading state since configuration is a two-step process
                showLoadingState();

                // SUCCESS: Now call the second API
                saveProjectFolder("/Api/listOfProjectFolders", selectedProjectId);
                Toast.makeText(requireContext(), "Project Selected. Retrieving folder details...", Toast.LENGTH_LONG).show();

                // FIX 3: Redirect to HomeFragment after a successful configuration (optional redirect)
                // if (isAdded() && getActivity() instanceof MainActivity) {
                //     MainActivity activity = (MainActivity) requireActivity();
                //     activity.loadFragment(new HomeFragment());
                //     activity.bottomNavigationView.setSelectedItemId(R.id.home);
                // }

            } else {
                Toast.makeText(requireContext(), "Failed to save project settings locally.", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(requireContext(), "Error: Could not find project ID.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Parses the JSON array response and populates the dropdown.
     */
    private void populateSpinner(String response) {
        // FIX 4: Ensure fragment is attached before calling requireContext()
        if (!isAdded()) {
            Log.w("OKHTTP", "Fragment not attached. Skipping UI update in populateSpinner.");
            return;
        }

        // Do not populate spinner if a project is already configured
        if (getSavedProjectName() != null) {
            Log.d("OKHTTP", "Project already configured. Skipping spinner population.");
            return;
        }

        List<String> projectNames = new ArrayList<>();
        // Clear previous data
        projectList.clear();

        try {
            // Correctly parse the response as a direct JSONArray
            JSONArray dataArray = new JSONArray(response);

            // Add a placeholder/default item at the start of the list
            String placeholder = "--- Choose a Project ---";
            projectNames.add(placeholder);

            for (int i = 0; i < dataArray.length(); i++) {
                JSONObject project = dataArray.getJSONObject(i);

                String projectId = project.getString("id");
                String projectName = project.getString("name_of_project");

                projectNames.add(projectName);

                // Store the project data in the list
                projectList.add(new ProjectItem(projectId, projectName));
            }
        } catch (JSONException e) {
            Log.e("OKHTTP", "JSON Parsing Error: " + e.getMessage());
            // Add error message to list if parsing fails
            projectNames.add("Error loading projects");
        }

        // Create an ArrayAdapter
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                projectNames
        );

        // Apply the adapter to the AutoCompleteTextView and set the default text
        if (projectDropdown != null && !projectNames.isEmpty()) {
            projectDropdown.setAdapter(adapter);
            // Only set text if the dropdown is visible (i.e., not configured)
            if(projectDropdownContainer.getVisibility() == View.VISIBLE) {
                projectDropdown.setText(projectNames.get(0), false);
            }
        }
    }

    public void processAllProject(final String url) {
        // Check if already configured before making API call
        if (getSavedProjectName() != null) {
            Log.d("OKHTTP", "Project already configured. Skipping project list API call.");
            return;
        }

        // Ensure client is initialized
        if (okHttpClient == null) {
            Log.e("OKHTTP", "OkHttpClient is null. Cannot proceed.");
            return;
        }

        String finalUrl = Uri.parse(base_url + url).toString();
        Log.d("OKHTTP", "Final URL: " + finalUrl);

        // Replaced Volley POST without body with OkHttp POST with an empty FormBody
        RequestBody requestBody = new FormBody.Builder().build();

        Request request = new Request.Builder()
                .url(finalUrl)
                .post(requestBody)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("OKHTTP", "OkHttp Error: " + e.getMessage());
                // Handle error on the main thread
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> populateSpinner("[]"));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e("OKHTTP", "Unsuccessful response code: " + response.code());
                    // Treat as failure
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> populateSpinner("[]"));
                    }
                    // Close response body on failure
                    if (response.body() != null) {
                        response.body().close();
                    }
                    return;
                }

                try (ResponseBody responseBody = response.body()) {
                    if (responseBody == null) {
                        Log.e("OKHTTP", "Response body is null.");
                        if (isAdded()) {
                            requireActivity().runOnUiThread(() -> populateSpinner("[]"));
                        }
                        return;
                    }
                    final String responseData = responseBody.string();
                    Log.d("OKHTTP", "Response: " + responseData);

                    // Update UI on the main thread
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> populateSpinner(responseData));
                    }
                }
            }
        });
    }

    private void saveProjectFolder(final String url, String id) {
        String path = url;
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        String finalUrl = base_url + "/" + path;
        Log.d("OKHTTP", "Attempting Final Folder URL: " + finalUrl);

        // Ensure client is initialized
        if (okHttpClient == null) {
            Log.e("OKHTTP", "OkHttpClient is null. Cannot proceed.");
            hideLoadingState("CONFIG");
            if (isAdded()) {
                Toast.makeText(requireContext(), "Internal error: OkHttpClient not initialized.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        // Volley's getParams() maps directly to OkHttp's FormBody for POST
        RequestBody formBody = new FormBody.Builder()
                .add("project_id", id)
                .build();

        Request request = new Request.Builder()
                .url(finalUrl)
                .post(formBody)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("OKHTTP", "OkHttp Error in Folder API: " + e.getMessage());
                if (isAdded()) {
                    // Update UI on the main thread
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(requireContext(), "Failed to retrieve folder details from server.", Toast.LENGTH_LONG).show();
                        hideLoadingState("CONFIG");
                    });
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                // CRITICAL CRASH FIX: Check for fragment attachment
                if (!isAdded()) {
                    Log.w("OKHTTP", "Fragment detached. Skipping folder save on response.");
                    return;
                }

                if (!response.isSuccessful()) {
                    Log.e("OKHTTP", "Unsuccessful folder response code: " + response.code());
                    if (isAdded()) {
                        // Update UI on the main thread
                        requireActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(), "Server error retrieving folder details.", Toast.LENGTH_LONG).show();
                            hideLoadingState("CONFIG");
                        });
                    }
                    // Close response body on failure
                    if (response.body() != null) {
                        response.body().close();
                    }
                    return;
                }

                String responseData;
                try (ResponseBody responseBody = response.body()) {
                    if (responseBody == null) {
                        Log.e("OKHTTP", "Folder Response body is null.");
                        if (isAdded()) {
                            requireActivity().runOnUiThread(() -> {
                                Toast.makeText(requireContext(), "Empty response from server.", Toast.LENGTH_LONG).show();
                                hideLoadingState("CONFIG");
                            });
                        }
                        return;
                    }
                    responseData = responseBody.string();
                    Log.d("OKHTTP", "Response in Folder: " + responseData);
                }

                // Process JSON and update UI on the main thread
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        try {
                            JSONArray jsonArray = new JSONArray(responseData);

                            SharedPreferences sharedPref = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                            SharedPreferences.Editor editor = sharedPref.edit();
                            boolean foundCamera = false;

                            // Iterate to find the "camera 1" folder ID
                            for (int i = 0; i < jsonArray.length(); i++) {
                                JSONObject folder = jsonArray.getJSONObject(i);
                                String folderName = folder.getString("folder_name");

                                if (CON_CAMERA.equalsIgnoreCase(folderName)) {
                                    String folderId = folder.getString("id");
                                    editor.putString(KEY_CAMERA_1_ID, folderId);
                                    editor.apply();
                                    Log.d("CAMERA_SAVE", "Found '"+CON_CAMERA+"' folder. ID saved: " + folderId + " to " + KEY_CAMERA_1_ID);
                                    foundCamera = true;
                                    break; // Stop looping once found
                                }
                            }

                            // After API call, check if configuration is complete and update UI
                            if (foundCamera) {
                                Toast.makeText(requireContext(), "Project configured successfully!", Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(requireContext(), "Error: '"+CON_CAMERA+"' folder ID not found.", Toast.LENGTH_LONG).show();
                            }

                            // Always hide loading state on completion of the second API call
                            hideLoadingState("CONFIG");

                            // This call now relies on KEY_CAMERA_1_ID being set
                            checkAndSetUI();

                        } catch (JSONException e) {
                            Log.e("OKHTTP", "Folder JSON Parsing Error: " + e.getMessage());
                            Toast.makeText(requireContext(), "Error parsing folder details.", Toast.LENGTH_LONG).show();
                            // Hide loading state on JSON error
                            hideLoadingState("CONFIG");
                        }
                    });
                }
            }
        });
    }

}
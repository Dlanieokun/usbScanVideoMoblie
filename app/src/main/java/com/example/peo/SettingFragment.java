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

public class SettingFragment extends Fragment {

    final String base_url = "http://apps.leyteprovince.gov.ph:70/gov_peo/index.php/";

    private AutoCompleteTextView projectDropdown;
    private MaterialButton configButton;
    private TextView savedProjectTextView;
    private TextInputLayout projectDropdownContainer;
    private ProgressBar configProgressBar;

    private List<ProjectItem> projectList = new ArrayList<>();

    private static final String PREF_NAME = "ProjectSettings";
    private static final String KEY_PROJECT_NAME = "selected_project_name";
    private static final String KEY_PROJECT_ID = "selected_project_id";
    private static final String KEY_CAMERA_1_ID = "camera1ID";
    private static final String CON_CAMERA = "camera 1";

    private static final String APP_PREFS_FILE = "app_local_data";
    private static final String PROJECT_NAME_KEY = "name_of_project";

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
        okHttpClient = new OkHttpClient();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setting, container, false);

        projectDropdown = view.findViewById(R.id.projectAutoCompleteTextView);
        configButton = view.findViewById(R.id.configButton);
        savedProjectTextView = view.findViewById(R.id.savedProjectTextView);
        projectDropdownContainer = view.findViewById(R.id.project_dropdown_container);
        configProgressBar = view.findViewById(R.id.configProgressBar);

        configButton.setOnClickListener(v -> saveSelectedProject());

        checkAndSetUI();

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
            projectDropdownContainer.setVisibility(View.GONE);
            configButton.setVisibility(View.GONE);
            configProgressBar.setVisibility(View.GONE);
            savedProjectTextView.setVisibility(View.VISIBLE);
            savedProjectTextView.setText("✅ Project configured: \n" + savedProject);
        } else {
            projectDropdownContainer.setVisibility(View.VISIBLE);
            configButton.setVisibility(View.VISIBLE);
            configProgressBar.setVisibility(View.GONE);
            savedProjectTextView.setVisibility(View.GONE);
            if (projectList.isEmpty() || projectDropdown.getAdapter() == null) {
                processAllProject("/Api/listOfProjects");
            }
        }
    }

    private String getSavedProjectName() {
        if (!isAdded()) return null;
        SharedPreferences sharedPref = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        String projectName = sharedPref.getString(KEY_PROJECT_NAME, null);
        String projectId = sharedPref.getString(KEY_PROJECT_ID, null);
        String camera1Id = sharedPref.getString(KEY_CAMERA_1_ID, null);

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
        if (!isAdded()) return;

        String selectedProjectName = projectDropdown.getText().toString();

        if (selectedProjectName.equals("--- Choose a Project ---") || selectedProjectName.isEmpty()) {
            Toast.makeText(requireContext(), "Please select a valid project first.", Toast.LENGTH_SHORT).show();
            return;
        }

        String selectedProjectId = null;
        for (ProjectItem item : projectList) {
            if (item.name.equals(selectedProjectName)) {
                selectedProjectId = item.id;
                break;
            }
        }

        if (selectedProjectId != null) {
            SharedPreferences sharedPref = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPref.edit();

            editor.putString(KEY_PROJECT_NAME, selectedProjectName);
            editor.putString(KEY_PROJECT_ID, selectedProjectId);

            editor.remove(KEY_CAMERA_1_ID);

            SharedPreferences appSharedPrefs = requireContext().getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE);
            appSharedPrefs.edit().putString(PROJECT_NAME_KEY, selectedProjectName).apply();

            if (editor.commit()) {
                showLoadingState();

                saveProjectFolder("/Api/listOfProjectFolders", selectedProjectId);
                Toast.makeText(requireContext(), "Project Selected. Retrieving folder details...", Toast.LENGTH_LONG).show();

            } else {
                Toast.makeText(requireContext(), "Failed to save project settings locally.", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(requireContext(), "Error: Project '" + selectedProjectName + "' not found in the list.", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Clears all project configuration keys from SharedPreferences,
     * effectively resetting the fragment to allow a new selection.
     */
    private void clearSavedProject() {
        if (!isAdded()) return;

        SharedPreferences sharedPref = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();

        editor.remove(KEY_PROJECT_NAME);
        editor.remove(KEY_PROJECT_ID);
        editor.remove(KEY_CAMERA_1_ID);

        SharedPreferences appSharedPrefs = requireContext().getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE);
        appSharedPrefs.edit().remove(PROJECT_NAME_KEY).apply();

        if (editor.commit()) {
            Toast.makeText(requireContext(), "Configuration reset. Please select a new project.", Toast.LENGTH_LONG).show();
            checkAndSetUI();
        } else {
            Toast.makeText(requireContext(), "Failed to clear project configuration.", Toast.LENGTH_SHORT).show();
        }
    }


    /**
     * Parses the JSON array response and populates the AutoCompleteTextView.
     */
    private void populateSpinner(String response) {
        if (!isAdded()) {
            Log.w("OKHTTP", "Fragment not attached. Skipping UI update in populateSpinner.");
            return;
        }

        if (getSavedProjectName() != null) {
            Log.d("OKHTTP", "Project already configured. Skipping dropdown population.");
            return;
        }

        List<String> projectNames = new ArrayList<>();
        projectList.clear();

        try {
            JSONArray dataArray = new JSONArray(response);

            String placeholder = "--- Choose a Project ---";
            projectNames.add(placeholder);

            for (int i = 0; i < dataArray.length(); i++) {
                JSONObject project = dataArray.getJSONObject(i);

                String projectId = project.getString("id");
                String projectName = project.getString("name_of_project");

                projectNames.add(projectName);

                projectList.add(new ProjectItem(projectId, projectName));
            }
        } catch (JSONException e) {
            Log.e("OKHTTP", "JSON Parsing Error: " + e.getMessage());
            projectNames.add("Error loading projects");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                projectNames
        );

        if (projectDropdown != null && !projectNames.isEmpty()) {
            projectDropdown.setAdapter(adapter);
            projectDropdown.setText(projectNames.get(0), false);
        }
    }

    public void processAllProject(final String url) {
        if (getSavedProjectName() != null) {
            Log.d("OKHTTP", "Project already configured. Skipping project list API call.");
            return;
        }

        if (okHttpClient == null) {
            Log.e("OKHTTP", "OkHttpClient is null. Cannot proceed.");
            return;
        }

        String finalUrl = Uri.parse(base_url + url).toString();
        Log.d("OKHTTP", "Final URL: " + finalUrl);

        RequestBody requestBody = new FormBody.Builder().build();

        Request request = new Request.Builder()
                .url(finalUrl)
                .post(requestBody)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("OKHTTP", "OkHttp Error: " + e.getMessage());
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> populateSpinner("[]"));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e("OKHTTP", "Unsuccessful response code: " + response.code());
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> populateSpinner("[]"));
                    }
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

        if (okHttpClient == null) {
            Log.e("OKHTTP", "OkHttpClient is null. Cannot proceed.");
            hideLoadingState("CONFIG");
            if (isAdded()) {
                Toast.makeText(requireContext(), "Internal error: OkHttpClient not initialized.", Toast.LENGTH_LONG).show();
            }
            return;
        }

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
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(requireContext(), "Failed to retrieve folder details from server.", Toast.LENGTH_LONG).show();
                        hideLoadingState("CONFIG");
                    });
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!isAdded()) {
                    Log.w("OKHTTP", "Fragment detached. Skipping folder save on response.");
                    return;
                }

                if (!response.isSuccessful()) {
                    Log.e("OKHTTP", "Unsuccessful folder response code: " + response.code());
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(), "Server error retrieving folder details.", Toast.LENGTH_LONG).show();
                            hideLoadingState("CONFIG");
                        });
                    }
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

                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        try {
                            JSONArray jsonArray = new JSONArray(responseData);

                            SharedPreferences sharedPref = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                            SharedPreferences.Editor editor = sharedPref.edit();
                            boolean foundCamera = false;

                            for (int i = 0; i < jsonArray.length(); i++) {
                                JSONObject folder = jsonArray.getJSONObject(i);
                                String folderName = folder.getString("folder_name");

                                if (CON_CAMERA.equalsIgnoreCase(folderName)) {
                                    String folderId = folder.getString("id");
                                    editor.putString(KEY_CAMERA_1_ID, folderId);
                                    editor.apply();
                                    Log.d("CAMERA_SAVE", "Found '"+CON_CAMERA+"' folder. ID saved: " + folderId + " to " + KEY_CAMERA_1_ID);
                                    foundCamera = true;
                                    break;
                                }
                            }

                            if (foundCamera) {
                                Toast.makeText(requireContext(), "Project configured successfully!", Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(requireContext(), "Error: '"+CON_CAMERA+"' folder ID not found.", Toast.LENGTH_LONG).show();
                            }

                            hideLoadingState("CONFIG");

                            checkAndSetUI();

                        } catch (JSONException e) {
                            Log.e("OKHTTP", "Folder JSON Parsing Error: " + e.getMessage());
                            Toast.makeText(requireContext(), "Error parsing folder details.", Toast.LENGTH_LONG).show();
                            hideLoadingState("CONFIG");
                        }
                    });
                }
            }
        });
    }

}
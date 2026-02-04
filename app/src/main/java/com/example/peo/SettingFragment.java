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
import com.google.android.material.textfield.TextInputEditText;
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

    // Dynamic Base URL stored in SharedPreferences
    private String currentBaseUrl;

    private AutoCompleteTextView projectDropdown;
    private TextInputEditText apiUrlEditText;
    private MaterialButton configButton, testConnectionButton;
    private TextView savedProjectTextView;
    private TextInputLayout projectDropdownContainer;
    private ProgressBar configProgressBar;

    private List<ProjectItem> projectList = new ArrayList<>();

    // Shared Preferences Constants
    private static final String PREF_NAME = "ProjectSettings";
    private static final String KEY_PROJECT_NAME = "selected_project_name";
    private static final String KEY_PROJECT_ID = "selected_project_id";
    private static final String KEY_CAMERA_1_ID = "camera3ID";
    private static final String KEY_BASE_URL = "custom_base_url";
    private static final String CON_CAMERA = "camera 3";

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
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setting, container, false);

        // Initialize UI Elements
        apiUrlEditText = view.findViewById(R.id.apiUrlEditText);
        testConnectionButton = view.findViewById(R.id.testConnectionButton);
        projectDropdown = view.findViewById(R.id.projectAutoCompleteTextView);
        configButton = view.findViewById(R.id.configButton);
        savedProjectTextView = view.findViewById(R.id.savedProjectTextView);
        projectDropdownContainer = view.findViewById(R.id.project_dropdown_container);
        configProgressBar = view.findViewById(R.id.configProgressBar);

        // Load existing Base URL or default
        SharedPreferences sharedPref = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        currentBaseUrl = sharedPref.getString(KEY_BASE_URL, "http://apps.leyteprovince.gov.ph:70/gov_peo/index.php/");
        apiUrlEditText.setText(currentBaseUrl);

        // Listeners
        testConnectionButton.setOnClickListener(v -> testConnection());
        configButton.setOnClickListener(v -> saveSelectedProject());

        checkAndSetUI();

        return view;
    }

    /**
     * Saves the URL from the EditText to SharedPreferences.
     */
    private void saveApiUrl() {
        String url = apiUrlEditText.getText().toString().trim();
        if (!url.isEmpty() && !url.endsWith("/")) url += "/";
        currentBaseUrl = url;
        requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_BASE_URL, url).apply();
    }

    /**
     * Verifies if the API URL is valid and fetches the project list.
     */
    private void testConnection() {
        saveApiUrl();
        showLoadingState("Testing...");

        Request request = new Request.Builder()
                .url(currentBaseUrl + "Api/listOfProjects")
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        hideLoadingState("CONFIG");
                        projectDropdownContainer.setVisibility(View.GONE);
                        configButton.setVisibility(View.GONE);
                        Toast.makeText(requireContext(), "Connection Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        hideLoadingState("CONFIG");
                        if (response.isSuccessful()) {
                            Toast.makeText(requireContext(), "Success! Projects loaded.", Toast.LENGTH_SHORT).show();
                            projectDropdownContainer.setVisibility(View.VISIBLE);
                            configButton.setVisibility(View.VISIBLE);
                            processAllProject("Api/listOfProjects");
                        } else {
                            projectDropdownContainer.setVisibility(View.GONE);
                            configButton.setVisibility(View.GONE);
                            Toast.makeText(requireContext(), "Server Error: " + response.code(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    private void checkAndSetUI() {
        if (!isAdded()) return;
        SharedPreferences sharedPref = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedProject = sharedPref.getString(KEY_PROJECT_NAME, null);

        if (savedProject != null && !savedProject.isEmpty()) {
            projectDropdownContainer.setVisibility(View.GONE);
            configButton.setVisibility(View.GONE);
            savedProjectTextView.setVisibility(View.VISIBLE);
            savedProjectTextView.setText("✅ Project configured: \n" + savedProject);
        } else {
            projectDropdownContainer.setVisibility(View.GONE); // Hide until Test Connection succeeds
            configButton.setVisibility(View.GONE);
            savedProjectTextView.setVisibility(View.GONE);
        }
    }

    private void saveSelectedProject() {
        String selectedProjectName = projectDropdown.getText().toString();
        if (selectedProjectName.isEmpty() || selectedProjectName.equals("--- Choose a Project ---")) {
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
            sharedPref.edit().putString(KEY_PROJECT_NAME, selectedProjectName)
                    .putString(KEY_PROJECT_ID, selectedProjectId).apply();

            SharedPreferences appSharedPrefs = requireContext().getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE);
            appSharedPrefs.edit().putString(PROJECT_NAME_KEY, selectedProjectName).apply();

            showLoadingState("Saving...");
            saveProjectFolder("Api/listOfProjectFolders", selectedProjectId);
        }
    }

    private void processAllProject(String endpoint) {
        String finalUrl = currentBaseUrl + (endpoint.startsWith("/") ? endpoint.substring(1) : endpoint);
        Request request = new Request.Builder().url(finalUrl).post(new FormBody.Builder().build()).build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {}
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (response.isSuccessful() && responseBody != null) {
                        String data = responseBody.string();
                        if (isAdded()) requireActivity().runOnUiThread(() -> populateSpinner(data));
                    }
                }
            }
        });
    }

    private void populateSpinner(String response) {
        if (!isAdded()) return;
        List<String> projectNames = new ArrayList<>();
        projectList.clear();
        try {
            JSONArray dataArray = new JSONArray(response);
            projectNames.add("--- Choose a Project ---");
            for (int i = 0; i < dataArray.length(); i++) {
                JSONObject project = dataArray.getJSONObject(i);
                projectList.add(new ProjectItem(project.getString("id"), project.getString("name_of_project")));
                projectNames.add(project.getString("name_of_project"));
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, projectNames);
            projectDropdown.setAdapter(adapter);
            projectDropdown.setText(projectNames.get(0), false);
        } catch (JSONException e) { e.printStackTrace(); }
    }

    private void saveProjectFolder(String endpoint, String id) {
        String finalUrl = currentBaseUrl + (endpoint.startsWith("/") ? endpoint.substring(1) : endpoint);
        RequestBody body = new FormBody.Builder().add("project_id", id).build();
        Request request = new Request.Builder().url(finalUrl).post(body).build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (isAdded()) requireActivity().runOnUiThread(() -> hideLoadingState("CONFIG"));
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (response.isSuccessful() && responseBody != null) {
                        String data = responseBody.string();
                        if (isAdded()) requireActivity().runOnUiThread(() -> handleFolderResponse(data));
                    }
                }
            }
        });
    }

    private void handleFolderResponse(String responseData) {
        try {
            JSONArray jsonArray = new JSONArray(responseData);
            SharedPreferences sharedPref = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            boolean found = false;
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject folder = jsonArray.getJSONObject(i);
                if (CON_CAMERA.equalsIgnoreCase(folder.getString("folder_name"))) {
                    sharedPref.edit().putString(KEY_CAMERA_1_ID, folder.getString("id")).apply();
                    found = true;
                    break;
                }
            }
            Toast.makeText(requireContext(), found ? "Project configured successfully!" : "Folder 'camera 4' not found.", Toast.LENGTH_LONG).show();
            hideLoadingState("CONFIG");
            checkAndSetUI();
        } catch (JSONException e) { hideLoadingState("CONFIG"); }
    }

    private void showLoadingState(String message) {
        configButton.setText(message);
        configButton.setEnabled(false);
        configProgressBar.setVisibility(View.VISIBLE);
    }

    private void hideLoadingState(String text) {
        configButton.setText(text);
        configButton.setEnabled(true);
        configProgressBar.setVisibility(View.GONE);
    }
}
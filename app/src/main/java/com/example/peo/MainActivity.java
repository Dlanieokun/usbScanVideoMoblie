package com.example.peo;

import android.os.Bundle;
import android.content.Context; // Required for SharedPreferences Context
import android.content.SharedPreferences; // Required for SharedPreferences
import android.content.DialogInterface;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    // IMPORTANT: Made public for fragments to access during redirection
    public BottomNavigationView bottomNavigationView;

    // Define constants for SharedPreferences
    private static final String APP_PREFS_FILE = "app_local_data";
    private static final String PROJECT_NAME_KEY = "name_of_project";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        bottomNavigationView = findViewById(R.id.bottomNavigation);

        // 1. Check for 'name_of_project' on app start
        SharedPreferences sharedPrefs = getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE);

        // If 'name_of_project' key is NOT present, load SettingFragment
        if (!sharedPrefs.contains(PROJECT_NAME_KEY)) {
            loadFragment(new SettingFragment());
            bottomNavigationView.setSelectedItemId(R.id.setting);
        } else {
            // Otherwise, load HomeFragment (default)
            loadFragment(new HomeFragment());
            bottomNavigationView.setSelectedItemId(R.id.home);
        }

        bottomNavigationView.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;

            int itemId = item.getItemId();

            if (itemId == R.id.home) {
                selectedFragment = new HomeFragment();
            } else if (itemId == R.id.setting) {
                selectedFragment = new SettingFragment();
            }

            if (selectedFragment != null) {
                loadFragment(selectedFragment);
            }

            return true;
        });
    }

    /**
     * Helper method to load a fragment into the container.
     * Made public so fragments can request navigation (e.g., HomeFragment redirecting to SettingFragment).
     */
    public void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }

    @Override
    public void onBackPressed() {
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragmentContainer);

        // 1. If the current fragment is HomeFragment, show the exit dialog
        if (currentFragment instanceof HomeFragment) {
            new AlertDialog.Builder(this)
                    .setTitle("Exit Application")
                    .setMessage("Are you sure you want to exit the application?")
                    .setPositiveButton("Yes", (dialog, which) -> {
                        finish();
                    })
                    .setNegativeButton("No", (dialog, which) -> {
                        dialog.dismiss();
                    })
                    .show();
        }

        // 2. If on any other fragment, navigate back to HomeFragment
        else {
            bottomNavigationView.setSelectedItemId(R.id.home);
            loadFragment(new HomeFragment());
        }
    }
}
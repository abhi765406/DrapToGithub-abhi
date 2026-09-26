package com.ziptool.uploader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int PICK_ZIP_REQUEST = 200;
    private static final String PREFS = "prefs";

    private EditText tokenInput, ownerInput, repoInput, messageInput;
    private CheckBox createRepoCheckbox, stripTopFolderCheckbox;
    private RadioButton privateRadio;
    private Button chooseZipButton, uploadButton, openRepoButton;
    private TextView selectedFileText, logText;
    private ProgressBar progressBar;

    private Uri selectedZipUri;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String lastRepoUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tokenInput = findViewById(R.id.tokenInput);
        ownerInput = findViewById(R.id.ownerInput);
        repoInput = findViewById(R.id.repoInput);
        messageInput = findViewById(R.id.messageInput);
        createRepoCheckbox = findViewById(R.id.createRepoCheckbox);
        stripTopFolderCheckbox = findViewById(R.id.stripTopFolderCheckbox);
        privateRadio = findViewById(R.id.privateRadio);
        chooseZipButton = findViewById(R.id.chooseZipButton);
        uploadButton = findViewById(R.id.uploadButton);
        openRepoButton = findViewById(R.id.openRepoButton);
        selectedFileText = findViewById(R.id.selectedFileText);
        logText = findViewById(R.id.logText);
        progressBar = findViewById(R.id.progressBar);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        tokenInput.setText(prefs.getString("token", ""));
        ownerInput.setText(prefs.getString("owner", ""));

        chooseZipButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(Intent.createChooser(intent, "Choose a zip file"), PICK_ZIP_REQUEST);
        });

        uploadButton.setOnClickListener(v -> startUpload());

        openRepoButton.setOnClickListener(v -> {
            if (lastRepoUrl != null) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(lastRepoUrl)));
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_ZIP_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            selectedZipUri = data.getData();
            String name = queryDisplayName(selectedZipUri);
            selectedFileText.setText(name != null ? name : "1 file selected");
        }
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) return null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void startUpload() {
        String token = tokenInput.getText().toString().trim();
        String owner = ownerInput.getText().toString().trim();
        String repo = repoInput.getText().toString().trim();
        String message = messageInput.getText().toString().trim();

        if (token.isEmpty() || owner.isEmpty() || repo.isEmpty()) {
            Toast.makeText(this, "Fill in the token, username and repository name", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedZipUri == null) {
            Toast.makeText(this, "Choose a zip file first", Toast.LENGTH_SHORT).show();
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("token", token)
                .putString("owner", owner)
                .apply();

        boolean createIfMissing = createRepoCheckbox.isChecked();
        boolean makePrivate = privateRadio.isChecked();
        boolean stripTopFolder = stripTopFolderCheckbox.isChecked();

        logText.setText("");
        progressBar.setProgress(0);
        openRepoButton.setVisibility(android.view.View.GONE);
        uploadButton.setEnabled(false);
        chooseZipButton.setEnabled(false);

        GitHubUploader.Listener listener = new GitHubUploader.Listener() {
            @Override
            public void onLog(String line) {
                appendLog(line);
            }

            @Override
            public void onProgress(int done, int total) {
                int percent = total > 0 ? (int) ((done * 100L) / total) : 0;
                progressBar.setProgress(percent);
            }

            @Override
            public void onFinished(boolean success, String repoUrl, int uploaded, int failed) {
                uploadButton.setEnabled(true);
                chooseZipButton.setEnabled(true);
                if (repoUrl != null) {
                    lastRepoUrl = repoUrl;
                    openRepoButton.setVisibility(android.view.View.VISIBLE);
                    appendLog("Done. Uploaded " + uploaded + " file(s)" + (failed > 0 ? (", " + failed + " failed") : "") + ".");
                    Toast.makeText(MainActivity.this,
                            success ? "Upload complete" : "Upload finished with some errors",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "Upload failed - see the log", Toast.LENGTH_LONG).show();
                }
            }
        };

        executor.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(selectedZipUri)) {
                if (is == null) throw new Exception("Could not open the selected file");
                GitHubUploader uploader = new GitHubUploader(
                        token, owner, repo, message, createIfMissing, makePrivate, stripTopFolder, listener);
                uploader.run(is);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendLog("Failed to read the zip file: " + e.getMessage());
                    uploadButton.setEnabled(true);
                    chooseZipButton.setEnabled(true);
                });
            }
        });
    }

    private void appendLog(String line) {
        logText.append(line + "\n");
    }
}

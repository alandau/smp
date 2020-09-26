package landau.smp;

import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListAdapter;
import android.widget.ListView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SuppressWarnings("WeakerAccess")
public class SMPOpenActivity extends ListActivity {
    private static final String rootPath = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator;
    private SharedPreferences prefs;
    private File curFolder;

    private class FileData {
        public File path;
        public String name;
        public File playPath;
        public boolean isDir;

        public FileData(File path) {
            this.path = path;
            this.name = path.getName();
            this.playPath = path;
            this.isDir = path.isDirectory();
        }

        public FileData(File path, String name, File playPath) {
            this.path = path;
            this.name = name;
            this.playPath = playPath;
            this.isDir = true;
        }

        public String toString() {
            return name;
        }
    }

    private class CustomAdapter extends ArrayAdapter<FileData> {
        public CustomAdapter(Context context, List<FileData> items) {
            super(context, R.layout.open_item, R.id.filename, items);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View row = super.getView(position, convertView, parent);
            final FileData fileData = getItem(position);
            assert fileData != null;

            ImageButton button = row.findViewById(R.id.play_button);
            // Button must be non-focusable or clicks on the row will be ignored.
            // Also, setting android:focusable="false" in the layout doesn't work.
            button.setFocusable(false);
            button.setOnClickListener(v -> {
                prefs.edit()
                        .putString("state_lastShowFolder", curFolder.getAbsolutePath())
                        .putString("state_lastPlayFolder", fileData.playPath.getAbsolutePath())
                        .apply();
                Intent data = new Intent();
                data.putExtra("path", fileData.playPath.getAbsolutePath());
                setResult(RESULT_OK, data);
                finish();
            });

            return row;
        }

        @Override
        public boolean isEnabled(int position) {
            return ((FileData)getListView().getItemAtPosition(position)).isDir;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String fallback = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath();
        loadFolder(new File(prefs.getString("state_lastShowFolder", fallback)));
    }

    private void loadFolder(File path) {
        while (!path.isDirectory() && path.getParentFile() != null) {
            path = path.getParentFile();
        }
        curFolder = path;

        String pathStr = path.getAbsolutePath();
        if (pathStr.startsWith(rootPath)) {
            pathStr = pathStr.substring(rootPath.length());
        }
        setTitle(pathStr);

        ArrayList<FileData> items = new ArrayList<>();
        for (File f : path.listFiles()) {
            items.add(new FileData(f));
        }
        Collections.sort(items, (lhs, rhs) -> lhs.toString().compareTo(rhs.toString()));
        File parent = path.getParentFile();
        if (parent != null) {
            items.add(0, new FileData(parent, "..", path));
        }
        ListAdapter adapter = new CustomAdapter(this, items);
        getListView().setAdapter(adapter);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        File path = ((FileData)getListView().getItemAtPosition(position)).path;
        loadFolder(path);
    }
}

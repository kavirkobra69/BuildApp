package com.kavir.downloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.Toast;
import java.net.URLDecoder;
import java.util.Locale;

public class MainActivity extends Activity {
    private EditText urlInput;
    private TextView status;
    private static final String PREFS = "kavir_settings";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(18, 12, 30));
        getWindow().setNavigationBarColor(Color.rgb(18, 12, 30));
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView label(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
        t.setTextColor(Color.WHITE); return t;
    }
    private Button button(String value) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(112, 57, 190)));
        return b;
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(24)); root.setBackgroundColor(Color.rgb(18,12,30));
        scroll.addView(root); setContentView(scroll);

        TextView brand = label("KAVIR", 14); brand.setTextColor(Color.rgb(190,145,255)); brand.setTypeface(null, Typeface.BOLD);
        root.addView(brand);
        TextView title = label("Downloader", 30); title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1,-2); titleParams.topMargin=dp(5); root.addView(title,titleParams);
        TextView subtitle = label("Videos • Music links • Photos • GIFs", 14); subtitle.setTextColor(Color.LTGRAY);
        root.addView(subtitle);

        TextView section = label("Paste a media link", 17); section.setTypeface(null,Typeface.BOLD);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1,-2); sp.topMargin=dp(30); root.addView(section,sp);
        urlInput = new EditText(this); urlInput.setSingleLine(false); urlInput.setMinLines(2); urlInput.setTextSize(15);
        urlInput.setHint("https://..."); urlInput.setHintTextColor(Color.GRAY); urlInput.setTextColor(Color.WHITE);
        urlInput.setPadding(dp(14),dp(12),dp(14),dp(12)); urlInput.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(135,95,190)));
        root.addView(urlInput,new LinearLayout.LayoutParams(-1,-2));

        Button inspect = button("Check link"); LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1,-2); bp.topMargin=dp(14); root.addView(inspect,bp);
        Button download = button("Download & save"); LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(-1,-2); dp2.topMargin=dp(8); root.addView(download,dp2);
        status = label("Paste a direct downloadable media URL to begin.",14); status.setTextColor(Color.LTGRAY);
        LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(-1,-2); st.topMargin=dp(18); root.addView(status,st);
        View divider = new View(this); divider.setBackgroundColor(Color.rgb(65,48,82));
        LinearLayout.LayoutParams dv = new LinearLayout.LayoutParams(-1,dp(1)); dv.topMargin=dp(26); dv.bottomMargin=dp(18); root.addView(divider,dv);
        Button settings = button("Settings · Proxy configuration"); root.addView(settings,new LinearLayout.LayoutParams(-1,-2));
        TextView note = label("Downloads are saved in your phone's Downloads folder. Platform pages are not direct media files; use content you own or are authorized to download.",12);
        note.setTextColor(Color.LTGRAY); LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1,-2); np.topMargin=dp(20); root.addView(note,np);

        inspect.setOnClickListener(v -> inspectLink());
        download.setOnClickListener(v -> downloadDirect());
        settings.setOnClickListener(v -> showProxySettings());
    }

    private String provider(String host) {
        host=host.toLowerCase(Locale.ROOT);
        if(host.contains("youtube.com") || host.equals("youtu.be") || host.endsWith(".youtube.com")) return "YouTube";
        if(host.contains("instagram.com")) return "Instagram";
        if(host.contains("tiktok.com")) return "TikTok";
        if(host.contains("spotify.com")) return "Spotify";
        if(host.contains("pinterest.")) return "Pinterest";
        return "Direct / other host";
    }
    private Uri parsedUri() {
        String raw=urlInput.getText().toString().trim();
        if(raw.isEmpty()) throw new IllegalArgumentException("Paste a URL first.");
        Uri u=Uri.parse(raw);
        if(!("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())) || u.getHost()==null)
            throw new IllegalArgumentException("Enter a valid http:// or https:// URL.");
        return u;
    }
    private void inspectLink() {
        try {
            Uri u=parsedUri(); String p=provider(u.getHost());
            String message=p+" link detected.\n";
            if(p.equals("Spotify")) message+="Spotify tracks are protected; this app can open the official Spotify experience but cannot extract protected audio.";
            else if(!p.equals("Direct / other host")) message+="A platform page is not necessarily a direct media file. Download works when the URL points directly to an accessible media file.";
            else message+="If this URL points directly to an accessible image, GIF, audio or video file, it can be saved.";
            status.setText(message);
        } catch(Exception e) { status.setText(e.getMessage()); }
    }
    private void downloadDirect() {
        try {
            Uri u=parsedUri(); String p=provider(u.getHost());
            if(!p.equals("Direct / other host")) {
                status.setText("This is a "+p+" page, not a direct media URL. Platform extraction is not enabled in this starter version.");
                return;
            }
            String path=u.getLastPathSegment(); if(path==null || !path.contains(".")) path="kavir_media_"+System.currentTimeMillis();
            path=URLDecoder.decode(path,"UTF-8").replaceAll("[^a-zA-Z0-9._-]","_");
            DownloadManager.Request req=new DownloadManager.Request(u);
            req.setTitle(path); req.setDescription("Kavir Downloader");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"KavirDownloader/"+path);
            req.setAllowedOverMetered(true); req.setAllowedOverRoaming(false);
            DownloadManager dm=(DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(req);
            status.setText("Download added. Check the Android notification and Downloads folder.");
        } catch(Exception e) { status.setText("Could not start download: "+e.getMessage()); }
    }
    private void showProxySettings() {
        EditText config=new EditText(this); config.setMinLines(5); config.setGravity(Gravity.TOP);
        config.setHint("Paste VLESS / VMess / Trojan config here"); config.setTextColor(Color.WHITE);
        config.setHintTextColor(Color.GRAY); config.setText(getSharedPreferences(PREFS,MODE_PRIVATE).getString("proxy_config",""));
        new AlertDialog.Builder(this).setTitle("Proxy configuration")
            .setMessage("Config storage is prepared, but proxy routing is not active in this starter build. Do not rely on this setting to bypass network blocks yet.")
            .setView(config)
            .setPositiveButton("Save", (d,w)->{
                getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("proxy_config",config.getText().toString().trim()).apply();
                Toast.makeText(this,"Config saved locally (not connected).",Toast.LENGTH_LONG).show();
            }).setNegativeButton("Cancel",null).show();
    }
}

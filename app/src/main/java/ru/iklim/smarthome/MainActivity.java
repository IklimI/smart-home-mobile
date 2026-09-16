package ru.iklim.smarthome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout page, devices;
    private TextView status;
    private SharedPreferences prefs;
    private boolean busy;
    private int generation;
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", 0);
        home();
    }
    private void layout(String title) {
        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(24));
        page.setBackgroundColor(Color.rgb(16,23,34)); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll); label(page, title, 28);
    }
    private TextView label(LinearLayout parent, String text, int size) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(size); t.setTextColor(Color.rgb(231,240,255));
        t.setPadding(0, dp(10), 0, dp(10)); parent.addView(t); return t;
    }
    private Button button(LinearLayout parent, String title, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false);
        parent.addView(b); b.setOnClickListener(v -> action.run()); return b;
    }
    private EditText field(String title, String value, boolean secret) {
        label(page, title, 16); EditText f = new EditText(this); f.setSingleLine(true);
        f.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD :
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        f.setText(value); f.setSaveEnabled(false); page.addView(f); return f;
    }
    private void home() {
        generation++; layout("Мой дом");
        status = label(page, "Подключение ещё не проверено", 16);
        button(page, "Настройки подключения", this::settings);
        button(page, "Обновить устройства", this::refresh);
        button(page, "Камера — открыть в VLC", this::camera);
        button(page, "Панель Home Assistant", () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(validateBase(prefs.getString("url", ""))))); }
            catch (Exception e) { notice("Укажи адрес Home Assistant в настройках"); }
        });
        devices = new LinearLayout(this); devices.setOrientation(LinearLayout.VERTICAL); page.addView(devices);
        if (!prefs.getString("url", "").isEmpty()) refresh();
        else status.setText("Введи адрес сервера и токен в настройках");
    }
    private void settings() {
        if (busy) { notice("Дождись завершения запроса"); return; }
        generation++; layout("Подключение");
        EditText url = field("Адрес Home Assistant", prefs.getString("url", ""), false);
        url.setHint("http://homeassistant.local:8123");
        EditText token = field("Токен HA — пустое поле сохраняет прежний", "", true);
        EditText rtsp = field("Адрес камеры без логина и пароля", prefs.getString("camera", ""), false);
        rtsp.setHint("rtsp://192.168.1.50:554/");
        label(page, "HTTP используй только в доверенной домашней сети или через VPN. Для доступа из интернета нужен HTTPS либо VPN.", 15);
        button(page, "Сохранить", () -> {
            try {
                String base = validateBase(url.getText().toString());
                String cam = rtsp.getText().toString().trim();
                if (!cam.isEmpty()) {
                    URI c = new URI(cam);
                    if (!"rtsp".equalsIgnoreCase(c.getScheme()) || c.getHost() == null || c.getRawUserInfo() != null)
                        throw new Exception("Камера: нужен rtsp:// без пароля в адресе");
                }
                String secret = token.getText().toString().trim();
                if (!secret.isEmpty()) Vault.save(this, secret);
                if (Vault.read(this).isEmpty()) throw new Exception("Введи токен Home Assistant");
                prefs.edit().putString("url", base).putString("camera", cam).apply(); home();
            } catch (Exception e) { notice(e.getMessage()); }
        });
        button(page, "Назад", this::home);
        button(page, "Удалить настройки и токен", () -> new AlertDialog.Builder(this)
                .setMessage("Удалить подключение с этого телефона?").setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d,w) -> {prefs.edit().clear().apply(); getSharedPreferences("vault",0).edit().clear().apply(); home();}).show());
    }
    static String validateBase(String input) throws Exception {
        URI u = new URI(input.trim());
        if (!("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme())) ||
                u.getHost() == null || u.getRawUserInfo() != null || u.getQuery() != null || u.getFragment() != null ||
                !(u.getPath().isEmpty() || u.getPath().equals("/")))
            throw new Exception("Укажи http(s)://сервер:порт без пути и пароля");
        return input.trim().replaceAll("/+$", "");
    }
    private String request(String base, String token, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(base + path).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(8000); c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new Exception("Нет доступа: проверь токен и права пользователя");
            if (code < 200 || code >= 300) throw new Exception("Ответ сервера HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 8*1024*1024) throw new Exception("Слишком большой ответ сервера");
                    out.write(buffer, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }
    private void refresh() { execute(null, null); }
    private void execute(String path, JSONObject body) {
        if (busy) return;
        final String base, token;
        try {base = validateBase(prefs.getString("url", "")); token = Vault.read(this);
            if(token.isEmpty()) throw new Exception("Токен не задан");}
        catch(Exception e) {notice(e.getMessage());return;}
        busy = true; final int current = generation; devices.removeAllViews();
        status.setText(path == null ? "Обновление…" : "Отправка команды…");
        worker.execute(() -> {
            try {
                if (path != null) request(base, token, path, body);
                JSONArray states = new JSONArray(request(base, token, "/api/states", null));
                runOnUiThread(() -> {busy=false; if(isFinishing() || current!=generation) return;
                    status.setText("Ответ сервера получен · " + java.text.DateFormat.getTimeInstance().format(new java.util.Date())); render(states);});
            } catch(Exception e) {
                runOnUiThread(() -> {busy=false; if(isFinishing() || current!=generation)return;
                    status.setText("Не удалось обновить состояние. Проверь сеть, адрес и токен. Команда могла выполниться — обнови состояние.");});
            }
        });
    }
    private void render(JSONArray states) {
        int count=0;
        for(int i=0;i<states.length();i++) {
            JSONObject state=states.optJSONObject(i); if(state==null)continue;
            String id=state.optString("entity_id"), domain=id.split("\\.")[0];
            if(!java.util.Arrays.asList("light","switch","sensor","binary_sensor","climate","camera","scene").contains(domain))continue;
            JSONObject attr=state.optJSONObject("attributes"); String value=state.optString("state");
            String name=attr==null?id:attr.optString("friendly_name",id);
            label(devices,name+"\n"+value+(attr==null?"":" "+attr.optString("unit_of_measurement","")),18); count++;
            boolean available=!value.equals("unavailable") && !value.equals("unknown");
            if((domain.equals("light") || domain.equals("switch")) && available && (value.equals("on") || value.equals("off"))) {
                String service=value.equals("on")?"turn_off":"turn_on";
                button(devices,service.equals("turn_on")?"Включить":"Выключить",()->new AlertDialog.Builder(this)
                        .setMessage("Изменить состояние: "+name+"?").setNegativeButton("Отмена",null)
                        .setPositiveButton("Выполнить",(d,w)->service(domain,service,id)).show());
            }
            if(domain.equals("scene") && available) button(devices,"Запустить сценарий",()->new AlertDialog.Builder(this)
                    .setMessage("Запустить: "+name+"?").setNegativeButton("Отмена",null)
                    .setPositiveButton("Запустить",(d,w)->service("scene","turn_on",id)).show());
        }
        if(count==0) label(devices,"Поддерживаемых устройств пока нет. Добавь интеграции на сервере.",16);
    }
    private void service(String domain,String service,String id) {
        try {execute("/api/services/"+domain+"/"+service,new JSONObject().put("entity_id",id));}
        catch(JSONException e) {notice("Не удалось подготовить команду");}
    }
    private void camera() {
        String uri=prefs.getString("camera","");
        if(uri.isEmpty()){notice("Укажи RTSP-адрес в настройках");return;}
        try {Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(uri)); i.setPackage("org.videolan.vlc");startActivity(i);}
        catch(android.content.ActivityNotFoundException e){notice("Установи VLC. Встроенный проигрыватель запланирован следующим этапом.");}
    }
    private void notice(String message){Toast.makeText(this,message==null?"Ошибка настройки":message,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){generation++;worker.shutdownNow();super.onDestroy();}
}

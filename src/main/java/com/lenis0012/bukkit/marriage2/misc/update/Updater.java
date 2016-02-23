package com.lenis0012.bukkit.marriage2.misc.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lenis0012.bukkit.marriage2.config.Settings;
import com.lenis0012.bukkit.marriage2.internal.MarriageCore;
import com.lenis0012.bukkit.marriage2.internal.MarriagePlugin;
import com.lenis0012.bukkit.marriage2.misc.BConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.logging.Level;

public class Updater {
    private static final long UPDATE_CACHE = TimeUnit.HOURS.toMillis(3); // Cache for 3 hours
    private static final String BASE_URL = "https://api.curseforge.com";
    private static final String API_FILES = "/servermods/files?projectIds=";

    private final int projectId;
    private final JsonParser jsonParser;
    private String currentVersion;
    private final File pluginFile;
    private boolean enabled;
    private String apiKey;

    private Version newVersion = null;
    private boolean isOutdated = false;
    private long lastUpdateCheck = 0L;
    private ItemStack changelog;

    public Updater(MarriageCore core, int projectId, File pluginFile) {
        MarriagePlugin plugin = core.getPlugin();
        this.projectId = projectId;
        this.jsonParser = new JsonParser();
        this.currentVersion = plugin.getDescription().getVersion();
        this.enabled = Settings.ENABLE_UPDATE_CHACKER.value();
        this.pluginFile = pluginFile;

        // Support for gravity updater
        File updateFile = new File(new File(plugin.getDataFolder().getParentFile(), "Updater"), "config.yml");
        if(updateFile.exists()) {
            BConfig config = new BConfig(core, updateFile);
            this.apiKey = config.get("api-key", String.class);
            this.enabled = !config.getOrDefault("disable", false);
        }
    }

    public boolean hasUpdate() {
        if(!enabled) return false;
        if(lastUpdateCheck < System.currentTimeMillis()) {
            this.lastUpdateCheck = System.currentTimeMillis() + UPDATE_CACHE;
            read();
        }

        return !isOutdated;
    }

    public ItemStack getChangelog() {
        return changelog;
    }

    public Version getNewVersion() {
        return newVersion;
    }

    public String downloadVersion() {
        if(newVersion == null) return "No new version available!";
        InputStream input = null;
        FileOutputStream output = null;
        MarriagePlugin.getInstance().getLogger().log(Level.INFO, "Downloading update " + newVersion.getName());
        try {
            Bukkit.getUpdateFolderFile().mkdir();
            URL url = new URL(newVersion.getDownloadURL());
            input = url.openStream();
            File dest = new File(Bukkit.getUpdateFolderFile(), pluginFile.getName());
            output = new FileOutputStream(dest);
            byte[] buffer = new byte[1024];
            int length;
            while((length = input.read(buffer, 0, buffer.length)) != -1) {
                output.write(buffer, 0, length);
            }

            // Don't warn players again hehehe :)
            this.currentVersion = newVersion.getName();
            this.isOutdated = false;
            readChangelog(dest); // Try to read changelog

            MarriagePlugin.getInstance().getLogger().log(Level.INFO, "Download complete");
            return null;
        } catch(IOException e) {
            MarriagePlugin.getInstance().getLogger().log(Level.WARNING, "Failed to download new file", e);
            return e.getMessage();
        } finally {
            if(input != null) {
                try {
                    input.close();
                } catch(IOException e) {
                }
            } if(output != null) {
                try {
                    output.close();
                } catch(IOException e) {
                }
            }
        }
    }

    private void read() {
        try {
            URLConnection connection = new URL(BASE_URL + API_FILES + projectId).openConnection();
            connection.addRequestProperty("User-Agent", "BukkitUpdater/v1 (by lenis0012)");
            if(apiKey != null) {
                connection.addRequestProperty("X-API-Key", apiKey);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder builder = new StringBuilder();
            String line;
            while((line = reader.readLine()) != null) {
                builder.append(line);
            }

            reader.close();
            JsonArray files = jsonParser.parse(builder.toString()).getAsJsonArray();
            JsonObject latest = files.get(files.size() - 1).getAsJsonObject();
            String name = latest.get("name").getAsString();
            ReleaseType type = ReleaseType.valueOf(latest.get("releaseType").getAsString().toUpperCase());
            String serverVersion = latest.get("gameVersion").getAsString();
            String downloadURL = latest.get("downloadUrl").getAsString();
            this.newVersion = new Version(name, type, serverVersion, downloadURL);
            this.isOutdated = !compateVersions(currentVersion, name);
        } catch(IOException e) {
            MarriagePlugin.getInstance().getLogger().log(Level.WARNING, "Failed to check for updates", e);
        }
    }

    /**
     * Read changelog from file inside of jar called changelog.json.
     *
     * @param file Jar File to read from
     */
    private void readChangelog(File file) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK, 1);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setAuthor("lenis0012");
        meta.setTitle(currentVersion + " Changelog");
        JsonObject json;

        JarFile jarFile = null;
        try {
            jarFile = new JarFile(file);
            InputStream input = jarFile.getInputStream(jarFile.getEntry("changelog.json"));
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            StringBuilder builder = new StringBuilder();
            String line;
            while((line = reader.readLine()) != null) {
                builder.append(line);
            }
            json = jsonParser.parse(builder.toString()).getAsJsonObject();
        } catch(Exception e) {
            MarriagePlugin.getInstance().getLogger().log(Level.WARNING, "Failed to read jar file", e);
            return;
        } finally {
            if(jarFile != null) {
                try {
                    jarFile.close();
                } catch(IOException e) {
                }
            }
        }

        if(!json.get("version").getAsString().equalsIgnoreCase(newVersion.getName())) {
            // Changelog outdated, don't show
            return;
        }

        JsonArray pages = json.get("data").getAsJsonArray();
        for(int i = 0; i < pages.size(); i++) {
            JsonArray lines = pages.get(i).getAsJsonArray();
            StringBuilder page = new StringBuilder();
            for(int j = 0; j < lines.size(); j++) {
                page.append(lines.get(j).getAsString()).append('\n');
            }
            page.setLength(page.length() - 1);
            meta.addPage(page.toString());
        }
        book.setItemMeta(meta);
        this.changelog = book;
    }

    private boolean compateVersions(String oldVersion, String newVersion) {
        int oldId = matchLength(oldVersion, newVersion);
        int newId = matchLength(newVersion, oldVersion);
        return newId > oldId;
    }

    private int matchLength(String a, String b) {
        a = a.replaceAll("[^0-9]", "");
        b = b.replaceAll("[^0-9]", "");
        while(a.length() < b.length()) {
            a += "0";
        }
        return Integer.parseInt(a);
    }
}

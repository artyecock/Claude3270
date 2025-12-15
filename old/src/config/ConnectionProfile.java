package config;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Connection profile for saving and loading host connection settings.
 */
public class ConnectionProfile {
    private final String name;
    private final String hostname;
    private final int port;
    private final String model;
    private final boolean useTLS;
    
    private static final String PROFILES_FILE = 
        System.getProperty("user.home") + File.separator + ".tn3270profiles";
    private static Map<String, ConnectionProfile> savedProfiles = new HashMap<>();
    
    static {
        loadProfiles();
    }
    
    /**
     * Create a new connection profile.
     */
    public ConnectionProfile(String name, String hostname, int port, String model, boolean useTLS) {
        this.name = name;
        this.hostname = hostname;
        this.port = port;
        this.model = model;
        this.useTLS = useTLS;
    }
    
    /**
     * Load all saved profiles from disk.
     */
/*
    private static void loadProfiles() {
        File file = new File(PROFILES_FILE);
        if (!file.exists()) {
            // Create default profiles
            savedProfiles.put("Local z/VM", 
                new ConnectionProfile("Local z/VM", "localhost", 23, "3279-3", false));
            savedProfiles.put("Local z/OS", 
                new ConnectionProfile("Local z/OS", "localhost", 23, "3279-3", false));
            return;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length == 5) {
                    String name = parts[0];
                    savedProfiles.put(name, 
                        new ConnectionProfile(
                            name,
                            parts[1],
                            Integer.parseInt(parts[2]),
                            parts[3],
                            Boolean.parseBoolean(parts[4])
                        ));
                }
            }
        } catch (IOException e) {
            System.err.println("Could not load profiles: " + e.getMessage());
        }
    }
*/

// CHANGE THIS (was private static void)
public static Map<String, ConnectionProfile> loadProfiles() {
    Map<String, ConnectionProfile> profiles = new HashMap<>();

    File file = new File(PROFILES_FILE);
    if (!file.exists()) {
        return profiles;
    }

    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(",");
            if (parts.length == 5) {
                String name = parts[0];
                String hostname = parts[1];
                int port = Integer.parseInt(parts[2]);
                String model = parts[3];
                boolean useTLS = Boolean.parseBoolean(parts[4]);

                profiles.put(name, new ConnectionProfile(
                    name, hostname, port, model, useTLS
                ));
            }
        }
    } catch (IOException e) {
        e.printStackTrace();
    }

    return profiles;
}

    
    /**
     * Save all profiles to disk.
     */
/*
    public static void saveProfiles() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(PROFILES_FILE))) {
            for (ConnectionProfile profile : savedProfiles.values()) {
                writer.write(profile.name + "|" + 
                           profile.hostname + "|" + 
                           profile.port + "|" + 
                           profile.model + "|" + 
                           profile.useTLS);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Could not save profiles: " + e.getMessage());
        }
    }
*/

// CHANGE THIS (was public static void saveProfiles() with no parameters)
public static void saveProfiles(Map<String, ConnectionProfile> profiles) {
    File file = new File(PROFILES_FILE);

    try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
        for (ConnectionProfile profile : profiles.values()) {
            writer.println(
                profile.name + "," +
                profile.hostname + "," +
                profile.port + "," +
                profile.model + "," +
                profile.useTLS
            );
        }
    } catch (IOException e) {
        e.printStackTrace();
    }
}

    /**
     * Add a profile to the saved profiles.
     */
    public static void addProfile(ConnectionProfile profile) {
        //savedProfiles.put(profile.name, profile);
        //saveProfiles();
        Map<String, ConnectionProfile> profiles = loadProfiles();
        profiles.put(profile.getName(), profile);
        saveProfiles(profiles);
    }
    
    /**
     * Remove a profile by name.
     */
    public static void removeProfile(String name) {
        //savedProfiles.remove(name);
        //saveProfiles();
        Map<String, ConnectionProfile> profiles = loadProfiles();
        profiles.remove(name);
        saveProfiles(profiles);
    }
    
    /**
     * Get a profile by name.
     */
    public static ConnectionProfile getProfile(String name) {
        return savedProfiles.get(name);
    }
    
    /**
     * Get all saved profiles.
     */
    public static Map<String, ConnectionProfile> getAllProfiles() {
        return new HashMap<>(savedProfiles);
    }
    
    /**
     * Get all profile names.
     */
    public static String[] getProfileNames() {
        return savedProfiles.keySet().toArray(new String[0]);
    }
    
    // Getters
    public String getName() { return name; }
    public String getHostname() { return hostname; }
    public int getPort() { return port; }
    public String getModel() { return model; }
    public boolean useTLS() { return useTLS; }
    
    @Override
    public String toString() {
        return name + " (" + hostname + ":" + port + ")";
    }
}

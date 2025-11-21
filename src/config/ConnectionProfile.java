import java.io.*;
import java.util.HashMap;
import java.util.Map;

private static class ConnectionProfile {
	String name;
	String hostname;
	int port;
	String model;
	boolean useTLS;

	ConnectionProfile(String name, String hostname, int port, String model, boolean useTLS) {
		this.name = name;
		this.hostname = hostname;
		this.port = port;
		this.model = model;
		this.useTLS = useTLS;
	}

	@Override
	public String toString() {
		return name + " (" + hostname + ":" + port + ")";
	}

	}

	private static void loadProfiles() {
		File file = new File(PROFILES_FILE);
		if (!file.exists()) {
			// Create default profiles
			savedProfiles.put("Local z/VM", new ConnectionProfile("Local z/VM", "localhost", 23, "3279-3", false));
			savedProfiles.put("Local z/OS", new ConnectionProfile("Local z/OS", "localhost", 23, "3279-3", false));
			return;
		}

		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split("\\|");
				if (parts.length == 5) {
					String name = parts[0];
					savedProfiles.put(name, new ConnectionProfile(name, parts[1], Integer.parseInt(parts[2]), parts[3],
							Boolean.parseBoolean(parts[4])));
				}
			}
		} catch (IOException e) {
			System.err.println("Could not load profiles: " + e.getMessage());
		}
	}

	private static void saveProfiles() {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(PROFILES_FILE))) {
			for (ConnectionProfile profile : savedProfiles.values()) {
				writer.write(profile.name + "|" + profile.hostname + "|" + profile.port + "|" + profile.model + "|"
						+ profile.useTLS);
				writer.newLine();
			}
		} catch (IOException e) {
			System.err.println("Could not save profiles: " + e.getMessage());
		}
	}

	/**
	 * Get a saved profile by name.
	 */
	public static ConnectionProfile getProfile(String name) {
		return savedProfiles.get(name);
	}

	/**
	 * Add or update a connection profile.
	 */
	public static void putProfile(String name, ConnectionProfile profile) {
		savedProfiles.put(name, profile);
		saveProfiles();
	}

	/**
	 * Remove a connection profile.
	 */
	public static void removeProfile(String name) {
		savedProfiles.remove(name);
		saveProfiles();
	}

	/**
	 * Get all saved profile names.
	 */
	public static String[] getProfileNames() {
		return savedProfiles.keySet().toArray(new String[0]);
	}

	/**
	 * Get all saved profiles.
	 */
	public static Map<String, ConnectionProfile> getAllProfiles() {
		return new HashMap<>(savedProfiles);
	}
}

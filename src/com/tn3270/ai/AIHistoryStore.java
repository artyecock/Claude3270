package com.tn3270.ai;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.logging.Logger;

import com.tn3270.util.LoggerSetup;

public class AIHistoryStore {
	private static final Logger logger = LoggerSetup.getLogger(AIHistoryStore.class);
	private final File dir;

	public AIHistoryStore(String dirPath) {
		this.dir = new File(dirPath);
		if (!this.dir.exists())
			this.dir.mkdirs();
	}

	public File save(String json, String baseName) {
		try {
			long ts = System.currentTimeMillis();
			String name = baseName.replaceAll("[^A-Za-z0-9_.-]", "_") + "_" + ts + ".json";
			File out = new File(dir, name);
			try (FileWriter fw = new FileWriter(out)) {
				fw.write(json);
				fw.flush();
			}
			return out;
		} catch (Exception e) {
			//System.err.println("AIHistoryStore.save: " + e.getMessage());
			logger.severe("AIHistoryStore.save: " + e.getMessage());
			return null;
		}
	}

	public File[] listFiles() {
		File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
		if (files == null)
			return new File[0];
		Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
		return files;
	}

	public String read(File f) {
		if (f == null || !f.exists())
			return null;
		try (BufferedReader r = new BufferedReader(new FileReader(f))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = r.readLine()) != null)
				sb.append(line).append('\n');
			return sb.toString();
		} catch (Exception e) {
			//System.err.println("AIHistoryStore.read: " + e.getMessage());
			logger.severe("AIHistoryStore.read: " + e.getMessage());
			return null;
		}
	}
}

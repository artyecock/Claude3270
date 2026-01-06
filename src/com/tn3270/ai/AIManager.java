package com.tn3270.ai;

import java.awt.Frame;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.tn3270.util.LoggerSetup;

/**
 * Manages AI Providers and routes requests. Supports Aliasing: "RealModelName
 * (Display Name)" in config.
 */
public class AIManager implements AIModelProvider {
	private static final Logger logger = LoggerSetup.getLogger(AIManager.class);

	private static AIManager instance;
	private AIConfig config;
	private AIChatWindow activeWindow;

	// --- FIX: Use Home Directory and specific dot-file name ---
	private static final String CONFIG_FILE = System.getProperty("user.home") + java.io.File.separator + ".tn3270ai";

	// --- The Tuple Class ---
	private static class ModelRoute {
		final AIModelProvider provider;
		final String realModelName;

		ModelRoute(AIModelProvider provider, String realModelName) {
			this.provider = provider;
			this.realModelName = realModelName;
		}
	}

	// --- New Inner Class for Rules ---
	private static class DetectionRule {
		String contextKey;
		String requiredHost; // "TSO", "CMS", or null (any)
		List<String> fileTriggers = new ArrayList<>();
		List<String> textTriggers = new ArrayList<>();

		boolean matches(String host, String filename, String content) {
			// 1. Check Host Constraint
			if (requiredHost != null && !requiredHost.equalsIgnoreCase(host)) {
				return false;
			}

			String fnUpper = filename.toUpperCase();
			String txtUpper = content.toUpperCase(); // heuristic check is case-insensitive

			// 2. Check File Triggers (OR logic)
			for (String trig : fileTriggers) {
				if (fnUpper.contains(trig))
					return true;
			}

			// 3. Check Text Triggers (OR logic)
			for (String trig : textTriggers) {
				if (txtUpper.contains(trig))
					return true;
			}

			return false;
		}
	}

	private final List<DetectionRule> detectionRules = new ArrayList<>();

	// Maps "Display Name" (UI) -> Route (Provider + API Model Name)
	private final Map<String, ModelRoute> modelRouter = new LinkedHashMap<>(); // Linked to preserve order
	private final List<String> availableModels = new ArrayList<>();

	private AIManager() {
		// Load from ~/.tn3270ai instead of local "ai.conf"
		config = new AIConfig(CONFIG_FILE);
		reloadConfig();
	}

	public static synchronized AIManager getInstance() {
		if (instance == null)
			instance = new AIManager();
		return instance;
	}

	public void reloadConfig() {
		config.load();
		loadDetectionRules();
		modelRouter.clear();
		availableModels.clear();
		buildProviders();
	}

	public void showChatDialog(Frame owner, String selectedText) {
		showChatDialog(owner, selectedText, false);
	}

	public void showChatDialog(Frame owner, String selectedText, boolean autoSend) {
		if (activeWindow != null && activeWindow.isVisible()) {
			activeWindow.toFront();
			activeWindow.showWithPrefill(selectedText, config.get("ai.prompt.default", ""), autoSend);
			return;
		}
		AIChatWindow w = new AIChatWindow(owner, this, config);
		this.activeWindow = w;
		w.showWithPrefill(selectedText, config.get("ai.prompt.default", ""), autoSend);
	}

	// =======================================================================
	// ROUTING LOGIC
	// =======================================================================

	@Override
	public String[] listModels() {
		return availableModels.toArray(new String[0]);
	}

	@Override
	public String send(String displayModel, String prompt, String context) throws Exception {
		ModelRoute route = modelRouter.get(displayModel);
		if (route == null)
			throw new IllegalArgumentException("No provider configured for: " + displayModel);

		// Pass the REAL model name to the provider
		return route.provider.send(route.realModelName, prompt, context);
	}

	@Override
	public void sendStream(String displayModel, String prompt, String context, Consumer<String> onChunk,
			Consumer<Exception> onError, Runnable onComplete) throws Exception {
		ModelRoute route = modelRouter.get(displayModel);
		if (route == null) {
			onError.accept(new IllegalArgumentException("No provider configured for: " + displayModel));
			return;
		}

		// Pass the REAL model name to the provider
		route.provider.sendStream(route.realModelName, prompt, context, onChunk, onError, onComplete);
	}

	// =======================================================================
	// HEADLESS / HOST API (Used by TN3270Session Background Thread)
	// =======================================================================

	/**
	 * Processes a prompt from the Host Mainframe (via Structured Fields) without
	 * UI. Automatically selects a model and blocks until response is received.
	 * 
	 * @param prompt The text prompt received from the mainframe.
	 * @return The text response from the AI.
	 * @throws Exception If the AI request fails or no models are available.
	 */
	public String ask(String prompt) throws Exception {
		// 1. Select Model
		// Priority:
		// 1. "ai.model.host" (Specific model for mainframe interactions)
		// 2. "ai.model.default" (User's general default)
		// 3. First available model in the list
		String modelName = config.get("ai.model.host", config.get("ai.model.default", null));

		if (modelName == null || !modelRouter.containsKey(modelName)) {
			if (!availableModels.isEmpty()) {
				modelName = availableModels.get(0);
			} else {
				throw new IllegalStateException("No AI models are configured or available.");
			}
		}

		// 2. Select System Context
		// Use a specific system prompt for the host, or a generic fallback.
		String systemContext = config.get("ai.prompt.host",
				"You are an automated AI service integrated with a mainframe emulator. Keep responses concise and compatible with terminal displays.");

		// 3. Send (Blocking Call)
		// We reuse the existing routing logic which handles the Provider specifics.
		return send(modelName, prompt, systemContext);
	}

	// =======================================================================
	// CONFIGURATION PARSER
	// =======================================================================

	private void buildProviders() {
		String providersList = config.get("ai.providers", null);

		if (providersList != null && !providersList.trim().isEmpty()) {
			String[] ids = providersList.split(",");
			for (String id : ids) {
				id = id.trim();
				if (!id.isEmpty())
					loadProvider(id);
			}
		} else {
			loadLegacyProvider();
		}

		// System.out.println("AI Manager loaded. Models: " + availableModels);
		logger.info("AI Manager loaded. Models: " + availableModels);
	}

	private void loadProvider(String id) {
		String type = config.get(id + ".type", "openai");
		String modelsStr = config.get(id + ".models", "");

		AIModelProvider provider;

		if ("ollama".equalsIgnoreCase(type)) {
			String endpoint = config.get(id + ".endpoint", "http://localhost:11434");
			provider = new OllamaProvider(endpoint, null);
		} else {
			String apiKey = config.get(id + ".apiKey", "");
			String endpoint = config.get(id + ".endpoint", "https://api.openai.com/v1/chat/completions");
			provider = new OpenAIProvider(apiKey, null, endpoint);
		}

		// PARSE MODELS AND ALIASES
		// Syntax: "RealName (Display Name)" or just "RealName"
		String[] entries = modelsStr.split(",");
		for (String entry : entries) {
			entry = entry.trim();
			if (entry.isEmpty())
				continue;

			String displayName;
			String realName;

			int openParen = entry.indexOf('(');
			int closeParen = entry.lastIndexOf(')');

			if (openParen > 0 && closeParen > openParen) {
				// Found parens: extract names
				realName = entry.substring(0, openParen).trim();
				displayName = entry.substring(openParen + 1, closeParen).trim();
			} else {
				// No parens: simple case
				realName = entry;
				displayName = entry;
			}

			modelRouter.put(displayName, new ModelRoute(provider, realName));
			if (!availableModels.contains(displayName)) {
				availableModels.add(displayName);
			}
		}
	}

	private void loadLegacyProvider() {
		String providerName = config.get("ai.provider", "openai");
		String[] models = config.getModels();

		AIModelProvider provider;
		if ("ollama".equalsIgnoreCase(providerName)) {
			provider = new OllamaProvider(config.getEndpoint(), models);
		} else if ("google".equalsIgnoreCase(providerName)) {
			String ep = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
			provider = new OpenAIProvider(config.getApiKey(), models, ep);
		} else {
			provider = new OpenAIProvider(config.getApiKey(), models);
		}

		for (String m : models) {
			modelRouter.put(m, new ModelRoute(provider, m));
			availableModels.add(m);
		}
	}

	// =======================================================================
	// HELPER: BINARY DETECTION
	// =======================================================================
	public static boolean isLikelyBinary(byte[] data) {
		if (data == null || data.length == 0)
			return false;
		int controlCharCount = 0;
		int checkLength = Math.min(data.length, 2048); // Check first 2KB

		for (int i = 0; i < checkLength; i++) {
			byte b = data[i];
			// Allow: Tab (9), LF (10), CR (13), and standard printable range (32-126)
			// Everything else is suspicious.
			if ((b < 32 && b != 9 && b != 10 && b != 13) || b == 127) {
				controlCharCount++;
			}
		}

		// Threshold: If > 10% are control characters, it's likely binary.
		return ((double) controlCharCount / checkLength) > 0.10;
	}

	// =======================================================================
	// HELPER: CONTEXT AUGMENTATION (RAG)
	// =======================================================================
	public String buildAugmentedPrompt(String filename, String content, String hostType) {
		StringBuilder sb = new StringBuilder();

		// --- NEW DYNAMIC DETECTION ---
		String matchedKey = null;

		// Normalize HostType for string comparison (e.g. "TSO" or "CMS")
		String currentHost = (hostType == null) ? "" : hostType.toUpperCase();

		for (DetectionRule rule : detectionRules) {
			if (rule.matches(currentHost, filename, content)) {
				matchedKey = rule.contextKey;
				// System.out.println("Context Match: " + matchedKey + " for " + filename);
				logger.info("Context Match: " + matchedKey + " for " + filename);
				break; // First match wins based on 'detect.order'
			}
		}
		// -----------------------------

		// 2. Fetch "Primer" from Config (Existing logic, slightly cleaner)
		if (matchedKey != null) {
			String prompt = config.get("context." + matchedKey + ".prompt", null);
			String ref = config.get("context." + matchedKey + ".ref", null);
			String url = config.get("context." + matchedKey + ".url", null);

			if (prompt != null) {
				sb.append("--- SYSTEM CONTEXT ---\n");
				sb.append(prompt).append("\n");
				if (ref != null) {
					sb.append("Reference Material: Please base answer on definitions found in '").append(ref)
							.append("'");
					if (url != null)
						sb.append(" (Available at: ").append(url).append(")");
					sb.append(".\n");
				}
				sb.append("\n");
			}
		}

		sb.append("--- ATTACHED FILE: ").append(filename).append(" ---\n");
		sb.append(content).append("\n\n");
		sb.append("--- USER REQUEST ---\n");

		return sb.toString();
	}

	public String buildAugmentedPromptOld(String filename, String content, String hostType) {
		StringBuilder sb = new StringBuilder();

		String key = null;
		String fn = filename.toUpperCase();

		// 1. Detect Context Key based on File Extension or Content
		if (hostType.contains("CMS") || hostType.contains("VM")) {
			if (fn.contains(" EXEC") || content.contains("/* REXX */"))
				key = "cms_rexx";
			else if (fn.contains(" ASSEMBLE"))
				key = "asm";
			else if (content.contains("PIPE"))
				key = "pipelines";
		} else { // TSO
			if (content.contains("/* REXX */"))
				key = "tso_rexx";
			else if (content.contains("//") && content.contains("JOB"))
				key = "jcl";
			else if (fn.contains("COBOL") || content.contains("IDENTIFICATION DIVISION"))
				key = "cobol";
		}

		// 2. Fetch "Primer" from Config
		if (key != null) {
			String prompt = config.get("context." + key + ".prompt", null);
			String ref = config.get("context." + key + ".ref", null);
			String url = config.get("context." + key + ".url", null);

			if (prompt != null) {
				sb.append("--- SYSTEM CONTEXT ---\n");
				sb.append(prompt).append("\n");
				if (ref != null) {
					sb.append("Reference Material: Please base answer on definitions found in '").append(ref)
							.append("'");
					if (url != null)
						sb.append(" (Available at: ").append(url).append(")");
					sb.append(".\n");
				}
				sb.append("\n");
			}
		}

		sb.append("--- ATTACHED FILE: ").append(filename).append(" ---\n");
		sb.append(content).append("\n\n");
		sb.append("--- USER REQUEST ---\n");

		return sb.toString();
	}
	/*
	 * private String buildAugmentedPromptNew(String filename, String content,
	 * TN3270Session.HostType hostType) { StringBuilder sb = new StringBuilder();
	 * AIConfig config = AIConfig.getInstance();
	 * 
	 * // 1. Determine Context Keys String fileExt = getExtension(filename); //
	 * e.g., "EXEC", "JCL", "ASSEMBLE"
	 * 
	 * // 2. Select Primer based on Logic String primer = "";
	 * 
	 * if (hostType == TN3270Session.HostType.CMS) { if
	 * (content.contains("// REXX //") || filename.contains(" EXEC ")) { primer =
	 * config.getContext("cms_rexx"); } else if (filename.contains(" ASSEMBLE ")) {
	 * primer = config.getContext("asm"); } else if (content.contains("PIPE")) {
	 * primer = config.getContext("pipelines"); } } else { // TSO if
	 * (content.contains("// REXX //")) { primer = config.getContext("tso_rexx"); }
	 * else if (content.contains("//") && content.contains("JOB")) { primer =
	 * config.getContext("jcl"); } }
	 * 
	 * // 3. Assemble the prompt if (primer != null && !primer.isEmpty()) {
	 * sb.append("--- SYSTEM CONTEXT ---\n"); sb.append(primer).append("\n\n"); }
	 * 
	 * sb.append("--- ATTACHED FILE: ").append(filename).append(" ---\n");
	 * sb.append(content).append("\n\n"); sb.append("--- USER REQUEST ---\n");
	 * 
	 * return sb.toString(); }
	 */

	private void loadDetectionRules() {
		detectionRules.clear();
		String orderRaw = config.get("detect.order", "");
		if (orderRaw.isEmpty())
			return;

		String[] keys = orderRaw.split(",");
		for (String key : keys) {
			key = key.trim();
			if (key.isEmpty())
				continue;

			DetectionRule rule = new DetectionRule();
			rule.contextKey = key;

			// Load Host Constraint
			rule.requiredHost = config.get("detect." + key + ".host", null);

			// Load Triggers
			String triggersRaw = config.get("detect." + key + ".triggers", "");
			if (!triggersRaw.isEmpty()) {
				// Split by Pipe '|'
				String[] parts = triggersRaw.split("\\|");
				for (String part : parts) {
					part = part.trim();
					if (part.toUpperCase().startsWith("FILE:")) {
						rule.fileTriggers.add(part.substring(5).trim().toUpperCase());
					} else if (part.toUpperCase().startsWith("TEXT:")) {
						rule.textTriggers.add(part.substring(5).trim().toUpperCase());
					}
				}
			}

			if (!rule.fileTriggers.isEmpty() || !rule.textTriggers.isEmpty()) {
				detectionRules.add(rule);
			}
		}
		// System.out.println("Loaded " + detectionRules.size() + " detection rules.");
		logger.info("Loaded " + detectionRules.size() + " detection rules.");
	}
}

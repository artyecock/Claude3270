import java.awt.Dimension;
import java.util.HashMap;
import java.util.Map;


/**
 * 
 * Terminal model definitions and screen dimensions.
 */
public class TerminalModels {
	// Terminal models and their dimensions
	private static final Map<String, Dimension> MODELS = new HashMap<>();
	static {
		initializeModels();
	}

	/**
	 * 
	 * Initialize all supported terminal models.
	 */
	private static void initializeModels() {
		MODELS.put("3278-2", new Dimension(80, 24));
		MODELS.put("3278-3", new Dimension(80, 32));
		MODELS.put("3278-4", new Dimension(80, 43));
		MODELS.put("3278-5", new Dimension(132, 27));
		MODELS.put("3279-2", new Dimension(80, 24)); // Color
		MODELS.put("3279-3", new Dimension(80, 32)); // Color
	}

	/**
	 * 
	 * Get screen dimensions for a given model.
	 */
	public static Dimension getDimensions(String model) {
		return MODELS.get(model);
	}

	/**
	 * 
	 * Check if a model is supported.
	 */
	public static boolean isValidModel(String model) {
		return MODELS.containsKey(model);
	}

	/**
	 * 
	 * Get all supported model names.
	 */
	public static String[] getModelNames() {
		return MODELS.keySet().toArray(new String[0]);
	}
}

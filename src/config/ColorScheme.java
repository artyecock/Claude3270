import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Color scheme management for TN3270 terminal display. Provides predefined
 * color schemes and custom color configuration.
 */
public class ColorScheme {
	public Color background;
	public Color defaultFg;
	public Color cursor;
	public Color[] colors;

	public ColorScheme(Color background, Color defaultFg, Color cursor, Color[] colors) {
		this.background = background;
		this.defaultFg = defaultFg;
		this.cursor = cursor;
		this.colors = colors;
	}

// Predefined color schemes
	private static final Map<String, ColorScheme> SCHEMES = new HashMap<>();
	static {
		initializeSchemes();
	}

	/**
	 * Get a predefined color scheme by name.
	 */
	public static ColorScheme getScheme(String name) {
		return SCHEMES.get(name);
	}

	/**
	 * Get all available scheme names.
	 */
	public static String[] getSchemeNames() {
		return SCHEMES.keySet().toArray(new String[0]);
	}

	/**
	 * Initialize all predefined color schemes.
	 */
	private static void initializeSchemes() {
// Green on Black (Classic)
		SCHEMES.put("Green on Black (Classic)",
				new ColorScheme(Color.BLACK, Color.GREEN, Color.WHITE, new Color[] { Color.BLACK, // 0 - Default
						Color.BLUE, // 1
						Color.RED, // 2
						Color.MAGENTA, // 3
						Color.GREEN, // 4
						Color.CYAN, // 5
						Color.YELLOW, // 6
						Color.WHITE // 7
				}));
// White on Black
		SCHEMES.put("White on Black", new ColorScheme(Color.BLACK, Color.WHITE, new Color(255, 255, 0), // Yellow cursor
				new Color[] { Color.BLACK, new Color(100, 149, 237), // Cornflower Blue
						new Color(255, 99, 71), // Tomato Red
						new Color(255, 105, 180), // Hot Pink
						new Color(144, 238, 144), // Light Green
						new Color(64, 224, 208), // Turquoise
						new Color(255, 255, 0), // Yellow
						Color.WHITE }));
// Amber on Black (Old Terminal)
		SCHEMES.put("Amber on Black", new ColorScheme(Color.BLACK, new Color(255, 176, 0), // Amber
				new Color(255, 200, 50), // Bright amber cursor
				new Color[] { Color.BLACK, new Color(180, 130, 0), // Dark amber
						new Color(255, 100, 0), // Orange-red
						new Color(255, 140, 0), // Dark orange
						new Color(255, 176, 0), // Amber
						new Color(255, 200, 50), // Light amber
						new Color(255, 220, 100), // Pale amber
						new Color(255, 230, 150) // Very pale amber
				}));
// Green on Dark Green (Phosphor)
		SCHEMES.put("Green on Dark Green", new ColorScheme(new Color(0, 40, 0), // Very dark green
				new Color(51, 255, 51), // Bright green
				new Color(102, 255, 102), // Light green cursor
				new Color[] { new Color(0, 40, 0), // Background
						new Color(0, 128, 128), // Teal
						new Color(0, 200, 0), // Medium green
						new Color(102, 255, 102), // Light green
						new Color(51, 255, 51), // Bright green
						new Color(153, 255, 153), // Very light green
						new Color(204, 255, 204), // Pale green
						new Color(230, 255, 230) // Almost white
				}));
// IBM 3270 Blue (Classic IBM)
		SCHEMES.put("IBM 3270 Blue", new ColorScheme(new Color(0, 0, 64), // Dark blue
				new Color(0, 255, 0), // Bright green
				Color.WHITE, new Color[] { new Color(0, 0, 64), // Background
						new Color(85, 170, 255), // Light blue
						new Color(255, 85, 85), // Light red
						new Color(255, 85, 255), // Pink/Magenta
						new Color(0, 255, 0), // Bright green
						new Color(85, 255, 255), // Cyan
						new Color(255, 255, 85), // Yellow
						Color.WHITE }));
// Solarized Dark
		SCHEMES.put("Solarized Dark", new ColorScheme(new Color(0, 43, 54), // Base03
				new Color(131, 148, 150), // Base0
				new Color(147, 161, 161), // Base1 cursor
				new Color[] { new Color(0, 43, 54), // Base03
						new Color(38, 139, 210), // Blue
						new Color(220, 50, 47), // Red
						new Color(211, 54, 130), // Magenta
						new Color(133, 153, 0), // Green
						new Color(42, 161, 152), // Cyan
						new Color(181, 137, 0), // Yellow
						new Color(238, 232, 213) // Base2
				}));
	}
}

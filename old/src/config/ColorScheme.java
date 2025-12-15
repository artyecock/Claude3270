package config;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Color scheme configuration for terminal display.
 * Includes predefined schemes and custom color support.
 */
public class ColorScheme {
    private final Color background;
    private final Color defaultForeground;
    private final Color cursor;
    private final Color[] colors;
    
    // Predefined color schemes
    private static final Map<String, ColorScheme> SCHEMES = new HashMap<>();
    
    static {
        initializeColorSchemes();
    }
    
    /**
     * Create a custom color scheme.
     */
    public ColorScheme(Color background, Color defaultForeground, Color cursor, Color[] colors) {
        this.background = background;
        this.defaultForeground = defaultForeground;
        this.cursor = cursor;
        this.colors = colors.clone();
    }
    
    /**
     * Initialize all predefined color schemes.
     */
    private static void initializeColorSchemes() {
        // Green on Black (Classic)
        SCHEMES.put("Green on Black (Classic)",
            new ColorScheme(
                Color.BLACK,
                Color.GREEN,
                Color.WHITE,
                new Color[] {
                    Color.BLACK,          // 0 - Default
                    Color.BLUE,           // 1
                    Color.RED,            // 2
                    Color.MAGENTA,        // 3
                    Color.GREEN,          // 4
                    Color.CYAN,           // 5
                    Color.YELLOW,         // 6
                    Color.WHITE           // 7
                }
            ));
        
        // White on Black
        SCHEMES.put("White on Black",
            new ColorScheme(
                Color.BLACK,
                Color.WHITE,
                new Color(255, 255, 0), // Yellow cursor
                new Color[] {
                    Color.BLACK,                    // 0 - Default
                    new Color(100, 149, 237),       // 1 - Cornflower Blue
                    new Color(255, 99, 71),         // 2 - Tomato Red
                    new Color(255, 105, 180),       // 3 - Hot Pink
                    new Color(144, 238, 144),       // 4 - Light Green
                    new Color(64, 224, 208),        // 5 - Turquoise
                    new Color(255, 255, 0),         // 6 - Yellow
                    Color.WHITE                     // 7
                }
            ));
        
        // Amber on Black (Old Terminal)
        SCHEMES.put("Amber on Black",
            new ColorScheme(
                Color.BLACK,
                new Color(255, 176, 0),      // Amber
                new Color(255, 200, 50),     // Bright amber cursor
                new Color[] {
                    Color.BLACK,                // 0 - Default
                    new Color(180, 130, 0),     // 1 - Dark amber
                    new Color(255, 100, 0),     // 2 - Orange-red
                    new Color(255, 140, 0),     // 3 - Dark orange
                    new Color(255, 176, 0),     // 4 - Amber
                    new Color(255, 200, 50),    // 5 - Light amber
                    new Color(255, 220, 100),   // 6 - Pale amber
                    new Color(255, 230, 150)    // 7 - Very pale amber
                }
            ));
        
        // Green on Dark Green (Phosphor)
        SCHEMES.put("Green on Dark Green",
            new ColorScheme(
                new Color(0, 40, 0),         // Very dark green
                new Color(51, 255, 51),      // Bright green
                new Color(102, 255, 102),    // Light green cursor
                new Color[] {
                    new Color(0, 40, 0),        // 0 - Background
                    new Color(0, 128, 128),     // 1 - Teal
                    new Color(0, 200, 0),       // 2 - Medium green
                    new Color(102, 255, 102),   // 3 - Light green
                    new Color(51, 255, 51),     // 4 - Bright green
                    new Color(153, 255, 153),   // 5 - Very light green
                    new Color(204, 255, 204),   // 6 - Pale green
                    new Color(230, 255, 230)    // 7 - Almost white
                }
            ));
        
        // IBM 3270 Blue (Classic IBM)
        SCHEMES.put("IBM 3270 Blue",
            new ColorScheme(
                new Color(0, 0, 64),         // Dark blue
                new Color(0, 255, 0),        // Bright green
                Color.WHITE,
                new Color[] {
                    new Color(0, 0, 64),        // 0 - Background
                    new Color(85, 170, 255),    // 1 - Light blue
                    new Color(255, 85, 85),     // 2 - Light red
                    new Color(255, 85, 255),    // 3 - Pink/Magenta
                    new Color(0, 255, 0),       // 4 - Bright green
                    new Color(85, 255, 255),    // 5 - Cyan
                    new Color(255, 255, 85),    // 6 - Yellow
                    Color.WHITE                 // 7
                }
            ));
        
        // Solarized Dark
        SCHEMES.put("Solarized Dark",
            new ColorScheme(
                new Color(0, 43, 54),        // Base03
                new Color(131, 148, 150),    // Base0
                new Color(147, 161, 161),    // Base1 cursor
                new Color[] {
                    new Color(0, 43, 54),       // 0 - Base03
                    new Color(38, 139, 210),    // 1 - Blue
                    new Color(220, 50, 47),     // 2 - Red
                    new Color(211, 54, 130),    // 3 - Magenta
                    new Color(133, 153, 0),     // 4 - Green
                    new Color(42, 161, 152),    // 5 - Cyan
                    new Color(181, 137, 0),     // 6 - Yellow
                    new Color(238, 232, 213)    // 7 - Base2
                }
            ));
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
     * Check if a scheme name exists.
     */
    public static boolean hasScheme(String name) {
        return SCHEMES.containsKey(name);
    }
    
    // Getters
    public Color getBackground() { return background; }
    public Color getDefaultForeground() { return defaultForeground; }
    public Color getCursor() { return cursor; }
    public Color[] getColors() { return colors.clone(); }
    public Color getColor(int index) {
        if (index >= 0 && index < colors.length) {
            return colors[index];
        }
        return defaultForeground;
    }
}

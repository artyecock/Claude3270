package config;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Keyboard mapping configuration for custom key assignments.
 * Supports mapping keys to characters or AID functions.
 */
public class KeyMapping implements Serializable {
    private static final long serialVersionUID = 1L;
    
    char character;
    Byte aid;
    String description;
    
    private static final String KEYMAP_FILE = 
        System.getProperty("user.home") + File.separator + ".tn3270keymap";
    
    /**
     * Create a character mapping.
     */
    public KeyMapping(char character, String description) {
        this.character = character;
        this.aid = null;
        this.description = description;
    }
    
    /**
     * Create an AID mapping.
     */
    public KeyMapping(byte aid, String description) {
        this.character = '\0';
        this.aid = aid;
        this.description = description;
    }
    
    /**
     * Save key mappings to disk.
     */
    public static void saveKeyMappings(Map<Integer, KeyMapping> keyMap) {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(KEYMAP_FILE))) {
            out.writeObject(new HashMap<>(keyMap));
            System.out.println("Keymaps saved to " + KEYMAP_FILE);
        } catch (IOException e) {
            System.err.println("Could not save keymaps: " + e.getMessage());
        }
    }
    
    /**
     * Load key mappings from disk.
     */
    @SuppressWarnings("unchecked")
    public static void loadKeyMappings(Map<Integer, KeyMapping> keyMap) {
        File file = new File(KEYMAP_FILE);
        if (!file.exists()) {
            return;
        }
        
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
            Map<Integer, KeyMapping> loaded = (Map<Integer, KeyMapping>) in.readObject();
            keyMap.clear();
            keyMap.putAll(loaded);
            System.out.println("Keymaps loaded from " + KEYMAP_FILE);
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Could not load keymaps: " + e.getMessage());
        }
    }
    
    /**
     * Initialize default key mappings.
     */
    public static void initializeDefaultMappings(Map<Integer, KeyMapping> keyMap) {
        // Default mappings (from Java KeyEvent constants)
        keyMap.put(192, new KeyMapping('¬', "Not sign"));  // VK_BACK_QUOTE
        keyMap.put(92, new KeyMapping('|', "Pipe"));       // VK_BACK_SLASH
    }
    
    /**
     * Get AID byte for a given AID name.
     */
    public static byte getAidForName(String name) {
        switch (name) {
            case "ENTER": return Constants.AID_ENTER;
            case "CLEAR": return Constants.AID_CLEAR;
            case "PA1": return Constants.AID_PA1;
            case "PA2": return Constants.AID_PA2;
            case "PA3": return Constants.AID_PA3;
            case "PF1": return Constants.AID_PF1;
            case "PF2": return Constants.AID_PF2;
            case "PF3": return Constants.AID_PF3;
            case "PF4": return Constants.AID_PF4;
            case "PF5": return Constants.AID_PF5;
            case "PF6": return Constants.AID_PF6;
            case "PF7": return Constants.AID_PF7;
            case "PF8": return Constants.AID_PF8;
            case "PF9": return Constants.AID_PF9;
            case "PF10": return Constants.AID_PF10;
            case "PF11": return Constants.AID_PF11;
            case "PF12": return Constants.AID_PF12;
            case "PF13": return Constants.AID_PF13;
            case "PF14": return Constants.AID_PF14;
            case "PF15": return Constants.AID_PF15;
            case "PF16": return Constants.AID_PF16;
            case "PF17": return Constants.AID_PF17;
            case "PF18": return Constants.AID_PF18;
            case "PF19": return Constants.AID_PF19;
            case "PF20": return Constants.AID_PF20;
            case "PF21": return Constants.AID_PF21;
            case "PF22": return Constants.AID_PF22;
            case "PF23": return Constants.AID_PF23;
            case "PF24": return Constants.AID_PF24;
            default: return Constants.AID_ENTER;
        }
    }
    
    /**
     * Get AID name for a given AID byte.
     */
    public static String getNameForAid(byte aid) {
        if (aid == Constants.AID_ENTER) return "ENTER";
        if (aid == Constants.AID_CLEAR) return "CLEAR";
        if (aid == Constants.AID_PA1) return "PA1";
        if (aid == Constants.AID_PA2) return "PA2";
        if (aid == Constants.AID_PA3) return "PA3";
        if (aid == Constants.AID_PF1) return "PF1";
        if (aid == Constants.AID_PF2) return "PF2";
        if (aid == Constants.AID_PF3) return "PF3";
        if (aid == Constants.AID_PF4) return "PF4";
        if (aid == Constants.AID_PF5) return "PF5";
        if (aid == Constants.AID_PF6) return "PF6";
        if (aid == Constants.AID_PF7) return "PF7";
        if (aid == Constants.AID_PF8) return "PF8";
        if (aid == Constants.AID_PF9) return "PF9";
        if (aid == Constants.AID_PF10) return "PF10";
        if (aid == Constants.AID_PF11) return "PF11";
        if (aid == Constants.AID_PF12) return "PF12";
        if (aid == Constants.AID_PF13) return "PF13";
        if (aid == Constants.AID_PF14) return "PF14";
        if (aid == Constants.AID_PF15) return "PF15";
        if (aid == Constants.AID_PF16) return "PF16";
        if (aid == Constants.AID_PF17) return "PF17";
        if (aid == Constants.AID_PF18) return "PF18";
        if (aid == Constants.AID_PF19) return "PF19";
        if (aid == Constants.AID_PF20) return "PF20";
        if (aid == Constants.AID_PF21) return "PF21";
        if (aid == Constants.AID_PF22) return "PF22";
        if (aid == Constants.AID_PF23) return "PF23";
        if (aid == Constants.AID_PF24) return "PF24";
        return "(None)";
    }
    
    // Getters
    public char getCharacter() { return character; }
    public Byte getAid() { return aid; }
    public String getDescription() { return description; }
    public boolean isAidMapping() { return aid != null; }
    public boolean isCharMapping() { return aid == null; }
}

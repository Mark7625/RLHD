package rs117.hd.utils.tooling.props;

/**
 * Generic context interface for property components to interact with their parent editor
 * @param <T> The type of data object being edited
 */
public interface PropertyContext<T> {
    
    /**
     * Get the current value for a property key
     * @param data The data object
     * @param key The property key
     * @return The current value as a string
     */
    String getValue(T data, String key);
    
    /**
     * Set a value for a property key
     * @param data The data object
     * @param key The property key
     * @param value The new value
     */
    void setValue(T data, String key, Object value);
    
    /**
     * Get the color picker manager for color-related operations
     * @return The color picker manager
     */
    net.runelite.client.ui.components.colorpicker.ColorPickerManager getColorPickerManager();
    
    /**
     * Get the window component for color picker creation
     * @return The window component
     */
    java.awt.Component getWindowComponent();
    
    /**
     * Get the name of the data object for display purposes
     * @param data The data object
     * @return The display name
     */
    String getDisplayName(T data);
}

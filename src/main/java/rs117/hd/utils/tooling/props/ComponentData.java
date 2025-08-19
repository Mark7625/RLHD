package rs117.hd.utils.tooling.props;

import javax.swing.JComponent;

/**
 * Generic abstract class for property components
 * @param <T> The type of data object being edited
 */
public abstract class ComponentData<T> {

	public abstract void create();

	public int[] range = new int[] { 0, Integer.MAX_VALUE };
	public String value;
	public String key;
	public T data;
	public JComponent component;
	public PropertyContext<T> context;
	public Runnable onValueChanged;

}
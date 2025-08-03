package rs117.hd.utils.tooling.props;


import javax.swing.JComponent;
import rs117.hd.scene.environments.Environment;
import rs117.hd.utils.tooling.environment.EnvironmentEditorPanel;

public abstract class ComponentData {

	public abstract void create();

	public int[] range = new int[] { 0, Integer.MAX_VALUE };
	public String value;
	public String key;
	public Environment environment;
	public JComponent component;
	public EnvironmentEditorPanel environmentEditor;
	public Runnable onValueChanged;


}
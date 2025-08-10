package rs117.hd.utils.tooling.props;

import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.Data;
import rs117.hd.scene.environments.Environment;

@Data
public class PropertyData {

	private String propertyName;
	private String description;
	private Object object;
	private Class<?> type;
	private BiConsumer<Environment, Object> setter;
	private Function<Environment, String> getter;
	private String category;
	private String editorType;

	public PropertyData(String description, Class<?> type, BiConsumer<Environment, Object> setter, Function<Environment, String> getter, String category) {
		this.description = description;
		this.type = type;
		this.object = null;
		this.getter = getter;
		this.setter = setter;
		this.category = category;
		this.editorType = null;
	}

	public PropertyData(String description, Object object, BiConsumer<Environment, Object> setter, Function<Environment, String> getter, String category) {
		this.description = description;
		this.object = object;
		this.type = object != null ? object.getClass() : null;
		this.getter = getter;
		this.setter = setter;
		this.category = category;
		this.editorType = null;
	}

	public PropertyData(String description, BiConsumer<Environment, Object> setter, Function<Environment, String> getter, String category) {
		this.description = description;
		this.object = null;
		this.type = null;
		this.getter = getter;
		this.setter = setter;
		this.category = category;
		this.editorType = null;
	}

	public PropertyData(String description, Class<?> type, BiConsumer<Environment, Object> setter, Function<Environment, String> getter, String category, String editorType) {
		this.description = description;
		this.type = type;
		this.object = null;
		this.getter = getter;
		this.setter = setter;
		this.category = category;
		this.editorType = editorType;
	}

	@Override
	public String toString() {
		return "PropertyDescription{" +
			   "propertyName='" + propertyName + '\'' +
			   ", description='" + description + '\'' +
			   ", category='" + category + '\'' +
			   '}';
	}
	
	// Manual getter for editorType in case Lombok doesn't work
	public String getEditorType() {
		return editorType;
	}
}
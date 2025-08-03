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
	private BiConsumer<Environment, Object> setter;
	private Function<Environment, String> getter;
	private String category;

	public PropertyData(String description, Object object, BiConsumer<Environment, Object> setter, Function<Environment, String> getter, String category) {
		this.description = description;
		this.object = object;
		this.getter = getter;
		this.setter = setter;
		this.category = category;
	}

	public PropertyData(String description, BiConsumer<Environment, Object> setter, Function<Environment, String> getter, String category) {
		this.description = description;
		this.object = null;
		this.getter = getter;
		this.setter = setter;
		this.category = category;
	}


	@Override
	public String toString() {
		return "PropertyDescription{" +
			   "propertyName='" + propertyName + '\'' +
			   ", description='" + description + '\'' +
			   ", category='" + category + '\'' +
			   '}';
	}
}
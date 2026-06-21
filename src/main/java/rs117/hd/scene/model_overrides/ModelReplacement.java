package rs117.hd.scene.model_overrides;

import com.google.gson.annotations.JsonAdapter;
import lombok.Data;
import rs117.hd.scene.lights.LightTimeOfDay;

@Data
public class ModelReplacement {
	@JsonAdapter(ModelDefinition.Adapter.class)
	public ModelDefinition model;

	@JsonAdapter(ModelDefinition.Adapter.class)
	public ModelDefinition nightModel;

	@JsonAdapter(LightTimeOfDay.Adapter.class)
	public LightTimeOfDay timeOfDay;

	@JsonAdapter(LightTimeOfDay.Adapter.class)
	public LightTimeOfDay timeOfDayOff;

	public boolean staggered;
	public boolean dayNightOnly;

	/** When true, overlay models are merged onto the scene model instead of replacing it. */
	public boolean mergeWithOriginal;

	public boolean isTimeOfDayControlled() {
		return timeOfDay != null;
	}
}

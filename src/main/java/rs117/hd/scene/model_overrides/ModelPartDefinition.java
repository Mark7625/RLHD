package rs117.hd.scene.model_overrides;

import lombok.NoArgsConstructor;

/**
 * Optional overrides for a sub-model within a composite {@link ModelDefinition}.
 * Unset fields inherit from the parent definition.
 */
@NoArgsConstructor
public class ModelPartDefinition {
	public int[] modelIds;

	public Integer contrast;
	public Integer ambient;

	public Integer offsetX;
	public Integer offsetY;
	public Integer offsetZ;

	public Integer modelSizeX;
	public Integer modelSizeY;
	public Integer modelHeight;

	public Boolean rotated;

	public int[] recolorFrom;
	public int[] recolorTo;
	public short[] retextureFrom;
	public short[] retextureTo;

	ModelDefinition mergeInto(ModelDefinition parent, String partName) {
		ModelDefinition merged = new ModelDefinition();
		merged.name = parent.name + "/" + partName;

		merged.modelIds = modelIds != null && modelIds.length > 0 ? modelIds : parent.modelIds;
		merged.contrast = contrast != null ? contrast : parent.contrast;
		merged.ambient = ambient != null ? ambient : parent.ambient;

		merged.offsetX = offsetX != null ? offsetX : parent.offsetX;
		merged.offsetY = offsetY != null ? offsetY : parent.offsetY;
		merged.offsetZ = offsetZ != null ? offsetZ : parent.offsetZ;

		merged.modelSizeX = modelSizeX != null ? modelSizeX : parent.modelSizeX;
		merged.modelSizeY = modelSizeY != null ? modelSizeY : parent.modelSizeY;
		merged.modelHeight = modelHeight != null ? modelHeight : parent.modelHeight;

		merged.rotated = rotated != null ? rotated : parent.rotated;

		merged.recolorFrom = recolorFrom != null ? recolorFrom : parent.recolorFrom;
		merged.recolorTo = recolorTo != null ? recolorTo : parent.recolorTo;
		merged.retextureFrom = retextureFrom != null ? retextureFrom : parent.retextureFrom;
		merged.retextureTo = retextureTo != null ? retextureTo : parent.retextureTo;

		return merged;
	}
}

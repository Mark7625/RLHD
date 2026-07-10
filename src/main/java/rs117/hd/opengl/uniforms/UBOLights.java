package rs117.hd.opengl.uniforms;

import rs117.hd.scene.lights.MaskProjection;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class UBOLights extends UniformBuffer<GLBuffer> {

	public static final int MAX_LIGHTS = 1000; // Struct is 64 Bytes, UBO Max size is 64 KB
	private final LightStruct[] lights;
	private final Property[] lightPositions;

	public UBOLights(boolean isCullingUBO) {
		super(GL_DYNAMIC_DRAW);
		lightPositions = isCullingUBO ? addPropertyArray(PropertyType.FVec4, "lightPositions", MAX_LIGHTS) : null;
		lights = !isCullingUBO ? addStructs(new LightStruct[MAX_LIGHTS], LightStruct::new) : null;
	}

	@Override
	public String getUniformBlockName() {
		return lights != null ? "UBOLights" : "UBOLightsCulling";
	}

	public void setLight(int lightIdx, float[] position, float[] color) {
		if (lightIdx >= 0 && lightIdx < MAX_LIGHTS) {
			if (lights != null) {
				var struct = lights[lightIdx];
				struct.position.set(position);
				struct.color.set(color);
			} else {
				lightPositions[lightIdx].set(position);
			}
		}
	}

	public static class LightStruct extends UniformBuffer.StructProperty {
		public Property position = addProperty(PropertyType.FVec4, "position");
		public Property color = addProperty(PropertyType.FVec4, "color");
	}

	public static class LightMasks extends UniformBuffer<GLBuffer> {
		private final Property[] maskData;

		public LightMasks() {
			super(GL_DYNAMIC_DRAW);
			maskData = addPropertyArray(PropertyType.FVec4, "lightMaskData", MAX_LIGHTS);
		}

		@Override
		public String getUniformBlockName() {
			return "UBOLightMasks";
		}

		public void setMask(int lightIdx, int layer, float scale, MaskProjection projection) {
			if (lightIdx >= 0 && lightIdx < MAX_LIGHTS) {
				int mode = projection != null ? projection.shaderIndex() : MaskProjection.AUTO.shaderIndex();
				maskData[lightIdx].set(layer, scale, mode, 0);
			}
		}
	}

}

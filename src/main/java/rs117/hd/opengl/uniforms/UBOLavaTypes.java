package rs117.hd.opengl.uniforms;

import rs117.hd.HdPlugin;
import rs117.hd.scene.lava.LavaType;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class UBOLavaTypes extends UniformBuffer<GLBuffer> {
	public static class LavaTypeStruct extends StructProperty {
		public final Property duration = addProperty(PropertyType.Float, "duration");
		public final Property scale = addProperty(PropertyType.Float, "scale");
		public final Property crustThreshold = addProperty(PropertyType.Float, "crustThreshold");
		public final Property crustAmount = addProperty(PropertyType.Float, "crustAmount");
		public final Property crustSharpness = addProperty(PropertyType.Float, "crustSharpness");
		public final Property flowStrength = addProperty(PropertyType.Float, "flowStrength");
		public final Property emissiveStrength = addProperty(PropertyType.Float, "emissiveStrength");
		public final Property specularStrength = addProperty(PropertyType.Float, "specularStrength");
		public final Property specularGloss = addProperty(PropertyType.Float, "specularGloss");
		public final Property waveStrength = addProperty(PropertyType.Float, "waveStrength");
		public final Property surfaceGlow = addProperty(PropertyType.Float, "surfaceGlow");
		public final Property irradianceStrength = addProperty(PropertyType.Float, "irradianceStrength");
		public final Property animSpeed = addProperty(PropertyType.Float, "animSpeed");
		public final Property crustColor = addProperty(PropertyType.FVec3, "crustColor");
		public final Property magmaColor = addProperty(PropertyType.FVec3, "magmaColor");
		public final Property hotColor = addProperty(PropertyType.FVec3, "hotColor");
	}

	private final LavaTypeStruct[] uboStructs;
	private final LavaType[] lavaTypes;

	public UBOLavaTypes(LavaType[] lavaTypes) {
		super(GL_STATIC_DRAW);
		this.lavaTypes = lavaTypes;
		uboStructs = addStructs(new LavaTypeStruct[lavaTypes.length], LavaTypeStruct::new);
		initialize(HdPlugin.UNIFORM_BLOCK_LAVA_TYPES);
		update();
	}

	public int getCount() {
		return uboStructs.length;
	}

	public void update() {
		for (int i = 0; i < lavaTypes.length; i++)
			lavaTypes[i].fillStruct(uboStructs[i]);
		upload();
	}
}

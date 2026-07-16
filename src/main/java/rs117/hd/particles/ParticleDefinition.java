package rs117.hd.particles;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
class ParticleDefinition
{
	private int color = 0x96FF981F;
	private boolean colorFade = false;
	private int colorEnd = 0x96FF981F;
	private int colorFadeStart = 0;
	private Shape shape = Shape.DEFAULT;
	private int size = 6;
	private int sizeJitter = 0;
	private int particlesPerSecond = 24;
	private int trailDensity = 0;
	private int lifetimeMs = 600;
	private int riseSpeed = 12;
	private int spreadSpeed = 6;
	private int gravity = 0;
	private int windX = 0;
	private int windY = 0;
	private int windZ = 0;
	private int drag = 0;
	private int vortex = 0;
	private int stretch = 0;
	private int stretchRamp = 0;
	private int spawnJitter = 6;
	private int movementLifetime = 100;
	private String textureFile = "";
	private int flipbookColumns = 0;
	private int flipbookRows = 0;
	private String flipbookMode = null;

	private float rotationSpeed = 0f;

	private boolean useEnvironmentLight = false;

	private boolean uniformColorVariation = true;

	private int scaleStartPercent = 100;

	private int scaleEndPercent = 100;

	ParticleDefinition()
	{
	}

	ParticleDefinition copy()
	{
		ParticleDefinition c = new ParticleDefinition();
		copyFieldsTo(c);
		return c;
	}

	void copyFieldsFrom(ParticleDefinition other)
	{
		copyFieldsTo(this, other);
	}

	private void copyFieldsTo(ParticleDefinition target)
	{
		copyFieldsTo(target, this);
	}

	private static void copyFieldsTo(ParticleDefinition target, ParticleDefinition source)
	{
		target.color = source.color;
		target.colorFade = source.colorFade;
		target.colorEnd = source.colorEnd;
		target.colorFadeStart = source.colorFadeStart;
		target.shape = source.shape;
		target.size = source.size;
		target.sizeJitter = source.sizeJitter;
		target.particlesPerSecond = source.particlesPerSecond;
		target.trailDensity = source.trailDensity;
		target.lifetimeMs = source.lifetimeMs;
		target.riseSpeed = source.riseSpeed;
		target.spreadSpeed = source.spreadSpeed;
		target.gravity = source.gravity;
		target.windX = source.windX;
		target.windY = source.windY;
		target.windZ = source.windZ;
		target.drag = source.drag;
		target.vortex = source.vortex;
		target.stretch = source.stretch;
		target.stretchRamp = source.stretchRamp;
		target.spawnJitter = source.spawnJitter;
		target.movementLifetime = source.movementLifetime;
		target.textureFile = source.textureFile;
		target.flipbookColumns = source.flipbookColumns;
		target.flipbookRows = source.flipbookRows;
		target.flipbookMode = source.flipbookMode;
		target.rotationSpeed = source.rotationSpeed;
		target.useEnvironmentLight = source.useEnvironmentLight;
		target.uniformColorVariation = source.uniformColorVariation;
		target.scaleStartPercent = source.scaleStartPercent;
		target.scaleEndPercent = source.scaleEndPercent;
	}

	static ParticleDefinition fromProfile(EmitterProfile profile)
	{
		ParticleDefinition def = new ParticleDefinition();
		def.setColor(profile.getColor());
		def.setColorFade(profile.isColorFade());
		def.setColorEnd(profile.getColorEnd());
		def.setColorFadeStart(profile.getColorFadeStart());
		def.setShape(profile.getShape() == null ? Shape.DEFAULT : profile.getShape());
		def.setSize(profile.getSize());
		def.setSizeJitter(profile.getSizeJitter());
		def.setParticlesPerSecond(profile.getParticlesPerSecond());
		def.setTrailDensity(profile.getTrailDensity());
		def.setLifetimeMs(profile.getLifetimeMs());
		def.setRiseSpeed(profile.getRiseSpeed());
		def.setSpreadSpeed(profile.getSpreadSpeed());
		def.setGravity(profile.getGravity());
		def.setWindX(profile.getWindX());
		def.setWindY(profile.getWindY());
		def.setWindZ(profile.getWindZ());
		def.setDrag(profile.getDrag());
		def.setVortex(profile.getVortex());
		def.setStretch(profile.getStretch());
		def.setStretchRamp(profile.getStretchRamp());
		def.setSpawnJitter(profile.getSpawnJitter());
		def.setMovementLifetime(profile.getMovementLifetime());
		def.setTextureFile(profile.getTextureFile() == null ? "" : profile.getTextureFile());
		def.setFlipbookColumns(profile.getFlipbookColumns());
		def.setFlipbookRows(profile.getFlipbookRows());
		def.setFlipbookMode(profile.getFlipbookMode());
		def.setRotationSpeed(profile.getRotationSpeed());
		def.setUseEnvironmentLight(profile.isUseEnvironmentLight());
		def.setUniformColorVariation(profile.isUniformColorVariation());
		def.setScaleStartPercent(profile.getScaleStartPercent());
		def.setScaleEndPercent(profile.getScaleEndPercent());
		return def;
	}

	void applyToProfile(EmitterProfile profile)
	{
		profile.setColor(color);
		profile.setColorFade(colorFade);
		profile.setColorEnd(colorEnd);
		profile.setColorFadeStart(colorFadeStart);
		profile.setShape(shape == null ? Shape.DEFAULT : shape);
		profile.setSize(size);
		profile.setSizeJitter(sizeJitter);
		profile.setParticlesPerSecond(particlesPerSecond);
		profile.setTrailDensity(trailDensity);
		profile.setLifetimeMs(lifetimeMs);
		profile.setRiseSpeed(riseSpeed);
		profile.setSpreadSpeed(spreadSpeed);
		profile.setGravity(gravity);
		profile.setWindX(windX);
		profile.setWindY(windY);
		profile.setWindZ(windZ);
		profile.setDrag(drag);
		profile.setVortex(vortex);
		profile.setStretch(stretch);
		profile.setStretchRamp(stretchRamp);
		profile.setSpawnJitter(spawnJitter);
		profile.setMovementLifetime(movementLifetime);
		profile.setTextureFile(textureFile == null ? "" : textureFile);
		profile.setFlipbookColumns(flipbookColumns);
		profile.setFlipbookRows(flipbookRows);
		profile.setFlipbookMode(flipbookMode);
		profile.setRotationSpeed(rotationSpeed);
		profile.setUseEnvironmentLight(useEnvironmentLight);
		profile.setUniformColorVariation(uniformColorVariation);
		profile.setScaleStartPercent(scaleStartPercent);
		profile.setScaleEndPercent(scaleEndPercent);
	}
}

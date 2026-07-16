package rs117.hd.particles;

import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
class ParticleEmitter
{
	private String targetType = EmitterProfile.TARGET_PLAYER;
	private int projectileId = -1;
	private int objectId = -1;
	private int npcId = -1;
	private int graphicId = -1;
	private String signature;
	private boolean enabled = true;
	private boolean wip = false;
	private Set<Integer> vertices = new HashSet<>();
	private Set<Integer> faces = new HashSet<>();
	private Set<Integer> itemIds = new HashSet<>();
	private Set<Integer> animationIds = new HashSet<>();
	private String animFrames = "";
	private int emitScale = 100;
	private int featherStrength = 0;
	private int interpolation = 0;
	private int depthBias = 0;
	private int offsetX = 0;
	private int offsetY = 0;
	private int offsetZ = 0;
	private String definitionId;

	static ParticleEmitter fromProfile(EmitterProfile profile)
	{
		ParticleEmitter emitter = new ParticleEmitter();
		emitter.targetType = profile.getTargetType();
		emitter.projectileId = profile.getProjectileId();
		emitter.objectId = profile.getObjectId();
		emitter.npcId = profile.getNpcId();
		emitter.graphicId = profile.getGraphicId();
		emitter.signature = profile.getSignature();
		emitter.enabled = profile.isEnabled();
		emitter.wip = profile.isWip();
		emitter.vertices = new HashSet<>(profile.getVertices());
		emitter.faces = new HashSet<>(profile.getFaces());
		emitter.itemIds = new HashSet<>(profile.getItemIds());
		emitter.animationIds = new HashSet<>(profile.getAnimationIds());
		emitter.animFrames = profile.getAnimFrames();
		emitter.emitScale = profile.getEmitScale();
		emitter.featherStrength = profile.getFeatherStrength();
		emitter.interpolation = profile.getInterpolation();
		emitter.depthBias = profile.getDepthBias();
		emitter.offsetX = profile.getOffsetX();
		emitter.offsetY = profile.getOffsetY();
		emitter.offsetZ = profile.getOffsetZ();
		emitter.definitionId = profile.getDefinitionId();
		return emitter;
	}

	void applyToProfile(EmitterProfile profile)
	{
		profile.setTargetType(targetType == null ? EmitterProfile.TARGET_PLAYER : targetType);
		profile.setProjectileId(projectileId);
		profile.setObjectId(objectId);
		profile.setNpcId(npcId);
		profile.setGraphicId(graphicId);
		profile.setSignature(signature);
		profile.setEnabled(enabled);
		profile.setWip(wip);
		profile.setVertices(new HashSet<>(vertices));
		profile.setFaces(faces == null ? new HashSet<>() : new HashSet<>(faces));
		profile.setItemIds(new HashSet<>(itemIds));
		profile.setAnimationIds(new HashSet<>(animationIds));
		profile.setAnimFrames(animFrames == null ? "" : animFrames);
		profile.setEmitScale(emitScale);
		profile.setFeatherStrength(featherStrength);
		profile.setInterpolation(interpolation);
		profile.setDepthBias(depthBias);
		profile.setOffsetX(offsetX);
		profile.setOffsetY(offsetY);
		profile.setOffsetZ(offsetZ);
		profile.setDefinitionId(definitionId);
	}

	EmitterProfile toProfile()
	{
		EmitterProfile profile = new EmitterProfile();
		applyToProfile(profile);
		return profile;
	}
}

package rs117.hd.particles.effector;

import lombok.Value;

@Value
public class ActiveEffectorState {
	String id;
	float x;
	float y;
	float z;
	EffectorDefinition def;
}

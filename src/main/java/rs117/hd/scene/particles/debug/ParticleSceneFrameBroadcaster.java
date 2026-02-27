/*
 * Sends particle scene data to the debug plugin via PluginMessage.
 * Projects to screen in core (proven to work); debug plugin can switch to local projection later.
 */
package rs117.hd.scene.particles.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.events.BeforeRender;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.HdPlugin;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.particles.ParticleBuffer;
import rs117.hd.scene.particles.ParticleManager;
import rs117.hd.scene.particles.emitter.ParticleEmitter;
import rs117.hd.utils.Mat4;

import static rs117.hd.utils.MathUtils.round;

@Singleton
public class ParticleSceneFrameBroadcaster {

	private static final String NAMESPACE = "117hd";
	private static final String FRAME_MSG = "particle-scene-frame";

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ParticleManager particleManager;

	private boolean gizmoOverlayActive;
	private boolean debugOverlayActive;

	public void setGizmoOverlayActive(boolean active) {
		gizmoOverlayActive = active;
	}

	public void setDebugOverlayActive(boolean active) {
		debugOverlayActive = active;
	}

	@Subscribe
	public void onBeforeRender(BeforeRender e) {
		if (!plugin.isActive() || (!gizmoOverlayActive && !debugOverlayActive))
			return;

		SceneContext ctx = plugin.getSceneContext();
		if (ctx == null || ctx.sceneBase == null)
			return;

		int currentPlane = client.getTopLevelWorldView() != null
			? client.getTopLevelWorldView().getPlane()
			: 0;

		float[] proj = buildProjectionMatrix();
		float[] pos = new float[3];
		int[] planeOut = new int[1];
		float[] point = new float[4];

		List<Map<String, Object>> emittersList = new ArrayList<>();
		List<ParticleEmitter> sceneEmitters = particleManager.getSceneEmitters();
		for (int i = 0; i < sceneEmitters.size(); i++) {
			ParticleEmitter em = sceneEmitters.get(i);
			if (!particleManager.getEmitterSpawnPosition(ctx, em, pos, planeOut))
				continue;
			if (planeOut[0] != currentPlane)
				continue;
			point[0] = pos[0];
			point[1] = pos[1];
			point[2] = pos[2];
			point[3] = 1f;
			Mat4.projectVec(point, proj, point);
			if (point[3] <= 0)
				continue;
			Map<String, Object> m = new HashMap<>();
			m.put("sx", round(point[0]));
			m.put("sy", round(point[1]));
			m.put("plane", planeOut[0]);
			m.put("particleId", em.getParticleId() != null ? em.getParticleId() : "");
			m.put("active", em.isActive());
			m.put("index", i);
			emittersList.add(m);
		}

		List<Map<String, Object>> particlesList = new ArrayList<>();
		ParticleBuffer buf = particleManager.getParticleBuffer();
		int[] cameraShift = plugin.cameraShift;
		for (int i = 0; i < buf.count; i++) {
			if (buf.plane[i] != currentPlane)
				continue;
			ParticleEmitter em = buf.emitter[i];
			String pid = em != null && em.getParticleId() != null ? em.getParticleId() : "";
			point[0] = buf.posX[i] + cameraShift[0];
			point[1] = buf.posY[i];
			point[2] = buf.posZ[i] + cameraShift[1];
			point[3] = 1f;
			Mat4.projectVec(point, proj, point);
			if (point[3] <= 0)
				continue;
			Map<String, Object> m = new HashMap<>();
			m.put("sx", round(point[0]));
			m.put("sy", round(point[1]));
			m.put("plane", buf.plane[i]);
			m.put("particleId", pid);
			particlesList.add(m);
		}

		Map<String, Object> payload = new HashMap<>();
		payload.put("currentPlane", currentPlane);
		payload.put("emitters", emittersList);
		payload.put("particles", particlesList);
		eventBus.post(new net.runelite.client.events.PluginMessage(NAMESPACE, FRAME_MSG, payload));
	}

	private float[] buildProjectionMatrix() {
		float[] m = Mat4.identity();
		int vx, vy, vw, vh;
		if (plugin.sceneViewport != null) {
			vx = plugin.sceneViewport[0];
			vy = plugin.sceneViewport[1];
			vw = plugin.sceneViewport[2];
			vh = plugin.sceneViewport[3];
		} else {
			vx = client.getViewportXOffset();
			vy = client.getViewportYOffset();
			vw = client.getViewportWidth();
			vh = client.getViewportHeight();
		}
		Mat4.mul(m, Mat4.translate(vx, vy, 0));
		Mat4.mul(m, Mat4.scale(vw, vh, 1));
		Mat4.mul(m, Mat4.translate(.5f, .5f, .5f));
		Mat4.mul(m, Mat4.scale(.5f, -.5f, .5f));
		Mat4.mul(m, plugin.viewProjMatrix);
		return m;
	}
}

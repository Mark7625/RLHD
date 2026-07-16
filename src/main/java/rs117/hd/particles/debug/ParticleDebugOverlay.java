package rs117.hd.particles.debug;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import rs117.hd.HdPlugin;
import rs117.hd.particles.Particle;
import rs117.hd.particles.ParticlesManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.Mat4;

import static rs117.hd.utils.MathUtils.round;

@Singleton
public class ParticleDebugOverlay extends Overlay
{
	private static final int PARTICLE_DOT_R = 2;
	private static final Color PARTICLE_COLOR = new Color(255, 255, 255, 220);

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ParticlesManager particlesManager;

	public ParticleDebugOverlay()
	{
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
	}

	public void setActive(boolean active)
	{
		if (active)
		{
			overlayManager.add(this);
		}
		else
		{
			overlayManager.remove(this);
		}
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		SceneContext ctx = plugin.getSceneContext();
		if (ctx == null || ctx.sceneBase == null)
		{
			return null;
		}

		float[] proj = Mat4.identity();
		int vw;
		int vh;
		int vx;
		int vy;
		if (plugin.sceneViewport != null)
		{
			vx = plugin.sceneViewport[0];
			vy = plugin.sceneViewport[1];
			vw = plugin.sceneViewport[2];
			vh = plugin.sceneViewport[3];
		}
		else
		{
			vx = client.getViewportXOffset();
			vy = client.getViewportYOffset();
			vw = client.getViewportWidth();
			vh = client.getViewportHeight();
		}
		Mat4.mul(proj, Mat4.translate(vx, vy, 0));
		Mat4.mul(proj, Mat4.scale(vw, vh, 1));
		Mat4.mul(proj, Mat4.translate(.5f, .5f, .5f));
		Mat4.mul(proj, Mat4.scale(.5f, -.5f, .5f));
		Mat4.mul(proj, plugin.viewProjMatrix);

		float[] point = new float[4];
		int[] cameraShift = plugin.cameraShift;
		List<Particle> particles = particlesManager.liveParticles();
		g.setColor(PARTICLE_COLOR);
		for (Particle p : particles)
		{
			point[0] = p.getX() + cameraShift[0];
			point[1] = p.getZ();
			point[2] = p.getY() + cameraShift[1];
			point[3] = 1f;
			Mat4.projectVec(point, proj, point);
			if (point[3] <= 0)
			{
				continue;
			}
			int sx = round(point[0]);
			int sy = round(point[1]);
			g.fillOval(sx - PARTICLE_DOT_R, sy - PARTICLE_DOT_R, PARTICLE_DOT_R * 2, PARTICLE_DOT_R * 2);
		}
		return null;
	}
}

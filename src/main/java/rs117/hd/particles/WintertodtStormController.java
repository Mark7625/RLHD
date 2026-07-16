package rs117.hd.particles;

import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.particles.effector.EffectorBuilder;
import rs117.hd.particles.effector.EffectorDefinitionManager;
import rs117.hd.particles.effector.EffectorRef;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.areas.Area;
@Singleton
public class WintertodtStormController
{
	private static final String OWNER_ID = "wintertodt_storm";
	private static final EffectorRef STORM = EffectorRef.json("WINTERTODT_STORM");
	private static final EffectorRef STORM_WIND = EffectorBuilder.create("WINTERTODT_STORM_WIND")
		.radiusTiles(4f)
		.heightOffset(600)
		.wind(9000f, 400f, -1900f, 2.25f, 1.0f, 0f, 12f)
		.edgeFalloff(true)
		.falloffPower(1.0f)
		.buildRef();

	private static final int STORM_VORTEX_X = 1630;
	private static final int STORM_VORTEX_Y = 4009;
	private static final int STORM_VORTEX_PLANE = 0;
	private static final int[][] STORM_WIND_PLACEMENTS = {
		{ 1620, 3996, 0 },
		{ 1641, 3997, 0 },
	};

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private EffectorDefinitionManager effectorDefinitions;

	@Inject
	private AreaManager areaManager;

	@Inject
	private rs117.hd.HdPlugin hdPlugin;

	private boolean registered;
	private boolean stormActive;

	public void startUp()
	{
		if (!registered)
		{
			eventBus.register(this);
			registered = true;
		}
		resetStorm();
	}

	public void shutDown()
	{
		if (registered)
		{
			eventBus.unregister(this);
			registered = false;
		}
		resetStorm();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!inWintertodtView())
		{
			if (stormActive)
			{
				stormActive = false;
				resetStorm();
			}
			return;
		}

		Widget widget = client.getWidget(InterfaceID.WintStatus.ENERGY_TITLE);
		boolean winterToadSpawned = widget != null
			&& widget.getText() != null
			&& widget.getText().contains("%");

		if (winterToadSpawned == stormActive)
		{
			return;
		}
		stormActive = winterToadSpawned;
		applyStormPlacements();
	}

	private boolean inWintertodtView()
	{
		SceneContext ctx = hdPlugin.getSceneContext();
		if (ctx == null)
		{
			return false;
		}
		Area area = areaManager.getArea("WINTERTODT_ARENA");
		return area != null && area != Area.NONE && ctx.intersects(area);
	}

	private void resetStorm()
	{
		effectorDefinitions.clearRuntimePlacements(OWNER_ID);
	}

	private void applyStormPlacements()
	{
		resetStorm();
		if (!stormActive)
		{
			return;
		}
		effectorDefinitions.addRuntimePlacement(
			OWNER_ID, STORM_VORTEX_X, STORM_VORTEX_Y, STORM_VORTEX_PLANE, STORM.id());
		for (int[] wind : STORM_WIND_PLACEMENTS)
		{
			effectorDefinitions.addRuntimePlacement(OWNER_ID, wind[0], wind[1], wind[2], STORM_WIND.id());
		}
	}

	public static List<String> stormLocalFilter()
	{
		return List.of(STORM.id());
	}

	public static List<String> stormGlobalEffectors()
	{
		return List.of(STORM_WIND.id());
	}
}

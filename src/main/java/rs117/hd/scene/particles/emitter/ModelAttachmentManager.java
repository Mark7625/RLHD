/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.HdPlugin;
import rs117.hd.scene.particles.ParticleManager;

@Slf4j
@Singleton
public class ModelAttachmentManager {
	private static final int INVALID_KEY = Integer.MIN_VALUE;
	private static final int APPEARANCE_ITEM_OFFSET = 512;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private ParticleManager particleManager;

	@Inject
	private EmitterDefinitionManager emitterDefinitionManager;

	@Inject
	private HdPlugin plugin;

	private final Map<Integer, Model> referenceWearModels = new HashMap<>();
	private final Map<Integer, ModelAttachmentEmitter> emittersByPlayerIndex = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> attachedEquipmentKeys = new ConcurrentHashMap<>();
	private boolean loggedConfigState;

	public void startUp() {
		eventBus.register(this);
	}

	public void shutDown() {
		eventBus.unregister(this);
		clearAll();
	}

	public void reloadReferenceSignatures() {
		referenceWearModels.clear();
		attachedEquipmentKeys.clear();
		loggedConfigState = false;

		for (ModelAttachmentConfig config : emitterDefinitionManager.getModelAttachmentConfigs()) {
			if (config == null)
				continue;
			Model referenceModel = loadReferenceModel(config);
			if (referenceModel != null) {
				referenceWearModels.put(config.getItemId(), referenceModel);
			} else {
				log.warn(
					"[Particles] Failed to load reference model for model attachment item {} (wearModelId={})",
					config.getItemId(),
					config.getWearModelId()
				);
			}
		}

		log.info(
			"[Particles] Loaded {} model attachment config(s), {} reference model(s)",
			emitterDefinitionManager.getModelAttachmentConfigs().size(),
			referenceWearModels.size()
		);
	}

	public List<ModelAttachmentEmitter> getModelAttachmentEmitters() {
		return new ArrayList<>(emittersByPlayerIndex.values());
	}

	/** Drops attachments when the configured item is no longer worn. */
	public void onTick() {
		if (!particleManager.areParticlesEnabled())
			return;

		for (ModelAttachmentEmitter emitter : new ArrayList<>(emittersByPlayerIndex.values())) {
			if (!(emitter.getActor() instanceof Player))
				continue;
			Player player = (Player) emitter.getActor();
			if (player.isDead() || findConfigForPlayer(player) == null)
				removePlayer(player);
		}
	}

	public void onPlayerModelUploaded(
		Player player,
		Model model,
		int orientation,
		int x,
		int y,
		int z,
		int plane
	) {
		onPlayerModelUploaded(player, model, orientation, x, y, z, plane, null);
	}

	/**
	 * Updates cape attachments for a drawn player model.
	 * When {@code transformedVertices} is set (zone renderer after preprocess), spawn positions
	 * match the same transformed coords written to the dynamic model vertex buffer.
	 */
	public void onPlayerModelUploaded(
		Player player,
		Model model,
		int orientation,
		int x,
		int y,
		int z,
		int plane,
		@Nullable float[] transformedVertices
	) {
		if (player == null || model == null)
			return;

		if (!client.isClientThread()) {
			clientThread.invoke(() -> onPlayerModelUploaded(
				player, model, orientation, x, y, z, plane, transformedVertices
			));
			return;
		}

		if (!particleManager.areParticlesEnabled())
			return;

		ModelAttachmentConfig config = findConfigForPlayer(player);
		if (config == null) {
			removePlayer(player);
			return;
		}

		int playerIndex = player.getId();
		ModelAttachmentEmitter emitter = emittersByPlayerIndex.computeIfAbsent(
			playerIndex,
			idx -> createEmitter(player, config)
		);

		emitter.setDrawX(x);
		emitter.setDrawY(y);
		emitter.setDrawZ(z);
		emitter.setDrawOrientation(orientation);
		emitter.setDrawPlane(plane);
		emitter.setFrameModel(model);
		WorldPoint wp = player.getWorldLocation();
		if (wp != null)
			emitter.at(wp);

		int equipmentKey = buildEquipmentKey(player);
		Integer attachedKey = attachedEquipmentKeys.get(playerIndex);
		if (attachedKey == null || attachedKey != equipmentKey || !emitter.isReady()) {
			Model wearModel = referenceWearModels.get(config.getItemId());
			int searchStart = computeSearchStart(player.getPlayerComposition(), config.getKitType());
			int matchOffset = EquipmentModelSignature.resolveSlotOffset(model, wearModel, searchStart);
			if (matchOffset < 0 || wearModel == null) {
				log.info(
					"[Particles] Model attachment offset invalid for player {} item {} (playerVerts={}, wearVerts={}, searchStart={})",
					player.getName(),
					config.getItemId(),
					model.getVerticesCount(),
					wearModel != null ? wearModel.getVerticesCount() : -1,
					searchStart
				);
				return;
			}

			emitter.setAttachments(matchOffset, config.getAttachments(), particleManager);
			applyEffectors(emitter, config);
			attachedEquipmentKeys.put(playerIndex, equipmentKey);
			particleManager.addEmitter(emitter);
			log.info(
				"[Particles] Attached model emitter for player {} item {} at vertex {} (wearModelId={}, searchStart={})",
				player.getName(),
				config.getItemId(),
				matchOffset,
				config.getWearModelId(),
				searchStart
			);
		}

		if (emitter.isReady()) {
			if (transformedVertices != null)
				emitter.tickAttachments(plugin.deltaTime, client.getGameCycle(), particleManager, transformedVertices, model.getVerticesCount());
			else
				emitter.tickAttachments(plugin.deltaTime, client.getGameCycle(), particleManager);
		}
	}

	private ModelAttachmentEmitter createEmitter(Player player, ModelAttachmentConfig config) {
		WorldPoint wp = player.getWorldLocation();
		if (wp == null)
			wp = new WorldPoint(0, 0, 0);
		return new ModelAttachmentEmitter(player, config.getItemId(), wp);
	}

	private void applyEffectors(ModelAttachmentEmitter emitter, ModelAttachmentConfig config) {
		emitter.setGlobalEffectors(config.getGlobalEffectors() != null ? config.getGlobalEffectors() : List.of());
		emitter.setEmbeddedEffectors(config.getEmbeddedEffectors() != null ? config.getEmbeddedEffectors() : List.of());
		emitter.setLocalEffectorFilter(config.getLocalEffectorFilter() != null ? config.getLocalEffectorFilter() : List.of());
	}

	@Nullable
	private ModelAttachmentConfig findConfigForPlayer(Player player) {
		List<ModelAttachmentConfig> configs = emitterDefinitionManager.getModelAttachmentConfigs();
		if (configs.isEmpty()) {
			logConfigStateOnce(player, null, null);
			return null;
		}

		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null) {
			logConfigStateOnce(player, configs, null);
			return null;
		}

		int[] equipmentIds = composition.getEquipmentIds();
		for (ModelAttachmentConfig config : configs) {
			if (config == null)
				continue;

			int preferredSlot = config.getKitType().getIndex();
			if (preferredSlot >= 0 && preferredSlot < equipmentIds.length &&
				itemIdMatches(equipmentIds[preferredSlot], config.getItemId())) {
				return config;
			}

			for (int wornId : equipmentIds) {
				if (itemIdMatches(wornId, config.getItemId()))
					return config;
			}

			if (player == client.getLocalPlayer() && localWornInventoryMatches(config.getItemId(), preferredSlot))
				return config;
		}

		logConfigStateOnce(player, configs, equipmentIds);
		return null;
	}

	private boolean localWornInventoryMatches(int configItemId, int preferredSlot) {
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
			return false;

		Item[] items = worn.getItems();
		if (items == null)
			return false;

		if (preferredSlot >= 0 && preferredSlot < items.length) {
			Item item = items[preferredSlot];
			if (item != null && itemIdMatches(item.getId(), configItemId))
				return true;
		}

		for (Item item : items) {
			if (item != null && itemIdMatches(item.getId(), configItemId))
				return true;
		}
		return false;
	}

	private boolean itemIdMatches(int wornItemId, int configItemId) {
		if (wornItemId <= 0 || configItemId <= 0)
			return false;

		if (wornItemId == configItemId)
			return true;

		int normalizedWorn = normalizeAppearanceItemId(wornItemId);
		if (normalizedWorn == configItemId)
			return true;

		if (wornItemId == configItemId + APPEARANCE_ITEM_OFFSET)
			return true;

		ItemComposition wornItem = client.getItemDefinition(normalizedWorn > 0 ? normalizedWorn : wornItemId);
		ItemComposition configItem = client.getItemDefinition(configItemId);
		if (wornItem == null || configItem == null)
			return false;

		if (wornItem.getId() == configItemId || configItem.getId() == normalizedWorn)
			return true;

		int wornLinked = wornItem.getLinkedNoteId();
		int configLinked = configItem.getLinkedNoteId();
		return wornLinked == configItemId || configLinked == normalizedWorn || wornLinked == configLinked;
	}

	private static int normalizeAppearanceItemId(int appearanceId) {
		if (appearanceId >= APPEARANCE_ITEM_OFFSET)
			return appearanceId - APPEARANCE_ITEM_OFFSET;
		return appearanceId;
	}

	private void logConfigStateOnce(Player player, @Nullable List<ModelAttachmentConfig> configs, @Nullable int[] equipmentIds) {
		if (loggedConfigState || player != client.getLocalPlayer())
			return;
		loggedConfigState = true;

		if (configs == null || configs.isEmpty()) {
			log.warn("[Particles] Model attachment: no configs loaded from emitters.json");
			return;
		}

		if (equipmentIds == null) {
			log.warn("[Particles] Model attachment: local player has no composition");
			return;
		}

		StringBuilder sb = new StringBuilder();
		for (ModelAttachmentConfig config : configs) {
			if (config == null)
				continue;
			sb.append(config.getItemId()).append('@').append(config.getKitType()).append(' ');
		}

		log.warn(
			"[Particles] Model attachment: no config matched local player. configs=[{}], equipment={}, capeSlotRaw={}, capeItemId={}",
			sb,
			Arrays.toString(equipmentIds),
			equipmentIds.length > KitType.CAPE.getIndex() ? equipmentIds[KitType.CAPE.getIndex()] : -1,
			equipmentIds.length > KitType.CAPE.getIndex() ?
				normalizeAppearanceItemId(equipmentIds[KitType.CAPE.getIndex()]) : -1
		);
	}

	private int buildEquipmentKey(Player player) {
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
			return INVALID_KEY;
		int hash = 1;
		for (int equipmentId : composition.getEquipmentIds())
			hash = 31 * hash + equipmentId;
		return hash;
	}

	private int computeSearchStart(@Nullable PlayerComposition composition, KitType kitType) {
		if (composition == null || kitType == null)
			return 0;

		int offset = 0;
		int[] equipmentIds = composition.getEquipmentIds();
		int endSlot = Math.min(kitType.getIndex(), equipmentIds.length);
		for (int slot = 0; slot < endSlot; slot++)
			offset += estimatePartVertexCount(equipmentIds[slot]);
		return offset;
	}

	private int estimatePartVertexCount(int part) {
		Model partModel = loadPartModel(part);
		return partModel != null ? partModel.getVerticesCount() : 0;
	}

	@Nullable
	private Model loadPartModel(int part) {
		if (part <= 0)
			return null;

		if (part >= APPEARANCE_ITEM_OFFSET) {
			ItemComposition item = client.getItemDefinition(part - APPEARANCE_ITEM_OFFSET);
			if (item != null) {
				Model itemModel = client.loadModel(item.getInventoryModel());
				if (itemModel != null)
					return itemModel;
			}
		}

		Model kitModel = client.loadModel(part);
		if (kitModel == null && part >= PlayerComposition.KIT_OFFSET)
			kitModel = client.loadModel(part - PlayerComposition.KIT_OFFSET);
		if (kitModel == null && part < PlayerComposition.KIT_OFFSET)
			kitModel = client.loadModel(part + PlayerComposition.KIT_OFFSET);
		return kitModel;
	}

	@Nullable
	private Model loadReferenceModel(ModelAttachmentConfig config) {
		if (config.getWearModelId() > 0) {
			Model model = client.loadModel(config.getWearModelId());
			if (model != null)
				return model;
			log.warn("[Particles] Failed to load wear model {} for item {}", config.getWearModelId(), config.getItemId());
		}
		return loadReferenceModelForItem(config.getItemId());
	}

	@Nullable
	private Model loadReferenceModelForItem(int itemId) {
		ItemComposition item = client.getItemDefinition(itemId);
		if (item == null)
			return null;
		return client.loadModel(item.getInventoryModel());
	}

	private void clearAll() {
		for (ModelAttachmentEmitter emitter : emittersByPlayerIndex.values())
			detachEmitter(emitter);
		emittersByPlayerIndex.clear();
		attachedEquipmentKeys.clear();
	}

	private void removePlayer(Player player) {
		int playerIndex = player.getId();
		attachedEquipmentKeys.remove(playerIndex);
		ModelAttachmentEmitter emitter = emittersByPlayerIndex.remove(playerIndex);
		if (emitter != null)
			detachEmitter(emitter);
	}

	private void detachEmitter(ModelAttachmentEmitter emitter) {
		emitter.clearSpawnedParticles(particleManager);
		particleManager.removeEmitter(emitter);
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event) {
		removePlayer(event.getPlayer());
	}
}

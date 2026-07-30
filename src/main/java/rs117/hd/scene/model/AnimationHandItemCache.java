package rs117.hd.scene.model;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Animation;
import net.runelite.api.Client;

@Singleton
public class AnimationHandItemCache {
	public static final int HIDDEN_HAND_ITEM = -512;

	@Inject
	private Client client;

	public static boolean isHandHidden(int animHandItem) {
		return animHandItem == HIDDEN_HAND_ITEM;
	}

	public static boolean showsEquippedItem(int animHandItem, int equippedId) {
		if (equippedId == -1)
			return true;
		if (isHandHidden(animHandItem))
			return false;
		// -1 means the animation does not override the hand item
		if (animHandItem == -1)
			return true;
		return animHandItem == equippedId;
	}

	public static final class Entry {
		public final int rightHandItem;
		public final int leftHandItem;
		public final boolean hidesRightHand;
		public final boolean hidesLeftHand;

		private Entry(Animation animation) {
			rightHandItem = animation.getRightHandItem();
			leftHandItem = animation.getLeftHandItem();
			hidesRightHand = isHandHidden(rightHandItem);
			hidesLeftHand = isHandHidden(leftHandItem);
		}
	}

	private final Map<Integer, Entry> cache = new HashMap<>();

	@Nullable
	public Entry get(int animationId) {
		if (animationId == -1)
			return null;

		Entry entry = cache.get(animationId);
		if (entry != null)
			return entry;

		Animation animation = client.loadAnimation(animationId);
		if (animation == null)
			return null;

		entry = new Entry(animation);
		cache.put(animationId, entry);
		return entry;
	}

	public void clear() {
		cache.clear();
	}
}

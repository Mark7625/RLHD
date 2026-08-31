package rs117.hd.resourcepacks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/** Persistent user state for official resource packs. */
final class ResourcePackState {
	List<String> packOrder = new ArrayList<>();
	Map<String, String> sha256ByPack = new LinkedHashMap<>();
	Set<String> disabledPacks = new LinkedHashSet<>();
	Map<String, Map<String, AppliedSetting>> settingsByPack = new LinkedHashMap<>();

	static final class AppliedSetting {
		String previousValue;
		String appliedValue;
	}
}

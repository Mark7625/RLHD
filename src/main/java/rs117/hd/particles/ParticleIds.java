package rs117.hd.particles;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Stable id helpers for particle definitions: human-readable slugs from names,
 * with target-specific suffixes when names collide.
 */
final class ParticleIds
{
	private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
	private static final Pattern GENDER_SUFFIX = Pattern.compile("\\s*\\([mf]\\)\\s*$", Pattern.CASE_INSENSITIVE);

	private ParticleIds()
	{
	}

	static String slugifyName(String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			return "particle";
		}
		String trimmed = GENDER_SUFFIX.matcher(name.trim()).replaceAll("");
		String slug = NON_SLUG.matcher(trimmed.toLowerCase(Locale.ROOT)).replaceAll("_");
		while (slug.contains("__"))
		{
			slug = slug.replace("__", "_");
		}
		slug = stripEdges(slug, '_');
		return slug.isEmpty() ? "particle" : slug;
	}

	static String displayName(String slug)
	{
		if (slug == null || slug.isEmpty())
		{
			return "particle";
		}
		String[] parts = slug.split("_");
		StringBuilder sb = new StringBuilder();
		for (String part : parts)
		{
			if (part.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(part.charAt(0)));
			if (part.length() > 1)
			{
				sb.append(part.substring(1));
			}
		}
		return sb.length() == 0 ? slug : sb.toString();
	}

	/** True when a map key looks like a mesh topology signature, not a slug. */
	static boolean isMeshSignatureKey(@Nullable String key)
	{
		return key != null && key.matches(".*\\d+v\\d+f.*");
	}

	/** Slug to title-case for UI; uses definition id when the emitter key is a legacy signature. */
	static String emitterLabelKey(String emitterKey, @Nullable EmitterProfile profile)
	{
		if (profile != null)
		{
			String definitionId = profile.getDefinitionId();
			if (definitionId != null && !definitionId.isEmpty()
				&& (isMeshSignatureKey(emitterKey) || Objects.equals(emitterKey, profile.getSignature())))
			{
				return definitionId;
			}
		}
		return emitterKey;
	}

	/** True when the profile carries an authored display name, not a slug/signature placeholder. */
	static boolean hasProperEmitterName(@Nullable String name, String key, @Nullable String signature)
	{
		if (name == null || name.isEmpty())
		{
			return false;
		}
		if (Objects.equals(name, key) || Objects.equals(name, signature))
		{
			return false;
		}
		// Legacy auto labels like "328v 642f" or mesh signature strings
		return !name.matches(".*\\d+v\\d+f.*");
	}

	/** List/menu label: authored name when set, otherwise title-cased slug / definition id. */
	static String emitterListName(String emitterKey, @Nullable EmitterProfile profile)
	{
		if (profile != null
			&& hasProperEmitterName(profile.getName(), emitterKey, profile.getSignature()))
		{
			return profile.getName().trim();
		}
		return displayName(emitterLabelKey(emitterKey, profile));
	}

	static String definitionIdFor(EmitterProfile profile, Set<String> reserved)
	{
		return slugId(profile, reserved);
	}

	static String emitterKeyFor(EmitterProfile profile, Set<String> reserved)
	{
		return slugId(profile, reserved);
	}

	private static String slugId(EmitterProfile profile, Set<String> reserved)
	{
		String base = slugifyName(profile.getName());
		if (!reserved.contains(base))
		{
			return base;
		}
		String disambiguator = disambiguator(profile);
		String candidate = base + "_" + disambiguator;
		if (!reserved.contains(candidate))
		{
			return candidate;
		}
		int n = 2;
		while (reserved.contains(base + "_" + disambiguator + "_" + n))
		{
			n++;
		}
		return base + "_" + disambiguator + "_" + n;
	}

	private static String disambiguator(EmitterProfile profile)
	{
		if (profile.getItemIds() != null && profile.getItemIds().size() == 1)
		{
			return "i" + profile.getItemIds().iterator().next();
		}
		if (profile.getNpcId() >= 0)
		{
			return "npc" + profile.getNpcId();
		}
		if (profile.getObjectId() >= 0)
		{
			return "obj" + profile.getObjectId();
		}
		if (profile.getGraphicId() >= 0)
		{
			return "gfx" + profile.getGraphicId();
		}
		if (profile.getProjectileId() >= 0)
		{
			return "proj" + profile.getProjectileId();
		}
		if (profile.isWeatherTarget() && profile.getWeatherAreas() != null && !profile.getWeatherAreas().isEmpty())
		{
			return slugifyName(profile.getWeatherAreas().get(0));
		}
		String signature = profile.getSignature();
		if (signature != null && !signature.isEmpty())
		{
			int at = signature.indexOf('@');
			if (at >= 0)
			{
				return signature.substring(at + 1).replace(':', '_');
			}
			return signature.length() > 12 ? signature.substring(0, 12) : signature;
		}
		return "particle";
	}

	private static String stripEdges(String s, char c)
	{
		int start = 0;
		int end = s.length();
		while (start < end && s.charAt(start) == c)
		{
			start++;
		}
		while (end > start && s.charAt(end - 1) == c)
		{
			end--;
		}
		return s.substring(start, end);
	}
}

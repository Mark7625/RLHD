package rs117.hd.particles;

import lombok.Getter;
import lombok.Setter;

/**
 * A named group of emitter profiles shown as one collapsible row in the
 * sidebar. Its own enable and work-in-progress flags override those of its
 * member profiles, so end users control a whole multi-layer effect with a
 * single toggle. Membership lives on the profiles ({@link EmitterProfile#folderId});
 * this holds only the folder's own settings. Folders are single-category and
 * never nested, and always hold at least two members (enforced by the store).
 */
@Getter
@Setter
class ProfileFolder
{
	/**
	 * Stable generated id that member profiles reference by their folderId.
	 */
	private String id;
	/**
	 * Display name; adopts the target profile's name when the folder is created.
	 */
	private String name;
	/**
	 * Preference toggle. For shipped users this is the one folder setting they
	 * control, and it gates every member (a member emits only if both the folder
	 * and the member are enabled).
	 */
	private boolean enabled = true;
	/**
	 * Author's work-in-progress mark: hides and force-disables the whole folder
	 * for shipped users, taking priority over member settings.
	 */
	private boolean wip = false;

	ProfileFolder()
	{
	}

	ProfileFolder(String id, String name)
	{
		this.id = id;
		this.name = name;
	}

	ProfileFolder copy()
	{
		ProfileFolder c = new ProfileFolder(id, name);
		c.enabled = enabled;
		c.wip = wip;
		return c;
	}
}

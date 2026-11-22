/**
 * Single source of truth for the app version. Bump this to the release tag
 * (without the leading "v") whenever a release is tagged — the GitHub release
 * workflow refuses to publish when tag and this constant disagree, and the
 * self-updater uses it to decide whether a release is newer.
 */
public final class AppVersion {

    public static final String VERSION = "0.0.1";

    private AppVersion() { }
}

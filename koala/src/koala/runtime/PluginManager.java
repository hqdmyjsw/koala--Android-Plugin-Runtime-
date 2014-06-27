package koala.runtime;

import android.content.Context;

import java.io.File;

/**
 * pluginmanager��wrapper
 * @author zhaoxuyang
 *
 */
public class PluginManager {
	private static PluginManager mInstance;
	private static PluginManagerImpl mImpl;

	private PluginManager() {
		mImpl = PluginManagerImpl.getInstance();
	}

	public static PluginManager getInstance() {
		if (mInstance == null) {
			mInstance = new PluginManager();
		}
		return mInstance;
	}

	public void init(Context context, String dop) {
		mImpl.init(context, dop);
	}

	public void installPlugin(PluginInfo info, InstallPluginListener listener) {
		mImpl.installPlugin(info, listener);
	}

	public void scanApks(File dir, ScanPluginListener listener) {
		mImpl.scanApks(dir, listener);
	}

	public void setCurrentPlugin(String key) {
		mImpl.setCurrentPlugin(key);
	}

	public Plugin getCurrentPlugin() {
		return mImpl.getCurrentPlugin();
	}

	public void uninstallPlugin(String name) {
		mImpl.uninstallPlugin(name);
	}

	public boolean checkInstalled(String name) {
		return mImpl.checkInstalled(name);
	}
}
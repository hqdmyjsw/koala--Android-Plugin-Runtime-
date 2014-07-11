package android.app;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.ActivityIntentInfo;
import android.content.pm.PackageParser.ServiceIntentInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Toast;
import dalvik.system.DexClassLoader;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

/**
 * 真正pluginmanager的实现
 * 
 * @author zhaoxuyang
 * 
 */
class PluginManagerImpl {

	/**
	 * DEBUG
	 */
	private static final String TAG = "PLUGIN_MANAGER";

	/**
	 * 初始化主程序的context
	 */
	private Context mContext;

	/**
	 * 单例
	 */
	private static PluginManagerImpl mInstance;

	/**
	 * 所有已安装插件的hashmap
	 */
	private HashMap<String, Plugin> mPlugins = new HashMap<String, Plugin>();

	/**
	 * 所有插件信息
	 */
	private HashMap<String, PluginInfo> mPluginInfos = new HashMap<String, PluginInfo>();

	/**
	 * 主程序的ActivityThread
	 */
	public ActivityThread mActivityThread;

	/**
	 * 创建LoadedApk的方法，将插件注册到ActivityThread中
	 */
	public Method getPackageInfo;

	/**
	 * 用于给LoadedApk设置classloader
	 */
	public Field mClassLoader;

	/**
	 * ContextImpl类
	 */
	public Class contextImpl;

	/**
	 * ContextImpl的初始化方法
	 */
	public Method init;

	/**
	 * ContextImpl的setOuterContext方法
	 */
	public Method setOuterContext;

	/**
	 * classloader 的dex输出目录
	 */
	private String mDexoutputPath;

	/**
	 * 主程序原始的classloader，作为插件classloader的parent
	 */
	private ClassLoader mOriginalClassLoader;

	/**
	 * 用于和主线程通信
	 */
	private Handler mHandler;

	/**
	 * 代理broadcastreceiver
	 */
	private PluginBlankBroadcastReceiver mReceiver;

	/**
	 * 获取单例
	 * 
	 * @return 插件单例
	 */
	static PluginManagerImpl getInstance() {
		if (mInstance == null) {
			mInstance = new PluginManagerImpl();
		}
		return mInstance;
	}

	/**
	 * 初始化
	 * 
	 * @param context
	 *            上下文
	 * @param dop
	 *            插件dex的存放目录
	 */
	void init(ContextWrapper context, String dop) {
		mContext = context.getBaseContext();
		mDexoutputPath = dop;
		mHandler = new Handler(Looper.getMainLooper());
		Log.d(TAG, "start init environment");
		initEnvironment();
		Log.d(TAG, "after init environment");
	}

	/**
	 * 初始化环境，需要反射一些类，处理不同版本的差异等
	 * 
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void initEnvironment() {

		// 得到主程序的LoadedAPK
		LoadedApk packageInfo = null;
		Class packageClass = null;
		try {
			packageClass = LoadedApk.class;

			mClassLoader = packageClass.getDeclaredField("mClassLoader");
			mClassLoader.setAccessible(true);

			contextImpl = Class.forName("android.app.ContextImpl");

			Field f = contextImpl.getDeclaredField("mPackageInfo");
			f.setAccessible(true);

			init = contextImpl.getDeclaredMethod("init", LoadedApk.class,
					IBinder.class, ActivityThread.class);
			init.setAccessible(true);

			setOuterContext = contextImpl.getDeclaredMethod("setOuterContext",
					Context.class);
			setOuterContext.setAccessible(true);

			packageInfo = (LoadedApk) f.get(mContext);

			mOriginalClassLoader = (ClassLoader) mClassLoader.get(packageInfo);

			Log.d(TAG, "find LoadedApk class,is above 2.2");
		} catch (Exception e) {
			e.printStackTrace();
		}

		// 获得主程序的activitythread对象，并反射出getPackageInfo和startActivityNow两个方法。
		// getPackageInfo用于将插件加载到主程序的mPackages里面
		// startActivityNow 参照activitygroup的方法，用于启动activity。
		if (packageClass != null) {
			try {
				Field f = packageClass.getDeclaredField("mActivityThread");
				f.setAccessible(true);
				mActivityThread = (ActivityThread) f.get(packageInfo);

				try {
					Class clazz = Class
							.forName("android.content.res.CompatibilityInfo");
					getPackageInfo = mActivityThread
							.getClass()
							.getDeclaredMethod(
									"getPackageInfoNoCheck",
									new Class[] { ApplicationInfo.class, clazz });
					getPackageInfo.setAccessible(true);
					Log.d(TAG,
							"find method getPackageInfoNoCheck and os is high version");
				} catch (Exception e) {
					try {
						getPackageInfo = mActivityThread.getClass()
								.getDeclaredMethod("getPackageInfoNoCheck",
										new Class[] { ApplicationInfo.class });
						getPackageInfo.setAccessible(true);
						Log.d(TAG,
								"find method getPackageInfoNoCheck and os is low version");
					} catch (NoSuchMethodException e1) {
						e1.printStackTrace();
					}
				}

				mReceiver = new PluginBlankBroadcastReceiver();

			} catch (NoSuchFieldException e) {
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 安装插件并启动
	 * 
	 * @param info
	 *            插件信息
	 * @param listener
	 *            插件安装的回掉
	 */
	void installPlugin(final PluginInfo info,
			final InstallPluginListener listener) {

		if (!mPlugins.containsKey(info.name)) {
			beginInstall(info, listener);
			realInstallPluin(info);
			afterInstall(info, listener);
		}
	}

	/**
	 * 开始安装
	 * 
	 * @param info
	 *            插件信息
	 * @param listener
	 *            安装回掉
	 */
	private void beginInstall(final PluginInfo info,
			final InstallPluginListener listener) {
		if (listener != null) {
			listener.onInstallStart(info);
		}
	}

	/**
	 * 结束安装
	 * 
	 * @param info
	 *            插件信息
	 * @param listener
	 *            安装回掉
	 */
	private void afterInstall(final PluginInfo info,
			final InstallPluginListener listener) {
		if (listener != null) {
			listener.onInstallEnd(info);
		}
	}

	/**
	 * 启动插件
	 * 
	 * @param info
	 *            插件信息
	 */
	void startPlugin(PluginInfo info) {
		if (info == null) {
			return;
		}
		if (!mPlugins.containsKey(info.name)) {
			Log.e(TAG, "no plugin or not install");
			return;
		}
		String className = info.enterClass;
		Intent newIntent = new Intent(mContext, PluginBlankActivity.class);
		newIntent.putExtra(PluginBlankActivity.ACTIVITY_NAME, className);
		newIntent.putExtra(PluginBlankActivity.PLUGIN_NAME, info.name);
		newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		mContext.startActivity(newIntent);
	}

	/**
	 * 启动一个插件的内的activity
	 * 
	 * @param intent
	 *            请求信息
	 */
	void startPluginActivity(Intent intent) {

		Collection<PluginInfo> infos = mPluginInfos.values();
		Iterator<PluginInfo> iter = infos.iterator();
		ComponentName cn = intent.getComponent();
		String className = null;
		PluginInfo info = null;
		while (iter.hasNext()) {
			PluginInfo temp = iter.next();
			ArrayList<android.content.pm.PackageParser.Activity> activitys = temp.mPackageObj.activities;
			Iterator<android.content.pm.PackageParser.Activity> iter1 = activitys
					.iterator();
			while (iter1.hasNext()) {
				PackageParser.Activity activity = (PackageParser.Activity) iter1
						.next();
				if (cn != null) {
					if (cn.getClassName().equals(activity.className)
							&& cn.getPackageName().equals(
									temp.mPackageInfo.packageName)) {
						className = activity.className;
						info = temp;
						break;
					}
				} else {
					ArrayList<ActivityIntentInfo> intentinfos = activity.intents;
					Iterator<ActivityIntentInfo> i = intentinfos.iterator();
					while (i.hasNext()) {
						PackageParser.ActivityIntentInfo intentinfo = (PackageParser.ActivityIntentInfo) i
								.next();
						int res = intentinfo.match(intent.getAction(),
								intent.getType(), intent.getScheme(),
								intent.getData(), intent.getCategories(), "");
						if (res > 0) {
							className = activity.className;
							info = temp;
							break;
						}
					}
				}
			}
		}

		if (info != null) {
			installPlugin(info, null);
			Intent newIntent = new Intent(mContext, PluginBlankActivity.class);
			newIntent.putExtras(intent);
			newIntent.putExtra(PluginBlankActivity.ACTIVITY_NAME, className);
			newIntent.putExtra(PluginBlankActivity.PLUGIN_NAME, info.name);
			newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			mContext.startActivity(newIntent);
		} else {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			mContext.startActivity(intent);
		}

	}

	/**
	 * 启动一个插件的内的service
	 * 
	 * @param intent
	 *            请求信息
	 * @return 启动service的ComponentName
	 */
	ComponentName startPluginService(Intent intent) {

		ComponentName cn = deliverPluginService(intent,
				PluginBlankService.START_TYPE);
		if (cn != null) {
			return cn;
		}
		return mContext.startService(intent);

	}

	/**
	 * 关闭一个插件的内的service
	 * 
	 * @param intent
	 *            请求信息
	 * @return 是否成功
	 */
	boolean stopPluginService(Intent intent) {

		ComponentName cn = deliverPluginService(intent,
				PluginBlankService.STOP_TYPE);
		if (cn != null) {
			return true;
		}
		return mContext.stopService(intent);

	}

	/**
	 * 将请求派发给代理service
	 * 
	 * @param intent
	 *            请求信息
	 * @param type
	 *            请求类型
	 * @return ComponentName
	 */
	private ComponentName deliverPluginService(Intent intent, int type) {
		Collection<PluginInfo> infos = mPluginInfos.values();
		Iterator<PluginInfo> iter = infos.iterator();
		ComponentName cn = intent.getComponent();
		String className = null;
		PluginInfo info = null;
		ComponentName rescn = null;
		while (iter.hasNext()) {
			PluginInfo temp = iter.next();
			ArrayList<android.content.pm.PackageParser.Service> services = temp.mPackageObj.services;
			Iterator<android.content.pm.PackageParser.Service> iter1 = services
					.iterator();
			while (iter1.hasNext()) {
				PackageParser.Service service = (PackageParser.Service) iter1
						.next();
				if (cn != null) {
					if (cn.getClassName().equals(service.className)
							&& cn.getPackageName().equals(
									temp.mPackageInfo.packageName)) {
						className = service.className;
						info = temp;
						rescn = cn;
						break;
					}
				} else {
					ArrayList<ServiceIntentInfo> intentinfos = service.intents;
					Iterator<ServiceIntentInfo> i = intentinfos.iterator();
					while (i.hasNext()) {
						PackageParser.ServiceIntentInfo intentinfo = (PackageParser.ServiceIntentInfo) i
								.next();
						int res = intentinfo.match(intent.getAction(),
								intent.getType(), intent.getScheme(),
								intent.getData(), intent.getCategories(), "");
						if (res > 0) {
							className = service.className;
							info = temp;
							rescn = new ComponentName(
									info.mPackageInfo.packageName, className);
							break;
						}
					}
				}
			}
		}

		if (info != null) {
			installPlugin(info, null);
			Intent newIntent = new Intent(mContext, PluginBlankService.class);
			newIntent.putExtras(intent);
			newIntent.putExtra(PluginBlankService.SERVICE_NAME, className);
			newIntent.putExtra(PluginBlankService.PLUGIN_NAME, info.name);
			newIntent.putExtra(PluginBlankService.TYPE, type);
			mContext.startService(newIntent);
			return rescn;
		}
		return null;
	}

	/**
	 * 派发广播到插件
	 * 
	 * @param intent
	 *            收到的广播信息
	 */
	void onPluginReceive(Intent intent) {
		Iterator<Plugin> iter = mPlugins.values().iterator();
		while (iter.hasNext()) {
			Plugin plugin = iter.next();
			Iterator<LocalBroadcastManager> iter1 = plugin.mLocalBroadCastManagers
					.values().iterator();
			while (iter1.hasNext()) {
				LocalBroadcastManager manager = iter1.next();
				manager.sendBroadcast(intent);
			}
		}
	}

	/**
	 * 注册广播
	 * 
	 * @param context
	 *            上下文
	 * @param pluginname
	 *            插件名称
	 * @param receiver
	 *            receiver
	 * @param filter
	 *            filter
	 * @return Intent
	 */
	Intent registerReceiver(Context context, String pluginname,
			BroadcastReceiver receiver, IntentFilter filter) {
		Plugin plugin = getPlugin(pluginname);
		if (plugin != null) {
			LocalBroadcastManager manager = plugin.mLocalBroadCastManagers
					.get(context);
			if (manager == null) {
				manager = new LocalBroadcastManager(context);
				plugin.mLocalBroadCastManagers.put(context, manager);
			}
			manager.registerReceiver(receiver, filter);
		}
		return mContext.registerReceiver(mReceiver, filter);
	}

	/**
	 * 同上
	 * 
	 * @param context
	 *            context
	 * @param pluginname
	 *            pluginname
	 * @param receiver
	 *            receiver
	 * @param filter
	 *            filter
	 * @param broadcastPermission
	 *            broadcastPermission
	 * @param scheduler
	 *            scheduler
	 * @return Intent
	 */
	Intent registerReceiver(Context context, String pluginname,
			BroadcastReceiver receiver, IntentFilter filter,
			String broadcastPermission, Handler scheduler) {
		return registerReceiver(context, pluginname, receiver, filter);
	}

	/**
	 * 解除注册
	 * 
	 * @param context
	 *            context
	 * @param pluginname
	 *            pluginname
	 * @param receiver
	 *            receiver
	 */
	void unregisterReceiver(Context context, String pluginname,
			BroadcastReceiver receiver) {
		Plugin plugin = getPlugin(pluginname);
		if (plugin != null) {
			LocalBroadcastManager manager = plugin.mLocalBroadCastManagers
					.get(context);
			if (manager != null) {
				manager.unregisterReceiver(receiver);
			}
		}
	}

	/**
	 * 真正安装插件的代码，核心方法
	 * 
	 * @param info
	 *            插件信息
	 */
	private void realInstallPluin(PluginInfo info) {
		try {
			Plugin plugin = new Plugin();
			plugin.mPluginInfo = info;

			plugin.enterClass = info.enterClass;

			PackageInfo packageInfo = info.mPackageInfo;
			packageInfo.applicationInfo.uid = Process.myUid();
			packageInfo.applicationInfo.sourceDir = info.apkPath;
			packageInfo.applicationInfo.publicSourceDir = info.apkPath;
			packageInfo.applicationInfo.dataDir = mContext.getDir(info.name, 0)
					.getAbsolutePath();
			packageInfo.applicationInfo.flags &= ApplicationInfo.FLAG_HAS_CODE;

			LoadedApk realPackageInfo = null;
			try {
				realPackageInfo = (LoadedApk) getPackageInfo.invoke(
						mActivityThread, new Object[] {
								packageInfo.applicationInfo, null });
			} catch (Exception e) {
				realPackageInfo = (LoadedApk) getPackageInfo.invoke(
						mActivityThread,
						new Object[] { packageInfo.applicationInfo });
			}
			plugin.mRealPackageInfo = realPackageInfo;

			// 本地库的路径
			StringBuilder sb = new StringBuilder();
			int size = info.nativeLibraryPaths.size();
			for (int j = 0; j < size; j++) {
				sb.append(info.nativeLibraryPaths.get(j));
				if (j < size - 1) {
					sb.append(":");
				}
			}

			// 实例化插件的classloader
			DexClassLoader classLoader = new DexClassLoader(info.apkPath,
					mDexoutputPath, sb.toString(), mOriginalClassLoader);
			mClassLoader.set(realPackageInfo, classLoader);
			plugin.mClassLoader = classLoader;

			// 调用Application
			plugin.mApplication = realPackageInfo.makeApplication(false, null);
			if (plugin.mApplication instanceof PluginApplication) {
				PluginApplication pa = (PluginApplication) plugin.mApplication;
				pa.setPluginName(plugin.mPluginInfo.name);
			}
			plugin.mApplication.onCreate();

			Context context = plugin.mApplication.getApplicationContext();
			LocalBroadcastManager lbm = new LocalBroadcastManager(context);
			plugin.mLocalBroadCastManagers.put(context, lbm);

			// 注册manifest里面的receiver
			ArrayList<PackageParser.Activity> receivers = info.mPackageObj.receivers;
			for (int i = 0; i < receivers.size(); i++) {
				android.content.pm.PackageParser.Activity receiver = receivers
						.get(i);
				String receiverName = receiver.className;
				BroadcastReceiver broadcastreceiver = (BroadcastReceiver) plugin.mClassLoader
						.loadClass(receiverName).newInstance();
				ArrayList<ActivityIntentInfo> intentinfos = receiver.intents;
				for (int j = 0; j < intentinfos.size(); j++) {
					IntentFilter filter = intentinfos.get(j);
					lbm.registerReceiver(broadcastreceiver, filter);
					mContext.registerReceiver(mReceiver, filter);
				}
			}

			// 恭喜，插件安装了
			plugin.mPluginInfo.isInstalled = true;

			mPlugins.put(info.name, plugin);

		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 遍历目录下的所有插件
	 * 
	 * @param dir
	 *            dir
	 * @param listener
	 *            listener
	 */
	void scanApks(final File dir, final ScanPluginListener listener) {
		new Thread() {
			public void run() {
				mHandler.post(new Runnable() {
					public void run() {
						listener.onScanStart();
					}
				});
				File[] files = dir.listFiles();
				for (int i = 0; i < files.length; i++) {
					File file = files[i];
					String str = file.getName();
					// 已经有了 跳过
					if (mPluginInfos.containsKey(str)) {
						continue;
					}
					PluginInfo pluginInfo = new PluginInfo();
					pluginInfo.name = str;
					File[] files2 = file.listFiles();
					for (int j = 0; j < files2.length; j++) {
						file = files2[j];
						str = file.getName();
						if (str.toLowerCase().endsWith(".apk")) {
							pluginInfo.apkName = str;
							pluginInfo.apkPath = file.getAbsolutePath();
						} else if (str.equals("libs")) {
							File temp = new File(file, Build.CPU_ABI);
							if (temp.exists()) {
								pluginInfo.nativeLibraryPaths.add(temp
										.getAbsolutePath());
							}
						} else if (str.toLowerCase().endsWith(".enter")) {
							pluginInfo.enterClass = str.substring(0,
									str.length() - 6);
						}
					}

					if (pluginInfo.checkApk()) {
						getPackageInfo(pluginInfo);
						mPluginInfos.put(pluginInfo.name, pluginInfo);
					}
				}
				mHandler.post(new Runnable() {
					public void run() {
						listener.onScanEnd(new ArrayList<PluginInfo>(
								mPluginInfos.values()));
					}
				});
			}
		}.start();
	}

	/**
	 * 通过名称得到plugin
	 * 
	 * @param name
	 *            插件名称
	 * @return 插件
	 */
	Plugin getPlugin(String name) {
		return mPlugins.get(name);
	}

	/**
	 * 解析APK的manifest
	 * 
	 * @param info
	 *            插件信息
	 */
	private void getPackageInfo(PluginInfo info) {

		int flags = PackageManager.GET_ACTIVITIES
				| PackageManager.GET_CONFIGURATIONS
				| PackageManager.GET_INSTRUMENTATION
				| PackageManager.GET_PERMISSIONS | PackageManager.GET_PROVIDERS
				| PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES
				| PackageManager.GET_SIGNATURES;

		// 需要获取Package对象，主要是处理隐式启动插件中的activity
		PackageParser parser = new PackageParser(info.apkPath);
		DisplayMetrics metrics = new DisplayMetrics();
		metrics.setToDefaults();
		File sourceFile = new File(info.apkPath);
		PackageParser.Package pack = parser.parsePackage(sourceFile,
				info.apkPath, metrics, 0);

		// 因为PackagePaser的generatePackageInfo方法不同版本参数相差太多，所以还是用packagemanager的api
		// 但这样导致APK被解析了两次，上面获取Package是一次
		PackageInfo packageInfo = mContext.getPackageManager()
				.getPackageArchiveInfo(info.apkPath, flags);

		info.mPackageObj = pack;
		info.mPackageInfo = packageInfo;
	}

	/**
	 * 卸载插件
	 * 
	 * @param activity
	 *            外部的activity
	 * @param name
	 *            插件名称
	 */
	void uninstallPlugin(Activity activity, String name) {
		if (mPlugins.containsKey(name)) {
			Plugin plugin = (Plugin) mPlugins.get(name);
			if (plugin.mPluginInfo.nativeLibraryPaths.size() > 0) {
				AlertDialog.Builder builder = new AlertDialog.Builder(activity)
						.setMessage("检测到该插件包含本地库，如卸载需要重启程序").setPositiveButton(
								"确认", new DialogInterface.OnClickListener() {

									@Override
									public void onClick(DialogInterface arg0,
											int arg1) {
										Process.killProcess(Process.myPid());
									}
								});
				builder.show();
				return;
			}
			mPlugins.remove(name);
			plugin.mRealPackageInfo = null;
			plugin.mPluginInfo.isInstalled = false;
			plugin.mLocalBroadCastManagers.clear();
			System.gc();
		}
	}

	/**
	 * @param context
	 * @param text
	 * @param duration
	 */
	void showToast(Context context, String text, int duration) {
		Toast.makeText(mContext, text, duration).show();
	}

	/**
	 * @param context
	 * @param resid
	 * @param duration
	 */
	void showToast(Context context, int resid, int duration) {
		String text = context.getResources().getString(resid);
		Toast.makeText(mContext, text, duration).show();
	}

	/**
	 * 销毁
	 */
	void destory() {
		mContext.unregisterReceiver(mReceiver);
	}
}
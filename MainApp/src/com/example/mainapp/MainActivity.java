package com.example.mainapp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import koala.runtime.InstallPluginListener;
import koala.runtime.PluginInfo;
import koala.runtime.PluginManager;
import koala.runtime.ScanPluginListener;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.AdapterView.OnItemClickListener;

public class MainActivity extends Activity implements ScanPluginListener,
		OnItemClickListener, InstallPluginListener {

	private ProgressDialog mDialog;

	private ListView mListView;
	
	private PluginsAdapter mAdapter;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		mListView = (ListView) findViewById(R.id.list);
		mListView.setOnItemClickListener(this);
		mDialog = new ProgressDialog(this);
		mDialog.setMessage("scanning");
		PluginManager.getInstance().init(this.getBaseContext(),
				getDir("dexout", Context.MODE_PRIVATE).getAbsolutePath());

	}
	
	@Override
	protected void onResume() {
		super.onResume();
		new AsyncTask<Void, Void, File>() {

			@Override
			protected File doInBackground(Void... arg0) {
				File dir = Environment.getExternalStorageDirectory();
				dir = new File(dir, "koala");
				if (dir.exists()) {
					dir.delete();
				}
				dir.mkdirs();
				try {
					File demo = new File(dir, "demo");
					if(!demo.exists()){
						demo.mkdirs();
						InputStream is = getAssets().open("demo/PluginApp.apk");
						File file = new File(demo,"PluginApp.apk");
						OutputStream os = new FileOutputStream(file);
						copyFile(is, os);
						is.close();
						os.close();
						
						is = getAssets().open("demo/libhello-jni.so");
						file = new File(demo,"libhello-jni.so");
						os = new FileOutputStream(file);
						copyFile(is, os);
						is.close();
						os.close();
						
						is = getAssets().open("demo/com.example.pluginapp.MainActivity.enter");
						file = new File(demo,"com.example.pluginapp.MainActivity.enter");
						os = new FileOutputStream(file);
						copyFile(is, os);
						is.close();
						os.close();
					}
					
				} catch (IOException e) {
					e.printStackTrace();
				}
				
				return dir;
			}
			
			private void copyFile(InputStream src, OutputStream des) throws IOException{
				byte[] bytes = new byte[1024];
				int len = 0;
				while((len=src.read(bytes))>0){
					des.write(bytes, 0, len);
				}
				des.flush();
			}
			
			protected void onPostExecute(File result) {
				PluginManager.getInstance().scanApks(result, MainActivity.this);
			};

		}.execute();
	}


	@Override
	public void onScanEnd(ArrayList<PluginInfo> arg0) {
		mAdapter = new PluginsAdapter(this, arg0);
		mListView.setAdapter(mAdapter);
	}

	@Override
	public void onScanStart() {
	}

	@Override
	public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
	}

	static class PluginsAdapter extends BaseAdapter {

		private Context mContext;

		private ArrayList<PluginInfo> mDatas = new ArrayList<PluginInfo>();
		
		private LayoutInflater mInflater;

		public PluginsAdapter(Context context, ArrayList<PluginInfo> datas) {
			if (datas == null) {
				return;
			}
			this.mContext = context;
			this.mDatas = datas;
			mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		}

		@Override
		public int getCount() {
			return mDatas.size();
		}

		@Override
		public Object getItem(int arg0) {
			return mDatas.get(arg0);
		}

		@Override
		public long getItemId(int arg0) {
			return arg0;
		}

		@Override
		public View getView(int pos, View convertView, ViewGroup parent) {
			if (convertView == null) {
				convertView = mInflater.inflate(R.layout.plugin_item, parent,false);
			}
			TextView tv = (TextView) convertView.findViewById(R.id.name);
			final PluginInfo info = mDatas.get(pos);
			tv.setText(info.name);
			boolean install = PluginManager.getInstance().checkInstalled(info.name);
			Button btn = (Button) convertView.findViewById(R.id.status);
			if(install){
				btn.setText("卸载");
				btn.setOnClickListener(new View.OnClickListener() {
					
					@Override
					public void onClick(View arg0) {
						PluginManager.getInstance().uninstallPlugin(info.name);
						notifyDataSetChanged();
					}
				});
			}else{
				btn.setText("安装");
				btn.setOnClickListener(new View.OnClickListener() {
					
					@Override
					public void onClick(View arg0) {
						PluginManager.getInstance().installPlugin(info, (InstallPluginListener) mContext);;
					}
				});
			}
			return convertView;
		}

	}

	@Override
	public void onInstallEnd() {
		mDialog.dismiss();
		mAdapter.notifyDataSetChanged();
	}

	@Override
	public void onInstallStart() {
		mDialog.setMessage("installing");
		mDialog.show();
	}

}

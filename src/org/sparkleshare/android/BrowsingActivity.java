package org.sparkleshare.android;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLConnection;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sparkleshare.android.ui.BaseActivity;
import org.sparkleshare.android.ui.ListEntryItem;
import org.sparkleshare.android.utils.ExternalDirectory;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.RemoteViews;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
/**
 * Activity for browsing content of a SparkleShare-Dashboard instance.
 * @author kai
 *
 */
public class BrowsingActivity extends BaseActivity {
	
	private ListView lvBrowsing;
	private BrowsingAdapter adapter;
	private Context context;
	private String ident, authCode, serverUrl, folderId;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		context = this;
		
		lvBrowsing = new ListView(context);
		adapter = new BrowsingAdapter(context);
		lvBrowsing.setAdapter(adapter);
		lvBrowsing.setOnItemClickListener(onListItemClick());
		setContentView(lvBrowsing);
		
		SharedPreferences prefs = SettingsActivity.getSettings((ContextWrapper) context);
		ident = prefs.getString("ident", "");
		authCode = prefs.getString("authCode", "");
		serverUrl = prefs.getString("serverUrl", "");
		folderId = prefs.getString("folderId", "");
		
		Log.d("folderId", folderId);
		
		String url = getIntent().getStringExtra("url");
		new DownloadFileList().execute(url);
	}
	
	/**
	 * Will be called everytime an item on this activities' listview was clicked.
	 * @return newly created {@link OnItemClickListener}
	 */
	private OnItemClickListener onListItemClick() {
		OnItemClickListener listener = new OnItemClickListener() {

			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				ListEntryItem current = (ListEntryItem) adapter.getItem(position);
				
				if (current.getType().equals("dir")) {
					Intent browseFolder = new Intent(context, BrowsingActivity.class);
					String tmpUrl = serverUrl + "/api/getFolderContent/" + folderId + "?" + current.getUrl();
					browseFolder.putExtra("url", tmpUrl);
					startActivity(browseFolder);
				} else if (current.getType().equals("git")) {
					Intent browseFolder = new Intent(context, BrowsingActivity.class);
					folderId = current.getId();
					SharedPreferences prefs = SettingsActivity.getSettings((ContextWrapper) parent.getContext());
					Editor editor = prefs.edit();
					editor.putString("folderId", folderId);
					editor.commit();
					browseFolder.putExtra("url", serverUrl + "/api/getFolderContent/" + folderId);
					startActivity(browseFolder);
				} else if (current.getType().equals("file")) {
					File file = new File(ExternalDirectory.getExternalRootDirectory() + "/" + current.getTitle());
					if (file.exists()) {
						Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(file.getAbsolutePath()));
						String mime = URLConnection.guessContentTypeFromName(file.getAbsolutePath());
						open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						open.setAction(android.content.Intent.ACTION_VIEW);
						open.setDataAndType((Uri.fromFile(file)), mime);
						startActivity(open);
					} else {
						StringBuilder sb = new StringBuilder();
						sb.append(serverUrl);
						sb.append("/api/getFile/");
						sb.append(folderId + "?");
						sb.append(current.getUrl());
						current.setUrl(sb.toString());
						new DownloadFile().execute(current);
					}
				}
				
			}
		};
		return listener;
	}
	
	private class DownloadFile extends AsyncTask<ListEntryItem, Integer, Boolean> {
		
		Notification notification;
		NotificationManager notificationManager;
		private int maxProgress;
		
		@Override
		protected void onPreExecute() {
			notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			Intent notificationIntent = new Intent(context, BrowsingActivity.class);
			PendingIntent intent = PendingIntent.getActivity(context, 0, notificationIntent, 0);
			notification = new Notification(R.drawable.ic_stat_download, "", System.currentTimeMillis());
			notification.flags = notification.flags | Notification.FLAG_ONGOING_EVENT;
			notification.contentView = new RemoteViews(getApplicationContext().getPackageName(), R.layout.download_progress);
			notification.contentIntent = intent;
		}
		
		@Override
		protected Boolean doInBackground(ListEntryItem... params) {
			// TODO: Check for connectivity
			ListEntryItem current = params[0];
			try {
				HttpClient client = new DefaultHttpClient();
				HttpGet get = new HttpGet(current.getUrl());
				Log.d("url", current.getUrl());
				get.setHeader("X-SPARKLE-IDENT", ident);
				get.setHeader("X-SPARKLE-AUTH", authCode);
				HttpResponse response = client.execute(get);
				if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
					HttpEntity entity = response.getEntity();
					if (entity != null) {
						notification.contentView.setTextViewText(R.id.tv_download_title, current.getTitle());
						notificationManager.notify(17, notification);
						File file = new File(ExternalDirectory.getExternalRootDirectory() + "/" + current.getTitle());
						InputStream input = entity.getContent();
						OutputStream output = new FileOutputStream(file);
						byte buffer[] = new byte[1024];
						int count = 0, total = 0;
						long nextUpdate = System.currentTimeMillis() + 2000;
						while ((count = input.read(buffer)) > 0) {
							output.write(buffer, 0, count);
							total += count;
							if (System.currentTimeMillis() > nextUpdate) {
								publishProgress(total);
								nextUpdate = System.currentTimeMillis() + 3000;
							}
						}
						output.flush();
						output.close();
						input.close();
					}
				} 
			} catch (ClientProtocolException e) {
				Log.e("DownloadFile", e.getLocalizedMessage());
				return false;
			} catch (IOException e) {
				Log.e("DownloadFile", e.getLocalizedMessage());
				return false;
			}
			return true;
		}
		
		@Override
		protected void onProgressUpdate(Integer... values) {
			int progress = values[0];
			notification.contentView.setProgressBar(R.id.pb_download_progressbar, maxProgress, progress, false);
			notificationManager.notify(17, notification);
		}
		
		@Override
		protected void onPostExecute(Boolean result) {
			notificationManager.cancel(17);
		}
	}
	
	
	private class DownloadFileList extends AsyncTask<String, ListEntryItem, Boolean> {
		
		private boolean isProjectsDirectory = false;
		private ProgressDialog loadingDialog;
		
		@Override
		protected void onPreExecute() {
			loadingDialog = ProgressDialog.show(context, "", getString(R.string.please_wait));
		}
		
		@Override
		protected Boolean doInBackground(String... params) {
			String server = params[0];
			
			try {
				// TODO: Refactor I/O here and in SetupActivity to central place
				HttpClient client = new DefaultHttpClient();
				HttpGet get = new HttpGet(server);
				get.setHeader("X-SPARKLE-IDENT", ident);
				get.setHeader("X-SPARKLE-AUTH", authCode);
				HttpResponse response = client.execute(get);
				if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
					BufferedReader in = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
					StringBuffer sb = new StringBuffer();
					String line = "";
					String NL = System.getProperty("line.separator");
					while ((line = in.readLine()) != null) {
						sb.append(line + NL);
					}
					in.close();
					JSONArray folderList = new JSONArray(sb.toString());
					for (int i=0; i<folderList.length(); i++) {
						JSONObject json = folderList.getJSONObject(i);
						ListEntryItem item = new ListEntryItem();
						item.setTitle(json.getString("name"));
						item.setId(json.getString("id"));
						String type = json.getString("type");
						if (type.equals("git")) {
							isProjectsDirectory = true;
						} if (type.equals("file")) {
							item.setFilesize(json.getString("fileSize"));
						}
						
						item.setType(json.getString("type"));
						if (json.has("url")) {
							item.setUrl(json.getString("url"));
						}
						if (json.has("mimeBase")) {
							item.setMimetype(json.getString("mimeBase"));
						}
						publishProgress(item);
					}
				}
			} catch (ClientProtocolException e) {
				Log.e("Browsing failed", e.getLocalizedMessage());
				return false;
			} catch (IOException e) {
				Log.e("Browsing failed", e.getLocalizedMessage());
				return false;
			} catch (JSONException e) {
				Log.e("Browsing failed", e.getLocalizedMessage());
				return false;
			}
			return true;
		}
		
		@Override
		protected void onProgressUpdate(ListEntryItem... values) {
			adapter.addEntry(values[0]);
		}
		
		@Override
		protected void onPostExecute(Boolean result) {
			loadingDialog.dismiss();
			if (isProjectsDirectory) {
				setupActionBar(getString(R.string.projects), Color.WHITE);
				addNewActionButton(R.drawable.ic_action_info, R.string.info, null);
				addNewActionButton(R.drawable.ic_title_add, R.string.add, null);
			} else {
				setupActionBar("SparkleShare", Color.WHITE);
			}
		}
	}
	
}

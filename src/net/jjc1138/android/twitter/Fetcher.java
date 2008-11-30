package net.jjc1138.android.twitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;

import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

public class Fetcher extends Service {
	final static String LOG_TAG = "nanoTweeter";

	private SharedPreferences prefs;

	private FetcherThread fetcherThread;
	private Handler handler;
	private PowerManager.WakeLock wakeLock;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		
		prefs = getSharedPreferences(TwitterConfig.PREFS, 0);
		handler = new Handler();
		wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
			.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);
		wakeLock.setReferenceCounted(false);
	}

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		
		wakeLock.acquire();
		Log.d(LOG_TAG, "Service started.");
		
		if (!prefs.getBoolean("enable", false)) {
			Log.d(LOG_TAG, "Fetching disabled.");
			stopIfIdle();
			return;
		}
		
		((AlarmManager) getSystemService(ALARM_SERVICE)).set(
			AlarmManager.ELAPSED_REALTIME_WAKEUP,
			SystemClock.elapsedRealtime() +
				(prefs.getInt("interval",
					TwitterConfig.INTERVALS[
						TwitterConfig.DEFAULT_INTERVAL_INDEX]) * 60 * 1000),
			PendingIntent.getService(this, 0,
				new Intent(this, Fetcher.class), 0));
		Log.d(LOG_TAG, "Scheduled next run.");
		
		if (fetcherThread != null && fetcherThread.inProgress()) {
			Log.d(LOG_TAG, "Refusing to start another fetcher " +
				"because one is in progress.");
			return;
		} else {
			fetcherThread = new FetcherThread();
			fetcherThread.start();
		}
	}

	private class FetcherThread extends Thread {
		private static final String LAST_TWEET_ID_FILENAME = "lasttweet";
		private final Charset FILE_CHARSET = Charset.forName("US-ASCII");
		private static final int ERROR_NOTIFICATION_ID = 0;
	
		private static final String API_HOST = "twitter.com";
		private static final int API_PORT = 443;
		private static final String API_ROOT =
			"https://" + API_HOST + ":" + API_PORT + "/";
	
		// stopIfIdle() below needs to know if this thread is still doing work.
		// You might think that we could use Thread.isAlive() to find that out,
		// but that wouldn't work because stopIfIdle() is queued to be called
		// from this thread, and it would be possible for stopIfIdle() to start
		// before this thread had actually died. For that reason we use this
		// variable so that the thread can indicate its impending death before
		// queuing the call to stopIfIdle().
		private volatile boolean inProgress = true;
	
		public boolean inProgress() {
			return inProgress;
		}
	
		private void finish(HttpEntity ent) {
			if (ent != null) {
				try {
					ent.consumeContent();
				} catch (IOException e) {
				}
			}
		}
	
		public void fetch() {
			String username = prefs.getString("username", "");
			String password = prefs.getString("password", "");
			if (username.length() == 0 || password.length() == 0) {
				Log.d(LOG_TAG,
					"Skipping fetch because we have no credentials.");
				return;
			}
			
			long last = 1;
			{
				BufferedReader br = null;
				try {
					br = new BufferedReader(new InputStreamReader(
						openFileInput(LAST_TWEET_ID_FILENAME), FILE_CHARSET),
						32);
					last = Long.parseLong(br.readLine());
				} catch (IOException e) {
				} catch (NumberFormatException e) {
				} finally {
					if (br != null) {
						try {
							br.close();
						} catch (IOException e) {
						}
					}
				}
			}
			
			DefaultHttpClient client = new DefaultHttpClient();
			client.getCredentialsProvider().setCredentials(
				new AuthScope(API_HOST, API_PORT),
				new UsernamePasswordCredentials(username, password));
			
			URI u;
			try {
				u = new URI(API_ROOT +
					"statuses/friends_timeline.xml" + '?' +
					"since_id=" + last);
			} catch (URISyntaxException e) {
				assert false;
				return;
			}
			
			HttpResponse r = null;
			try {
				r = client.execute(new HttpGet(u));
			} catch (ClientProtocolException e) {
				assert false;
				return;
			} catch (IOException e) {
				Log.e(LOG_TAG, "Failed to get timeline.");
				return;
			}
			
			int status = r.getStatusLine().getStatusCode();
			// FIXME remove debug:
			Log.v(LOG_TAG, Integer.toString(status));
			
			HttpEntity ent = r.getEntity();
			
			NotificationManager nm =
				(NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			
			if (status == HttpStatus.SC_UNAUTHORIZED) {
				Notification n = new Notification();
				n.icon = R.drawable.icon; // TODO proper error icon
				Intent i = new Intent(
					Fetcher.this, TwitterConfig.class);
				i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				n.setLatestEventInfo(Fetcher.this,
					getString(R.string.app_name),
					getString(R.string.unauthorized),
					PendingIntent.getActivity(Fetcher.this, 0, i, 0));
				n.flags |= Notification.FLAG_AUTO_CANCEL;
				nm.notify(ERROR_NOTIFICATION_ID, n);
				
				finish(ent);
				return;
			} else {
				nm.cancel(ERROR_NOTIFICATION_ID);
			}
			
			if (status == HttpStatus.SC_OK) {
				// Coolness.
			} else if (status == HttpStatus.SC_NOT_MODIFIED) {
				// Nothing new.
				finish(ent);
				// TODO skip onto replies/direct messages.
				return;
			} else {
				// All other response codes are essentially transient errors.
				// "403 Forbidden" and "404 Not Found" are exceptions, but there
				// isn't anything reasonable we can do to recover from them.
				finish(ent);
				return;
			}
			
			if (ent == null) {
				return;
			}
			
			try {
				XMLReader reader = SAXParserFactory.newInstance()
					.newSAXParser().getXMLReader();
				abstract class PathHandler extends DefaultHandler {
					private ArrayList<String> path =
						new ArrayList<String>();
					private ArrayList<StringBuffer> text =
						new ArrayList<StringBuffer>();
				
					protected boolean pathEquals(String[] a) {
						int size = a.length;
						if (path.size() != size) {
							return false;
						}
						for (int i = 0; i < size; ++i) {
							if (!a[i].equals(path.get(i))) {
								return false;
							}
						}
						return true;
					}
				
					protected String getCurrentText() {
						int depth = text.size();
						if (depth == 0) {
							return "";
						}
						return text.get(depth - 1).toString();
					}
				
					@Override
					public void characters(char[] ch, int start, int length)
						throws SAXException {
						
						int depth = text.size();
						if (depth == 0) {
							return;
						}
						text.get(depth - 1).append(ch, start, length);
					}
				
					@Override
					public void startElement(String uri, String localName,
						String name, Attributes attributes)
						throws SAXException {
						
						path.add(localName);
						text.add(new StringBuffer(0));
					}
				
					abstract void endElement();
				
					@Override
					public void endElement(String uri, String localName,
						String name) throws SAXException {
						
						endElement();
						int last = path.size() - 1;
						path.remove(last);
						text.remove(last);
					}
				};
				reader.setContentHandler(new PathHandler() {
					private final String[] statusPath = {
						"statuses", "status" };
					private final String[] idPath = {
						"statuses", "status", "id" };
					private final String[] textPath = {
						"statuses", "status", "text" };
					private final String[] screenNamePath = {
						"statuses", "status", "user", "screen_name" };
				
					private long id = 1;
					private String text;
					private String screenName;
				
					@Override
					void endElement() {
						if (pathEquals(statusPath)) {
							// FIXME remove debug (privacy):
							Log.d(LOG_TAG,
								id + " [" + screenName + "]: " + text);
						} else if (pathEquals(idPath)) {
							try {
								id = Long.parseLong(getCurrentText());
							} catch (NumberFormatException e) {
							}
						} else if (pathEquals(textPath)) {
							text = getCurrentText();
						} else if (pathEquals(screenNamePath)) {
							screenName = getCurrentText();
						}
					}
				});
				InputSource is = new InputSource(ent.getContent());
				is.setEncoding("UTF-8");
				reader.parse(is);
			} catch (ParserConfigurationException e) {
				assert false;
				return;
			} catch (FactoryConfigurationError e) {
				assert false;
				return;
			} catch (SAXException e) {
				return;
			} catch (IOException e) {
				return;
			}
			
			{
				OutputStreamWriter osw = null;
				try {
					osw = new OutputStreamWriter(
						openFileOutput(LAST_TWEET_ID_FILENAME, 0),
						FILE_CHARSET);
					osw.write(Long.toString(last));
				} catch (IOException e) {
					// This is a fairly big problem, because we'll keep
					// notifying the user about the same tweets, but I don't
					// think that there's anything sensible we can do about it.
					// An IOException here probably indicates a critical lack of
					// filesystem space.
					Log.e(LOG_TAG, "Couldn't write last tweet ID.");
				} finally {
					if (osw != null) {
						try {
							osw.close();
						} catch (IOException e) {
						}
					}
				}
			}
		}
	
		@Override
		public void run() {
			fetch();
			
			inProgress = false;
			handler.post(new Runnable() {
				@Override
				public void run() {
					stopIfIdle();
				}
			});
		}
	}

	private void stopIfIdle() {
		if (fetcherThread != null && fetcherThread.inProgress()) {
			// This could happen if this call to stopIfIdle() gets queued after
			// another starting of the service (which would fire up another
			// thread).
			Log.d(LOG_TAG, "Not stopping service because thread is running.");
			return;
		}
		wakeLock.release();
		Log.d(LOG_TAG, "Stopping service.");
		stopSelf();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		
		Log.d(LOG_TAG, "Service destroyed.");
	}
}

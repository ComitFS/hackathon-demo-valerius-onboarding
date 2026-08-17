package com.valerius.wealth.openfire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jivesoftware.openfire.http.HttpBindManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.container.*;
import org.jivesoftware.admin.AuthCheckFilter;
import org.jivesoftware.util.JiveGlobals;

import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.ee8.webapp.WebAppContext;
import org.eclipse.jetty.ee8.servlet.*;

import org.apache.http.*;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.*;
import java.nio.file.*;
import de.mxro.process.*;
import net.sf.json.*;
import org.jitsi.util.OSUtils;

public class OpenAIOnboardingPlugin implements Plugin, ProcessListener {
    private static final Logger Log = LoggerFactory.getLogger(OpenAIOnboardingPlugin.class);
    private SessionMetadataHandler iqHandler;
    private WebAppContext context = null;	
	private XProcess ngrokThread = null;
	private String publicUrl = null;

    @Override
    public void initializePlugin(PluginManager manager, File pluginDir) {
        try {		
			iqHandler = new SessionMetadataHandler();		
			XMPPServer.getInstance().getIQRouter().addHandler(iqHandler);
			
			context = new WebAppContext(null, pluginDir.getPath() + "/classes/wwwroot", "/");
			context.setClassLoader(this.getClass().getClassLoader());
			HttpBindManager.getInstance().addJettyHandler(context);	
			context.setWelcomeFiles(new String[]{"index.html"});
			
			setupNgrok(pluginDir);			
			
			Log.info( "Initialized public web service for /valerius" );	
        }
        catch (Exception e) {
            Log.error("OpenAIOnboardingPlugin initializePlugin", e);
        }		
    }

    @Override
    public void destroyPlugin() {
        try {
			HttpBindManager.getInstance().removeJettyHandler(context);			
			
			if (ngrokThread != null) ngrokThread.destory();	
			
			XMPPServer.getInstance().getIQRouter().removeHandler(iqHandler);
        }
        catch (Exception e) {
            Log.error("OpenAIOnboardingPlugin destroyPlugin", e);
        }			
    }
	
    // -------------------------------------------------------
    //
    //  NGROK
    //
    // -------------------------------------------------------	
	

	private void setupNgrok(File pluginDirectory) {
		if (JiveGlobals.getBooleanProperty("casvoice.enable.ngrok", true))
		{			
			if (runNgrok(pluginDirectory))
			{
				try {
					HttpClient client = new DefaultHttpClient();
					HttpGet get = new HttpGet("http://localhost:4040/api/tunnels");
					HttpResponse response = client.execute(get);
					BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

					String json = "";
					String line;

					while ((line = rd.readLine()) != null) {
					   json = json + line;
					}	
					
					JSONObject ngrok = new JSONObject(json);
					Log.debug("ngrok response\n" + ngrok);	

					/*
					{
					  "tunnels": [
						{
						  "name": "command_line",
						  "uri": "/api/tunnels/command_line",
						  "public_url": "https://0ff4063a73f2.ngrok.io",
						  "proto": "https",
						  "config": {
							"addr": "http://localhost:80",
							"inspect": true
						  },
						  "metrics": {
							"conns": {
							  "count": 0,
							  "gauge": 0,
							  "rate1": 0,
							  "rate5": 0,
							  "rate15": 0,
							  "p50": 0,
							  "p90": 0,
							  "p95": 0,
							  "p99": 0
							},
							"http": {
							  "count": 0,
							  "rate1": 0,
							  "rate5": 0,
							  "rate15": 0,
							  "p50": 0,
							  "p90": 0,
							  "p95": 0,
							  "p99": 0
							}
						  }
						},
						{
						  "name": "command_line (http)",
						  "uri": "/api/tunnels/command_line%20%28http%29",
						  "public_url": "http://0ff4063a73f2.ngrok.io",
						  "proto": "http",
						  "config": {
							"addr": "http://localhost:80",
							"inspect": true
						  },
						  "metrics": {
							"conns": {
							  "count": 0,
							  "gauge": 0,
							  "rate1": 0,
							  "rate5": 0,
							  "rate15": 0,
							  "p50": 0,
							  "p90": 0,
							  "p95": 0,
							  "p99": 0
							},
							"http": {
							  "count": 0,
							  "rate1": 0,
							  "rate5": 0,
							  "rate15": 0,
							  "p50": 0,
							  "p90": 0,
							  "p95": 0,
							  "p99": 0
							}
						  }
						}
					  ],
					  "uri": "/api/tunnels"
					}						
					*/
					
					if (ngrok.has("tunnels"))
					{
						JSONArray tunnels = ngrok.getJSONArray("tunnels");
						
						for (int i=0; i<tunnels.length(); i++)
						{
							JSONObject tunnel = tunnels.getJSONObject(i);
							
							if (tunnel.getString("proto").equals("https"))
							{
								publicUrl = tunnel.getString("public_url");		
								Log.info("setupNgrok " + publicUrl);
								break;
							}
						}						
					}
					
				} catch (Exception e) {
					Log.error(e.getMessage(), e);
				}						
			}
		}
	}

	public boolean runNgrok(File pluginDirectory) {
		if (JiveGlobals.getBooleanProperty("casvoice.use.binary", true))
		{
			final String path = pluginDirectory.getAbsolutePath() + File.separator + "classes" + File.separator +  "apps";	
			
			try {
				String ngrokName = null;
				if (OSUtils.IS_LINUX64) 	ngrokName = "ngrok";
				if (OSUtils.IS_WINDOWS64) 	ngrokName = "ngrok.exe";									
						
				final String ngrok = path + File.separator + ngrokName;	
				String token = JiveGlobals.getProperty("casvoice.ngrok.token");
				Spawn.startProcess(ngrok + " authtoken " + token, new File(path), this);
				
				String cmdLine = ngrok + " http " + JiveGlobals.getProperty("httpbind.port.plain", "7070") + " --url " + JiveGlobals.getProperty("casvoice.ngrok.url", "https://certain-sole-rational.ngrok-free.app");			
				String ngrokDomain = JiveGlobals.getProperty("casvoice.ngrok.domain", null);
				
				if (!isNull(ngrokDomain)) {
					cmdLine = cmdLine + " --domain=" + ngrokDomain;	
				}					
				
				File file = new File(ngrok);			
				file.setReadable(true, true);
				file.setWritable(true, true);
				file.setExecutable(true, true);	
											
				ngrokThread = Spawn.startProcess(cmdLine, new File(path), this);
				Thread.sleep(10000);	// wait for service to start, block whole plugin
				
				Log.debug( "ngrok activated with "  + cmdLine);				
				return true;

			} catch ( Exception e ) {
				Log.error( "An error occurred while testing ngrok", e );
				return false;
			}	
		} else {
			return true;
		}			
	}
	
    public void onOutputLine(final String line) {
        Log.info("FsPlugin onOutputLine " + line);
    }

    public void onProcessQuit(int code) {
        Log.info("FsPlugin onProcessQuit " + code);
    }

    public void onOutputClosed() {
        Log.error("FsPlugin onOutputClosed");
    }

    public void onErrorLine(final String line) {
        Log.debug(line);
    }

    public void onError(final Throwable t) {
        Log.error("FsPluginThread error", t);
    }	

    // -------------------------------------------------------
    //
    //  Utility Functions
    //
    // -------------------------------------------------------	

    private boolean isNull(String value)   {
        return (value == null || "undefined".equals(value)  || "null".equals(value) || "".equals(value.trim()) || "unknown".equals(value) || "none".equals(value));
    }

}

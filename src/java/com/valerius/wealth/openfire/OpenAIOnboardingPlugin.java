package com.valerius.wealth.openfire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.container.*;
import org.jivesoftware.admin.AuthCheckFilter;

import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.ee8.webapp.WebAppContext;
import org.eclipse.jetty.ee8.servlet.*;

import java.io.File;

public class OpenAIOnboardingPlugin implements Plugin {
    private static final Logger Log = LoggerFactory.getLogger(OpenAIOnboardingPlugin.class);
    private SessionMetadataHandler iqHandler;
    private WebAppContext context = null;		

    @Override
    public void initializePlugin(PluginManager manager, File pluginDir) {
        try {		
			 // 1. Inject custom IQ tracking router logic to listen for network state updates
			iqHandler = new SessionMetadataHandler();		
			XMPPServer.getInstance().getIQRouter().addHandler(iqHandler);
			
			// 2. Map servlets securely right inside Openfire's native Jetty router context		
			ContextHandlerCollection contexts = ((AdminConsolePlugin)XMPPServer.getInstance().getPluginManager().getPluginByCanonicalName("admin").orElseThrow()).getContexts();
			context = new WebAppContext(null, pluginDir.getPath() + "/classes/wwwroot", "/valerius-web");
			context.setClassLoader(this.getClass().getClassLoader());
			contexts.addHandler(context);
			context.setWelcomeFiles(new String[]{"index.html"});
			context.start();
			
			AuthCheckFilter.addExclude("valerius/app/*");	
			AuthCheckFilter.addExclude("valerius/session");			
			AuthCheckFilter.addExclude("valerius/acs-token");	
			AuthCheckFilter.addExclude("valerius/voda-retrieve");
			
			Log.info( "Initialized public web service for /valerius" );	
        }
        catch (Exception e) {
            Log.error("OpenAIOnboardingPlugin initializePlugin", e);
        }		
    }

    @Override
    public void destroyPlugin() {
        try {		
			if (context != null && context.isStarted()) context.stop();	
			AuthCheckFilter.removeExclude("valerius/app/*");	
			AuthCheckFilter.removeExclude("valerius/session");	
			AuthCheckFilter.removeExclude("valerius/acs-token");	
			AuthCheckFilter.removeExclude("valerius/voda-retrieve");	
			XMPPServer.getInstance().getIQRouter().removeHandler(iqHandler);
        }
        catch (Exception e) {
            Log.error("OpenAIOnboardingPlugin destroyPlugin", e);
        }			
    }
}

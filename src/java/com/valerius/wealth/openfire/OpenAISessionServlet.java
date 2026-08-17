package com.valerius.wealth.openfire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.*;

import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

public class OpenAISessionServlet extends HttpServlet {
    private static final Logger Log = LoggerFactory.getLogger(OpenAISessionServlet.class);	
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private static final String apiKey = org.jivesoftware.util.JiveGlobals.getProperty("valerius.openai.apiKey");	

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {        
        // Validate front-end request content type
		
        if (!"application/sdp".equalsIgnoreCase(request.getContentType())) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid Content-Type. Expected application/sdp");
            return;
        }

        // Read the client's raw SDP offer text
        StringBuilder sdpOfferBuilder = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sdpOfferBuilder.append(line).append("\n");
            }
        }
        String browserSdpOffer = sdpOfferBuilder.toString().trim();
		Log.debug("SDP offer\n" + browserSdpOffer);

        // 3. Define the structured OpenAI GA session payload
        String sessionConfigJson = "{"
                + "\"type\":\"realtime\","
                + "\"model\":\"gpt-realtime-mini\","
                + "\"audio\":{\"output\":{\"voice\":\"marin\"}}"
                + "}";

        String LINE_FEED = "\r\n";
        String boundary = "AriaBoundary" + UUID.randomUUID().toString().replace("-", "");;

        try {
            URL url = new URL("https://api.openai.com/v1/realtime/calls");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
			//connection.setRequestProperty("Content-Type", "application/sdp");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
			
            try (OutputStream outputStream = connection.getOutputStream();
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, "UTF-8"), true)) {
                
							
                // --- WebRTC SDP Payload ---
                writer.append("--").append(boundary).append(LINE_FEED);
                writer.append("Content-Disposition: form-data; name=\"sdp\"").append(LINE_FEED);
                writer.append("Content-Type: application/sdp").append(LINE_FEED);
                writer.append(LINE_FEED);
                writer.append(browserSdpOffer).append(LINE_FEED);
                writer.flush();
				

                // --- JSON Metadata Payload ---
                writer.append("--").append(boundary).append(LINE_FEED);
                writer.append("Content-Disposition: form-data; name=\"session\"").append(LINE_FEED);
                writer.append("Content-Type: application/json; charset=UTF-8").append(LINE_FEED);
                writer.append(LINE_FEED);
                writer.append(sessionConfigJson).append(LINE_FEED);
                writer.flush();				
				
                // --- Close the Multipart Body ---
                writer.append("--").append(boundary).append("--").append(LINE_FEED);
                //writer.append(browserSdpOffer);				
                writer.flush();
            }			

            int responseCode = connection.getResponseCode();
            Log.debug("Server Response Code: " + responseCode + "\n");
            
			String line;

			if (responseCode == 200) {
				BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));	
				response.setContentType("application/sdp");
				response.setStatus(HttpServletResponse.SC_OK);
				
				while ((line = reader.readLine()) != null) {
					Log.debug(line);
					response.getWriter().write(line);						
				}
				
			} else {
				BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
				response.setStatus(responseCode);
				response.setContentType("application/json");
				
				while ((line = reader.readLine()) != null) {
					Log.debug(line);
					response.getWriter().write(line);						
				}										
			}
            
            connection.disconnect();
            
        } catch (Exception e) {
			Log.error("SDP Error", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "WebRTC Error " + e);
        }
    }
	
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        resp.setContentType("application/json");

        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            resp.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        if (apiKey == null) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"API Key Missing\"}");
            return;
        }

        String jsonPayload = "{\"session\": {\"type\":\"realtime\", \"model\":\"gpt-realtime-mini\",\"output_modalities\":[\"audio\"],\"audio\":{\"output\":{\"voice\":\"marin\"}}}}";

        HttpRequest openAiRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/realtime/client_secrets"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        try {
            HttpResponse<String> openAiResponse = httpClient.send(openAiRequest, HttpResponse.BodyHandlers.ofString());
            resp.setStatus(openAiResponse.statusCode());
            resp.getWriter().write(openAiResponse.body());
        } catch (Exception e) {
            resp.setStatus(500);
        }
    }
}

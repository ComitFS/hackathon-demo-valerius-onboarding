package com.valerius.wealth.openfire;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class OpenAISessionServlet extends HttpServlet {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        resp.setContentType("application/json");

        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            resp.setStatus(HttpServletResponse.SC_OK);
            return;
        }

		// Fetch from persistent Openfire database configuration with a null fallback
		String apiKey = org.jivesoftware.util.JiveGlobals.getProperty("valerius.openai.apiKey");

        if (apiKey == null) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"API Key Missing\"}");
            return;
        }

        String jsonPayload = "{\"model\":\"gpt-4o-realtime-preview\",\"modalities\":[\"audio\",\"text\"],\"voice\":\"alloy\"}";

        HttpRequest openAiRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://openai.com"))
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

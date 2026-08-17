package com.valerius.wealth.openfire;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class VodaRetrieveServlet extends HttpServlet {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setContentType("application/json");

        String requestBody = new String(req.getInputStream().readAllBytes());
        String operatorToken = extractJsonValue(requestBody, "token");
        String appAccessToken = org.jivesoftware.util.JiveGlobals.getProperty("valerius.vodafone.gatewaySecret");

        HttpRequest gatewayRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://vodafone.com"))
                .header("Authorization", "Bearer " + appAccessToken)
                .header("X-Operator-Token", operatorToken)
                .GET()
                .build();

        try {
            HttpResponse<String> gatewayResponse = httpClient.send(gatewayRequest, HttpResponse.BodyHandlers.ofString());
            resp.setStatus(gatewayResponse.statusCode());
            resp.getWriter().write(gatewayResponse.body());
        } catch (Exception e) {
            resp.setStatus(500);
        }
    }

    private String extractJsonValue(String json, String key) {
        try {
            int keyIndex = json.indexOf("\"" + key + "\"");
            int valueStart = json.indexOf("\"", keyIndex + key.length() + 2) + 1;
            return json.substring(valueStart, json.indexOf("\"", valueStart));
        } catch (Exception e) { return ""; }
    }
}

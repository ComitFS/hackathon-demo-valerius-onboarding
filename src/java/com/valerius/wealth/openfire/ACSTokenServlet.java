package com.valerius.wealth.openfire;

import com.azure.core.credential.*;
import com.azure.communication.common.*;
import com.azure.communication.identity.*;
import com.azure.communication.identity.models.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

public class ACSTokenServlet extends HttpServlet {
    private CommunicationIdentityClient clientEngine;

    @Override
    public void init() {
		String connectionString = org.jivesoftware.util.JiveGlobals.getProperty("valerius.acs.connectionString", "endpoint=https://cas-companion.uk.communication.azure.com/;accesskey=DoiGxak8Rf11t0sfNBOMesw6My3SH9Zvwu5dpg8lw7jHVzO7bM5QJQQJ99AGACULyCpMhHOyAAAAAZCS7Krl");

        if (connectionString != null && !connectionString.isEmpty()) {
            this.clientEngine = new CommunicationIdentityClientBuilder().connectionString(connectionString).buildClient();
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setContentType("application/json");

        if (this.clientEngine == null) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"ACS Key Unconfigured\"}");
            return;
        }

        try {
            CommunicationUserIdentifier user = clientEngine.createUser();
            AccessToken token = clientEngine.getToken(user, Collections.singletonList(CommunicationTokenScope.VOIP));
            
            resp.getWriter().write(String.format("{\"token\":\"%s\",\"userRawId\":\"%s\"}", token.getToken(), user.getId()));
        } catch (Exception e) {
            resp.setStatus(502);
        }
    }
}

<%@ page import="org.jivesoftware.util.JiveGlobals" %>
<%@ page import="org.jivesoftware.util.ParamUtils" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%
    // Process form submissions and commit configuration parameters directly to Openfire DB
    boolean savePressed = ParamUtils.getBooleanParameter(request, "save");
    String message = "";
    
    if (savePressed) {
        String openAiKey = ParamUtils.getParameter(request, "openAiKey");
        String acsConnStr = ParamUtils.getParameter(request, "acsConnStr");
        String vodaSecret = ParamUtils.getParameter(request, "vodaSecret");
        
        if (openAiKey != null) JiveGlobals.setProperty("valerius.openai.apiKey", openAiKey.trim());
        if (acsConnStr != null) JiveGlobals.setProperty("valerius.acs.connectionString", acsConnStr.trim());
        if (vodaSecret != null) JiveGlobals.setProperty("valerius.vodafone.gatewaySecret", vodaSecret.trim());
        
        message = "Configuration options securely committed to database.";
    }

    // Retrieve active values to display in form inputs
    String currentOpenAiKey = JiveGlobals.getProperty("valerius.openai.apiKey", "");
    String currentAcsConnStr = JiveGlobals.getProperty("valerius.acs.connectionString", "");
    String currentVodaSecret = JiveGlobals.getProperty("valerius.vodafone.gatewaySecret", "");
%>
<html>
<head>
    <title>Valerius Onboarding Engine Settings</title>
    <!-- Instruct Openfire's web container layout decorator engine to render standard sidebar navigation tabs -->
    <meta name="pageID" content="valerius-settings-tab"/>
</head>
<body>

    <h1>Valerius Wealth Smart Onboarding Configuration</h1>
    <p>Manage API authentication configurations and private carrier keys across your distributed pipeline architecture endpoints securely.</p>

    <% if (!message.isEmpty()) { %>
        <div class="jive-success">
            <table cellpadding="0" cellspacing="0" border="0">
                <tbody>
                    <tr>
                        <td class="jive-icon"><img src="images/success-16x16.gif" width="16" height="16" alt=""></td>
                        <td class="jive-icon-label"><%= message %></td>
                    </tr>
                </tbody>
            </table>
        </div><br>
    <% } %>

    <form action="valerius-config.jsp" method="post">
        <input type="hidden" name="save" value="true">
        
        <div class="jive-table">
            <table cellpadding="0" cellspacing="0" border="0" width="100%">
                <thead>
                    <tr>
                        <th colspan="2">Integration API Parameters</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td width="30%"><b>OpenAI Realtime API Key:</b><br><small>Used by the WebRTC S2S token generator engine.</small></td>
                        <td><input type="password" name="openAiKey" value="<%= currentOpenAiKey %>" style="width: 80%;"></td>
                    </tr>
                    <tr>
                        <td width="30%"><b>Azure Communication Connection String:</b><br><small>Used to issue ephemeral VoIP calling variables.</small></td>
                        <td><input type="password" name="acsConnStr" value="<%= currentAcsConnStr %>" style="width: 80%;"></td>
                    </tr>
                    <tr>
                        <td width="30%"><b>Vodafone Open Gateway Secret:</b><br><small>Used for CAMARA L0 SIM identity checks.</small></td>
                        <td><input type="password" name="vodaSecret" value="<%= currentVodaSecret %>" style="width: 80%;"></td>
                    </tr>
                </tbody>
            </table>
        </div>

        <input type="submit" value="Save System Properties" style="margin-top: 15px; padding: 6px 12px; font-weight: bold; cursor: pointer;">
    </form>

</body>
</html>

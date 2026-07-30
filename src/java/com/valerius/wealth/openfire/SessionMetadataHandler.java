package com.valerius.wealth.openfire;

import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.SessionManager;
import org.xmpp.packet.IQ;

public class SessionMetadataHandler extends IQHandler {
    public SessionMetadataHandler() {
        super("Valerius Metadata Linker");
    }

    @Override
    public IQ handleIQ(IQ packet) {
        if (packet.getType() == IQ.Type.set && packet.getChildElement().getNamespaceURI().equals("http://valerius.wealth")) {
            String verifiedPhone = packet.getChildElement().element("identity").elementText("msisdn");
            ClientSession session = SessionManager.getInstance().getSession(packet.getFrom());
			
            if (session != null) {
				// TODO
                //session.setSessionData("VERIFIED_MSISDN", verifiedPhone);
            }
        }
        return IQ.createResultIQ(packet);
    }

    @Override
    public IQHandlerInfo getInfo() {
        return new IQHandlerInfo("session", "http://valerius.wealth");
    }
}

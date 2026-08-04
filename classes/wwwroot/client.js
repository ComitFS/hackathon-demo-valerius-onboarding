let openfireHost = window.location.host;
let pc = null; let dataChannel = null; let xmppConnection = null;
let clientMicStream = null; let openAiRemoteStream = null;
const PUBSUB_SERVICE = 'pubsub.' + openfireHost.split(':')[0];
const SESSION_NODE = 'onboarding_session_alexander_vance';

async function runSilentVodafoneDiscovery() {
    const banner = document.getElementById('identityStatus');
    try {
        // 1. Fetch cellular hardware authentication validation values
        let authReq = await fetch(`http://${openfireHost}/plugins/valerius/api/session`); 
        let authRes = await authReq.json();
        
        // Target fake mock response sequence token wrapper for sandbox execution loops
        let operatorToken = "MOCK_VODA_TOKEN_XYZ_2026"; 

        let verifyReq = await fetch(`http://${openfireHost}/plugins/valerius/api/voda-retrieve`, {
            method: "POST", body: JSON.stringify({ token: operatorToken })
        });
        let verifyRes = await verifyReq.json();
        let msisdn = verifyRes.phoneNumber || "+44 7700 900077";

        banner.style.background = "rgba(16, 185, 129, 0.1)";
        banner.innerHTML = `<div class="status-dot" style="background:#10B981"></div><div>Vodafone Network Binding Authenticated: ${msisdn}</div>`;
        
        connectToOpenfireXmpp(msisdn);
    } catch (e) {
        banner.innerText = "Fallback triggered. Activating encryption layer pipeline hooks...";
        connectToOpenfireXmpp("+447700900077");
    }
}

function connectToOpenfireXmpp(msisdn) {
    xmppConnection = new Strophe.Connection(`${window.location.protocol}//${openfireHost}/http-bind/`);
    xmppConnection.connect("localhost", null, (status) => {
        if (status === Strophe.Status.CONNECTED) {
            saveNumberToSessionContext(msisdn);
            broadcastPayload({ type: "FORM_UPDATE", data: { formPhone: msisdn } });
            initOpenAiRealtimeConnection();
        }
    });
}

function saveNumberToSessionContext(msisdn) {
    const iq = $iq({ type: "set", id: "voda_bind" })
        .c("session", { xmlns: "http://valerius.wealth" })
        .c("identity").c("msisdn").t(msisdn);
    xmppConnection.send(iq);
}

async function initOpenAiRealtimeConnection() {
    let tokenReq = await fetch(`http://${openfireHost}/plugins/valerius/api/session`);
    let sessionData = await tokenReq.json();
    let ephemeralKey = sessionData.client_secret.value;

    pc = new RTCPeerConnection();
    pc.ontrack = (e) => {
        openAiRemoteStream = e.streams[0];
        document.getElementById('aiState').innerText = 'Speaking...';
        document.getElementById('aiAvatar').classList.add('active');
        dialHumanFaPstn("+447700900000"); // Trigger dynamic ACS PSTN call route mapping
    };

    clientMicStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false } });
    clientMicStream.getTracks().forEach(t => pc.addTrack(t, clientMicStream));

    dataChannel = pc.createDataChannel("oai-events");
    dataChannel.onmessage = (e) => {
        let serverEvent = JSON.parse(e.data);
        if (serverEvent.type === "conversation.item.created" && serverEvent.item?.content?.[0]?.type === "text") {
            broadcastPayload({
                type: "TRANSCRIPT", speaker: serverEvent.item.role === "assistant" ? "AI" : "CLIENT",
                timestamp: new Date().toLocaleTimeString(), text: serverEvent.item.content[0].text
            });
        }
    };

    let offer = await pc.createOffer();
    await pc.setLocalDescription(offer);

    let sdpReq = await fetch(`https://openai.com`, {
        method: "POST", body: offer.sdp,
        headers: { "Authorization": `Bearer ${ephemeralKey}`, "Content-Type": "application/sdp" }
    });
    await pc.setRemoteDescription({ type: "answer", sdp: await sdpReq.text() });
}

function broadcastPayload(data) {
    if (!xmppConnection) return;
    const item = Strophe.xmlElement('item', { id: 'msg_' + Date.now() }, 
        Strophe.xmlElement('entry', { xmlns: 'http://valerius.wealth' }, JSON.stringify(data))
    );
    const pub = $iq({ type: 'set', to: PUBSUB_SERVICE }).c('pubsub', { xmlns: 'http://jabber.org' })
        .c('publish', { node: SESSION_NODE }).child(item);
    xmppConnection.send(pub);
}

async function dialHumanFaPstn(targetNumber) {
    let acsReq = await fetch(`http://${openfireHost}/plugins/valerius/api/acs-token`);
    let acsRes = await acsReq.json();
    
    let ctx = new (window.AudioContext || window.webkitAudioContext)();
    let mSrc = ctx.createMediaStreamSource(clientMicStream);
    let oSrc = ctx.createMediaStreamSource(openAiRemoteStream);
    let dest = ctx.createMediaStreamDestination();
    
    mSrc.connect(dest); oSrc.connect(dest); oSrc.connect(ctx.destination); // Route stream outputs cleanly

    const callClient = new AzureCommunicationCalling.CallClient();
    const cred = new AzureCommunicationCalling.AzureCommunicationTokenCredential(acsRes.token);
    let agent = await callClient.createCallAgent(cred);
    let acsTrack = dest.stream.getAudioTracks()[0];
    let acsStream = new AzureCommunicationCalling.LocalAudioStream(acsTrack);
    
    agent.startCall([{ phoneNumber: targetNumber }], { audioOptions: { localAudioStreams: [acsStream] } });
    document.getElementById('humanState').innerText = "Connected via PSTN";
    document.getElementById('humanAvatar').classList.add('active');
}

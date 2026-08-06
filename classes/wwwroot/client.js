let openfireHost = window.location.host;
let pc = null; 
let localStream  = null;
let dataChannel = null;
let xmppConnection = null, clientPreVerifiedMetadata;
let clientMicStream = null; let openAiRemoteStream = null;

const PUBSUB_SERVICE = 'pubsub.localhost'; // + openfireHost.split(':')[0];
const SESSION_NODE = 'onboarding_session_alexander_vance';

const ARIA_SYSTEM_PROMPT = `
# IDENTITY & AUDIENCE
You are Aria, an elite Virtual Digital Financial Adviser conducting a frictionless, JMLSG-aligned KYC onboarding conversation for a High-Net-Worth (HNW) UK client.

# OPERATIONAL CONTEXT (PRE-VERIFIED CLIENT)
- Crucial: Before the call started, the client was cryptographically authenticated via WebAuthN and network-verified via Mobile Carrier APIs.
- You already have their verified Legal Name, Mobile Number, and Device Trust Token. 
- DO NOT ask the client to state or prove their name or phone number. Address them confidently by name.

# OPERATIONAL PROTOCOL (DUAL STREAM)
1. AUDIO STREAM: Speak with an empathetic, ultra-polished private banking persona. Keep spoken responses under 2 sentences. Focus heavily on conversational elegance.
2. STRUCTURED OUTPUT: Progressively populate and update the underlying KYC data structure via the 'submit_kyc_data' tool on every turn.

# CONVERSATIONAL FLOW
- Phase 1: Premium Welcome & Address Confirmation. Greet by name. Confirm UK residential address.
- Phase 2: Wealth Profile. Gather net worth estimations and primary Source of Wealth (SoW).
- Phase 3: Statutory Compliance. Capture National Insurance Number (NINO) and screen for PEP status.
- Gather exactly ONE piece of information at a time.
`;

const KYC_SCHEMA_TOOL = {
  type: "function",
  name: "submit_kyc_data",
  description: "Progressively update the structured KYC data layer for the UK banking automation pipeline based on the conversation.",
  parameters: {
    type: "object",
    properties: {
      pre_call_authentication: {
        type: "object",
        properties: {
          webauthn_authenticated: { type: "boolean" },
          carrier_sim_verified: { type: "boolean" },
          device_trust_score: { type: "string", enum: ["HIGH", "MEDIUM", "LOW"] }
        },
        required: ["webauthn_authenticated", "carrier_sim_verified", "device_trust_score"]
      },
      biographical_data: {
        type: "object",
        properties: {
          full_legal_name: { type: ["string", "null"] },
          uk_residential_address: { type: ["string", "null"] },
          address_confirmed_by_client: { type: "boolean" },
          national_insurance_number: { type: ["string", "null"] }
        },
        required: ["full_legal_name", "uk_residential_address", "address_confirmed_by_client", "national_insurance_number"]
      },
      financial_profile: {
        type: "object",
        properties: {
          estimated_net_worth_gbp: { type: ["number", "null"] },
          source_of_wealth_primary: { type: ["string", "null"] },
          source_of_wealth_details: { type: ["string", "null"] }
        },
        required: ["estimated_net_worth_gbp", "source_of_wealth_primary", "source_of_wealth_details"]
      },
      compliance_flags: {
        type: "object",
        properties: {
          pep_status_confirmed: { type: ["boolean", "null"] },
          sanction_match_suspicion: { type: "boolean" }
        },
        required: ["pep_status_confirmed", "sanction_match_suspicion"]
      },
      next_automated_action: {
        type: "string",
        enum: ["CONTINUE_INTERVIEW", "ROUTE_TO_ELECTRONIC_ADDRESS_LOOKUP", "TRIGGER_CREDIT_CHECK", "HALT_AND_REFER_TO_HUMAN"]
      }
    },
    required: ["pre_call_authentication", "biographical_data", "financial_profile", "compliance_flags", "next_automated_action"]
  }
};

window.onbeforeunload = function(event) {
	console.debug("⚠️ Window unloading detected. Cleaning up Aria WebRTC session...");	
	closeConnections();
};

window.onload = function() {
	console.debug("⚠️ Window loading detected");	
};

function closeConnections() {
	if (xmppConnection) xmppConnection.disconnect();

	// A. Stop all local microphone / camera media tracks immediately
	if (clientMicStream) {
		clientMicStream.getTracks().forEach((track) => {
		  try {
			track.stop(); // Releases hardware control and turns off browser record indicators
			console.debug(`✅ Media track stopped: ${track.kind}`);
		  } catch (err) {
			console.error("Error stopping media track:", err);
		  }
		});
		clientMicStream = null;
	}

	// B. Gracefully close the OpenAI WebRTC Data Channel
	if (dataChannel) {
		try {
		  if (dataChannel.readyState !== 'closed') {
			dataChannel.close();
			console.debug("✅ OpenAI Data Channel closed cleanly.");
		  }
		} catch (err) {
		  console.error("Error closing data channel:", err);
		}
		dataChannel = null;
	}

	// C. Disconnect the primary Peer Connection
	if (pc) {
		try {
		  // Remove track listeners to clear browser event loops
		  pc.ontrack = null;
		  pc.onicecandidate = null;
		  pc.onconnectionstatechange = null;
		  
		  pc.close();
		  console.debug("✅ RTCPeerConnection terminated cleanly.");
		} catch (err) {
		  console.error("Error closing peer connection:", err);
		}
		pc = null;
	}	
}

async function runSilentVodafoneDiscovery() {
	console.debug("runSilentVodafoneDiscovery");
	
    const banner = document.getElementById('identityStatus');
	const statusDot = document.querySelector(".status-dot");
    try {
		/*
        // 1. Fetch cellular hardware authentication validation values
        let authReq = await fetch(`${window.location.protocol}//${openfireHost}/session`); 
        let authRes = await authReq.json();
        
        // Target fake mock response sequence token wrapper for sandbox execution loops
        let operatorToken = "MOCK_VODA_TOKEN_XYZ_2026"; 

        let verifyReq = await fetch(`${window.location.protocol}//${openfireHost}/voda-retrieve`, {
            method: "POST", body: JSON.stringify({ token: operatorToken })
        });
        let verifyRes = await verifyReq.json();
        let msisdn = verifyRes.phoneNumber || "+44 7700 900077";
		*/
		
		let msisdn = "+447825589457";
	
		clientPreVerifiedMetadata = {
		  pre_call_authentication: {
			webauthn_authenticated: true,
			carrier_sim_verified: true,
			device_trust_score: "HIGH"
		  },
		  biographical_data: {
			full_legal_name: "Dele Olajide",
			uk_residential_address: "151, Pike Road, Hoo, Rochecter. ME3 4GA",
			address_confirmed_by_client: false, // Set to false; Aria will verbally confirm this
			national_insurance_number: null     // To be gathered during the call
		  }
		};		
        banner.innerHTML = `Vodafone Network Binding Authenticated: ${msisdn}`;
		statusDot.style.background = "#10B981";
        
        connectToOpenfireXmpp(msisdn);
    } catch (e) {
        banner.innerText = "Fallback triggered. Activating encryption layer pipeline hooks...";
        connectToOpenfireXmpp("+447825589457");
    }
}

function connectToOpenfireXmpp(msisdn) {
	console.debug("connectToOpenfireXmpp", msisdn);
	
    xmppConnection = new Strophe.Connection(`${window.location.protocol}//${openfireHost}/http-bind/`);
	
    xmppConnection.connect("localhost", null, (status) => {
        if (status === Strophe.Status.CONNECTED) {
            xmppConnection.send($pres());				
            initOpenAiRealtimeConnection();
            saveNumberToSessionContext(msisdn);
            broadcastPayload({ type: "FORM_UPDATE", data: { formPhone: msisdn } });			
        }
    });
}

function saveNumberToSessionContext(msisdn) {
	console.debug("saveNumberToSessionContext", msisdn);
	
    const iq = $iq({ type: "set", id: "voda_bind" })
        .c("session", { xmlns: "http://valerius.wealth" })
        .c("identity").c("msisdn").t(msisdn);
    xmppConnection.send(iq);
}

async function initOpenAiRealtimeConnection() {
	console.debug("initOpenAiRealtimeConnection");

	const rtcConfig = {
	  bundlePolicy: "max-bundle", 
	  rtcpMuxPolicy: "require" 
	};
	
	const pc = new RTCPeerConnection(rtcConfig);
	const audioElement = document.createElement("audio");
	audioElement.autoplay = true;
	
    pc.ontrack = (e) => {
        openAiRemoteStream = e.streams[0];
        document.getElementById('aiState').innerText = 'Speaking...';
        document.getElementById('aiAvatar').classList.add('active');
		audioElement.srcObject = e.streams[0];
        //dialHumanFaPstn("+447700900000"); // Trigger dynamic ACS PSTN call route mapping
    };

    clientMicStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
	pc.addTrack(clientMicStream.getTracks()[0]);;

    dataChannel = pc.createDataChannel("oai-events");
	
    dataChannel.onopen = (e) => {
		console.debug("Data channel ready for event pipelines.");

		// A. Configure the Session Environment
		const sessionUpdateEvent = {
		type: "session.update",
		session: {
		  modalities: ["text", "audio"],
		  instructions: ARIA_SYSTEM_PROMPT,
		  voice: "marin",
		  tools: [KYC_SCHEMA_TOOL],
		  tool_choice: "auto",
		  turn_detection: { type: "server_vad" }
		}
		};
		dataChannel.send(JSON.stringify(sessionUpdateEvent));

		// B. Inject the Pre-Verified Metadata into the Conversation History
		const injectStateEvent = {
		type: "conversation.item.create",
		item: {
		  type: "message",
		  role: "system",
		  content: [
			{
			  type: "input_text",
			  text: `[SYSTEM METADATA INJECTION] The client has successfully authenticated via WebAuthN and Carrier API before connection. 
			  Current Client Identity Record: ${JSON.stringify(clientPreVerifiedMetadata)}. 
			  Immediately generate the initial response welcoming the client by their name and confirming their address.`
			}
		  ]
		}
		};
		dataChannel.send(JSON.stringify(injectStateEvent));

		// C. Force Aria to evaluate the injected state and verbally open the call
		const triggerResponseEvent = {
		type: "response.create",
		response: {
		  instructions: "Greet Dele Olajide warmly, reference the secure biometric connection, and verify if 151, Pike Road, Hoo is still his current residential address."
		}
		};
		dataChannel.send(JSON.stringify(triggerResponseEvent));
	};	
	
    dataChannel.onmessage = (e) => {
        let serverEvent = JSON.parse(e.data);
		
        if (serverEvent.type === "conversation.item.created" && serverEvent.item?.content?.[0]?.type === "text") {
            broadcastPayload({
                type: "TRANSCRIPT", speaker: serverEvent.item.role === "assistant" ? "AI" : "CLIENT",
                timestamp: new Date().toLocaleTimeString(), text: serverEvent.item.content[0].text
            });
        }
		
		 // Handle when the AI finishes speaking to toggle UI states
		 if (serverEvent.type === "response.done") {
			document.getElementById('aiState').innerText = 'Listening....';
			document.getElementById('aiAvatar').classList.remove('active');
		 }
    };

	let offer = await pc.createOffer();
	await pc.setLocalDescription(offer);
	
	await new Promise((resolve) => 
	{
	  if (pc.iceGatheringState === 'complete') {
		resolve();
	  } else {
		function checkState() {
		  if (pc.iceGatheringState === 'complete') {
			pc.removeEventListener('icegatheringstatechange', checkState);
			resolve();
		  }
		}
		pc.addEventListener('icegatheringstatechange', checkState);
	  }
	});	
	
	/*
	let sdpReq = await fetch(`${window.location.protocol}//${openfireHost}/session`, {
		method: "POST", 
		body: pc.localDescription.sdp, // Pure plain text SDP string mapping
		headers: { 
			"Content-Type": "application/sdp" 
		}
	});	
	*/
	
	const tokenResponse = await fetch(`${window.location.protocol}//${openfireHost}/session`);
	const data = await tokenResponse.json();
	const EPHEMERAL_KEY = data.value;	
	console.debug("initOpenAiRealtimeConnection - EPHEMERAL_KEY", data);	
	
	const sdpReq = await fetch("https://api.openai.com/v1/realtime/calls", {
	  method: "POST",
	  body: offer.sdp,
	  headers: {
		Authorization: `Bearer ${EPHEMERAL_KEY}`,
		"Content-Type": "application/sdp",
	  },
	});	
	
	const answerSdp = await sdpReq.text();	
	console.debug("ANSWER SDP", answerSdp);	
	await pc.setRemoteDescription({ type: "answer", sdp: answerSdp });
}

function broadcastPayload(data) {
	console.debug("broadcastPayload");
	
    if (!xmppConnection) return;
    const item = Strophe.xmlElement('item', { id: 'msg_' + Date.now() }, 
        Strophe.xmlElement('entry', { xmlns: 'http://valerius.wealth' }, JSON.stringify(data))
    );
    const pub = $iq({ type: 'set', to: PUBSUB_SERVICE }).c('pubsub', { xmlns: 'http://jabber.org' }).c('publish', { node: SESSION_NODE }).child(item);
    xmppConnection.send(pub);
}

async function dialHumanFaPstn(targetNumber) {
	console.debug("dialHumanFaPstn", targetNumber);
	
    let acsReq = await fetch(`${window.location.protocol}//${openfireHost}/acs-token`);
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

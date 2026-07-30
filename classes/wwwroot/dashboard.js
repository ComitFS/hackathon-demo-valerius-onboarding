let openfireHost = window.location.host || "localhost:7070";
let dbConn = null;
const PUBSUB_SERVICE = 'pubsub.' + openfireHost.split(':')[0];
const SESSION_NODE = 'onboarding_session_alexander_vance';

window.onload = () => {
    dbConn = new Strophe.Connection(`http://${openfireHost}/http-bind/`);
    dbConn.connect("advisor_console@localhost", "password", (status) => {
        if (status === Strophe.Status.CONNECTED) {
            dbConn.send($iq({ type: 'set', to: PUBSUB_SERVICE }).c('pubsub', { xmlns: 'http://jabber.org' })
                .c('subscribe', { node: SESSION_NODE, jid: dbConn.jid }));
            dbConn.addHandler((stanza) => {
                let items = stanza.getElementsByTagName('entry');
                if (items.length > 0) {
                    let frame = JSON.parse(items[0].textContent);
                    if (frame.type === "TRANSCRIPT") updateTranscripts(frame);
                    if (frame.type === "FORM_UPDATE") autoPopulateFields(frame.data);
                }
                return true;
            }, null, 'message');
        }
    });
};

function updateTranscripts(f) {
    const box = document.getElementById('transcripts');
    const r = document.createElement('div'); r.className = 'transcript-row';
    const cls = f.speaker === "AI" ? "ts-speaker ai" : "ts-speaker client";
    r.innerHTML = `<span>[${f.timestamp}]</span> <span class="${cls}">${f.speaker}:</span> <span>${f.text}</span>`;
    box.appendChild(r); box.scrollTop = box.scrollHeight;
}

function autoPopulateFields(data) {
    const map = { fullName: 'formName', formPhone: 'formPhone', sourceOfWealth: 'formSource', targetLiquidity: 'formNetWorth', summaryNotes: 'formNotes' };
    Object.keys(data).forEach(k => {
        let el = document.getElementById(map[k]);
        if (el && el.value !== data[k]) {
            el.value = data[k];
            el.classList.remove('field-updated-flash'); void el.offsetWidth; el.classList.add('field-updated-flash');
        }
    });
}

function resetDashboard() {
    ['formName', 'formPhone', 'formSource', 'formNetWorth', 'formNotes'].forEach(id => {
        let el = document.getElementById(id); if (el) { el.value = ''; el.classList.remove('field-updated-flash'); }
    });
    document.getElementById('transcripts').innerHTML = '';
    document.getElementById('amlFlag').className = "badge-status badge-clean";
    document.getElementById('amlFlag').innerText = "WAITING FOR NEW SESSION";
}

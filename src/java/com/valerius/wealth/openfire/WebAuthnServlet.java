package com.valerius.wealth.openfire;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.json.JSONObject;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class WebAuthnServlet extends HttpServlet {
    private static final Logger Log = LoggerFactory.getLogger(WebAuthnServlet.class);

    private static final long STATE_TTL_MILLIS = 2 * 60 * 1000L;
    private static final long CLEANUP_INTERVAL_MILLIS = 15 * 1000L;
    private static final AtomicLong lastCleanupAt = new AtomicLong(0L);

    private static final Map<String, Map<String, StoredCredential>> CREDENTIALS_BY_MSISDN = new ConcurrentHashMap<>();
    private static final Map<String, ByteArray> USER_HANDLES_BY_MSISDN = new ConcurrentHashMap<>();
    private static final Map<String, RegistrationState> REGISTRATION_STATES = new ConcurrentHashMap<>();
    private static final Map<String, AssertionState> ASSERTION_STATES = new ConcurrentHashMap<>();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        applyCommonHeaders(req, resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        applyCommonHeaders(req, resp);
        cleanupExpiredStates();

        if (!isHttps(req)) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"WebAuthn requires HTTPS\"}");
            return;
        }

        String path = req.getPathInfo();
        if (path == null) {
            writeJson(resp, HttpServletResponse.SC_NOT_FOUND, "{\"error\":\"Endpoint not found\"}");
            return;
        }

        try {
            switch (path) {
                case "/register/start":
                    handleRegisterStart(req, resp);
                    return;
                case "/register/finish":
                    handleRegisterFinish(req, resp);
                    return;
                case "/authenticate/start":
                    handleAuthenticateStart(req, resp);
                    return;
                case "/authenticate/finish":
                    handleAuthenticateFinish(req, resp);
                    return;
                default:
                    writeJson(resp, HttpServletResponse.SC_NOT_FOUND, "{\"error\":\"Endpoint not found\"}");
            }
        } catch (Exception e) {
            Log.error("WebAuthn endpoint error", e);
            writeJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "{\"error\":\"WebAuthn operation failed\"}");
        }
    }

    private void handleRegisterStart(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JSONObject body = readBody(req);
        String msisdn = body.optString("msisdn", "").trim();

        if (msisdn.isEmpty()) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Missing msisdn\"}");
            return;
        }

        RelyingParty relyingParty = buildRelyingParty(req);
        ByteArray userHandle = USER_HANDLES_BY_MSISDN.computeIfAbsent(msisdn,
                key -> new ByteArray(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        UserIdentity userIdentity = UserIdentity.builder()
                .name(msisdn)
                .displayName(body.optString("displayName", msisdn))
                .id(userHandle)
                .build();

        PublicKeyCredentialCreationOptions options = relyingParty.startRegistration(
                StartRegistrationOptions.builder()
                        .user(userIdentity)
                        .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                                .userVerification(UserVerificationRequirement.REQUIRED)
                                .build())
                        .build()
        );

        String requestId = UUID.randomUUID().toString();
        REGISTRATION_STATES.put(requestId, new RegistrationState(msisdn, options, currentRpOrigin(req), userHandle));

        String responseJson = "{"
                + "\"requestId\":\"" + jsonEscape(requestId) + "\"," 
                + "\"publicKey\":" + options.toCredentialsCreateJson()
                + "}";

        writeJson(resp, HttpServletResponse.SC_OK, responseJson);
    }

    private void handleRegisterFinish(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JSONObject body = readBody(req);
        String requestId = body.optString("requestId", "").trim();
        String credentialJson = body.optJSONObject("credential") != null
                ? body.getJSONObject("credential").toString()
                : body.optString("credential", "");

        if (requestId.isEmpty() || credentialJson.isEmpty()) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Missing requestId or credential\"}");
            return;
        }

        RegistrationState state = REGISTRATION_STATES.remove(requestId);
        if (state == null) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Registration request expired\"}");
            return;
        }

        if (!state.origin.equals(currentRpOrigin(req))) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Origin mismatch\"}");
            return;
        }

        RelyingParty relyingParty = buildRelyingParty(req);
        PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> credential =
                PublicKeyCredential.parseRegistrationResponseJson(credentialJson);

        RegistrationResult result = relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                .request(state.options)
                .response(credential)
                .build());

        StoredCredential stored = new StoredCredential(
                state.msisdn,
                state.userHandle,
                result.getKeyId().getId(),
                result.getPublicKeyCose(),
                result.getSignatureCount(),
                result.getKeyId().getTransports().orElse(Collections.emptySortedSet())
        );

        putCredential(state.msisdn, stored);

        writeJson(resp, HttpServletResponse.SC_OK, "{\"registered\":true}");
    }

    private void handleAuthenticateStart(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JSONObject body = readBody(req);
        String msisdn = body.optString("msisdn", "").trim();

        if (msisdn.isEmpty()) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Missing msisdn\"}");
            return;
        }

        if (!hasCredentials(msisdn)) {
            writeJson(resp, HttpServletResponse.SC_OK, "{\"requiresRegistration\":true}");
            return;
        }

        RelyingParty relyingParty = buildRelyingParty(req);
        AssertionRequest assertionRequest = relyingParty.startAssertion(
                StartAssertionOptions.builder().username(msisdn).build()
        );

        String requestId = UUID.randomUUID().toString();
        ASSERTION_STATES.put(requestId, new AssertionState(msisdn, assertionRequest, currentRpOrigin(req)));

        String responseJson = "{"
                + "\"requestId\":\"" + jsonEscape(requestId) + "\"," 
                + "\"publicKey\":" + assertionRequest.getPublicKeyCredentialRequestOptions().toCredentialsGetJson()
                + "}";

        writeJson(resp, HttpServletResponse.SC_OK, responseJson);
    }

    private void handleAuthenticateFinish(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JSONObject body = readBody(req);
        String requestId = body.optString("requestId", "").trim();
        String credentialJson = body.optJSONObject("credential") != null
                ? body.getJSONObject("credential").toString()
                : body.optString("credential", "");

        if (requestId.isEmpty() || credentialJson.isEmpty()) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Missing requestId or credential\"}");
            return;
        }

        AssertionState state = ASSERTION_STATES.remove(requestId);
        if (state == null) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Authentication request expired\"}");
            return;
        }

        if (!state.origin.equals(currentRpOrigin(req))) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Origin mismatch\"}");
            return;
        }

        RelyingParty relyingParty = buildRelyingParty(req);
        PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> credential =
                PublicKeyCredential.parseAssertionResponseJson(credentialJson);

        AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                .request(state.request)
                .response(credential)
                .build());

        if (!result.isSuccess()) {
            writeJson(resp, HttpServletResponse.SC_UNAUTHORIZED, "{\"authenticated\":false}");
            return;
        }

        StoredCredential stored = findCredential(state.msisdn, credential.getId());
        if (stored != null) {
            StoredCredential updated = new StoredCredential(
                    stored.msisdn,
                    stored.userHandle,
                    stored.credentialId,
                    stored.publicKeyCose,
                    result.getSignatureCount(),
                    stored.transports
            );
            putCredential(state.msisdn, updated);
        }

        writeJson(resp, HttpServletResponse.SC_OK, "{\"authenticated\":true}");
    }

    private RelyingParty buildRelyingParty(HttpServletRequest req) {
        String rpId = req.getServerName();
        String rpOrigin = currentRpOrigin(req);

        return RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder().id(rpId).name("Valerius Wealth").build())
                .credentialRepository(new InMemoryCredentialRepository())
                .origins(Collections.singleton(rpOrigin))
                .build();
    }

    private boolean isHttps(HttpServletRequest req) {
        String forwardedProto = trustedForwardedProto(req);
        if (forwardedProto != null && "https".equalsIgnoreCase(forwardedProto)) {
            return true;
        }
        return req.isSecure() || "https".equalsIgnoreCase(req.getScheme());
    }

    private String currentRpOrigin(HttpServletRequest req) {
        String forwardedProto = trustedForwardedProto(req);
        String scheme = (forwardedProto != null && !forwardedProto.isEmpty()) ? forwardedProto : req.getScheme();
        String host = req.getHeader("Host");
        if (host == null || host.trim().isEmpty()) {
            host = req.getServerName();
            int port = req.getServerPort();
            if (port != 80 && port != 443) {
                host = host + ":" + port;
            }
        }
        return scheme + "://" + host;
    }

    private String trustedForwardedProto(HttpServletRequest req) {
        if (!isTrustedProxyRequest(req)) {
            return null;
        }
        return req.getHeader("X-Forwarded-Proto");
    }

    private boolean isTrustedProxyRequest(HttpServletRequest req) {
        String remoteAddr = req.getRemoteAddr();
        String configured = org.jivesoftware.util.JiveGlobals.getProperty(
                "valerius.webauthn.trustedProxies",
                "127.0.0.1,::1"
        );
        for (String candidate : configured.split(",")) {
            if (remoteAddr.equals(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    private JSONObject readBody(HttpServletRequest req) throws IOException {
        String payload = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (payload.trim().isEmpty()) {
            return new JSONObject();
        }
        return JSONObject.fromObject(payload);
    }

    private void applyCommonHeaders(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", currentRpOrigin(req));
        resp.setHeader("Vary", "Origin");
        resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setContentType("application/json");
    }

    private void writeJson(HttpServletResponse resp, int status, String body) throws IOException {
        resp.setStatus(status);
        resp.getWriter().write(body);
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void cleanupExpiredStates() {
        long now = System.currentTimeMillis();
        long previous = lastCleanupAt.get();
        if ((now - previous) < CLEANUP_INTERVAL_MILLIS) {
            return;
        }
        if (!lastCleanupAt.compareAndSet(previous, now)) {
            return;
        }
        REGISTRATION_STATES.entrySet().removeIf(entry -> (now - entry.getValue().createdAt) > STATE_TTL_MILLIS);
        ASSERTION_STATES.entrySet().removeIf(entry -> (now - entry.getValue().createdAt) > STATE_TTL_MILLIS);
    }

    private void putCredential(String msisdn, StoredCredential credential) {
        CREDENTIALS_BY_MSISDN.computeIfAbsent(msisdn, key -> new ConcurrentHashMap<>())
                .put(credential.credentialId.getBase64Url(), credential);
    }

    private boolean hasCredentials(String msisdn) {
        Map<String, StoredCredential> credentialMap = CREDENTIALS_BY_MSISDN.get(msisdn);
        return credentialMap != null && !credentialMap.isEmpty();
    }

    private StoredCredential findCredential(String msisdn, ByteArray credentialId) {
        Map<String, StoredCredential> credentialMap = CREDENTIALS_BY_MSISDN.get(msisdn);
        if (credentialMap == null) {
            return null;
        }
        return credentialMap.get(credentialId.getBase64Url());
    }

    private static class InMemoryCredentialRepository implements CredentialRepository {
        @Override
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
            Map<String, StoredCredential> credentials = CREDENTIALS_BY_MSISDN.get(username);
            if (credentials == null || credentials.isEmpty()) {
                return Collections.emptySet();
            }

            Set<PublicKeyCredentialDescriptor> ids = new HashSet<>();
            for (StoredCredential stored : credentials.values()) {
                ids.add(PublicKeyCredentialDescriptor.builder()
                        .id(stored.credentialId)
                        .build());
            }
            return ids;
        }

        @Override
        public Optional<ByteArray> getUserHandleForUsername(String username) {
            Map<String, StoredCredential> credentials = CREDENTIALS_BY_MSISDN.get(username);
            if (credentials == null || credentials.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(credentials.values().iterator().next().userHandle);
        }

        @Override
        public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
            for (Map.Entry<String, Map<String, StoredCredential>> entry : CREDENTIALS_BY_MSISDN.entrySet()) {
                for (StoredCredential stored : entry.getValue().values()) {
                    if (stored.userHandle.equals(userHandle)) {
                        return Optional.of(entry.getKey());
                    }
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
            for (Map<String, StoredCredential> credentialMap : CREDENTIALS_BY_MSISDN.values()) {
                for (StoredCredential stored : credentialMap.values()) {
                    if (stored.credentialId.equals(credentialId) && stored.userHandle.equals(userHandle)) {
                        return Optional.of(toRegisteredCredential(stored));
                    }
                }
            }
            return Optional.empty();
        }

        @Override
        public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
            Set<RegisteredCredential> matches = new HashSet<>();
            for (Map<String, StoredCredential> credentialMap : CREDENTIALS_BY_MSISDN.values()) {
                for (StoredCredential stored : credentialMap.values()) {
                    if (stored.credentialId.equals(credentialId)) {
                        matches.add(toRegisteredCredential(stored));
                    }
                }
            }
            return matches;
        }

        private RegisteredCredential toRegisteredCredential(StoredCredential stored) {
            return RegisteredCredential.builder()
                    .credentialId(stored.credentialId)
                    .userHandle(stored.userHandle)
                    .publicKeyCose(stored.publicKeyCose)
                    .signatureCount(stored.signatureCount)
                    .build();
        }
    }

    private static class StoredCredential {
        private final String msisdn;
        private final ByteArray userHandle;
        private final ByteArray credentialId;
        private final ByteArray publicKeyCose;
        private final long signatureCount;
        private final Set<AuthenticatorTransport> transports;

        private StoredCredential(String msisdn, ByteArray userHandle, ByteArray credentialId, ByteArray publicKeyCose, long signatureCount,
                                 Set<AuthenticatorTransport> transports) {
            this.msisdn = msisdn;
            this.userHandle = userHandle;
            this.credentialId = credentialId;
            this.publicKeyCose = publicKeyCose;
            this.signatureCount = signatureCount;
            this.transports = transports;
        }
    }

    private static class RegistrationState {
        private final String msisdn;
        private final PublicKeyCredentialCreationOptions options;
        private final String origin;
        private final ByteArray userHandle;
        private final long createdAt;

        private RegistrationState(String msisdn, PublicKeyCredentialCreationOptions options, String origin, ByteArray userHandle) {
            this.msisdn = msisdn;
            this.options = options;
            this.origin = origin;
            this.userHandle = userHandle;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private static class AssertionState {
        private final String msisdn;
        private final AssertionRequest request;
        private final String origin;
        private final long createdAt;

        private AssertionState(String msisdn, AssertionRequest request, String origin) {
            this.msisdn = msisdn;
            this.request = request;
            this.origin = origin;
            this.createdAt = System.currentTimeMillis();
        }
    }
}

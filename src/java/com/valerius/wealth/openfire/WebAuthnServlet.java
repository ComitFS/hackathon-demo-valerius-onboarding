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
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
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
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WebAuthnServlet extends HttpServlet {
    private static final Logger Log = LoggerFactory.getLogger(WebAuthnServlet.class);

    private static final Map<String, StoredCredential> CREDENTIALS_BY_MSISDN = new ConcurrentHashMap<>();
    private static final Map<String, RegistrationState> REGISTRATION_STATES = new ConcurrentHashMap<>();
    private static final Map<String, AssertionState> ASSERTION_STATES = new ConcurrentHashMap<>();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        applyCommonHeaders(resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        applyCommonHeaders(resp);

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
        UserIdentity userIdentity = UserIdentity.builder()
                .name(msisdn)
                .displayName(body.optString("displayName", msisdn))
                .id(new ByteArray(msisdn.getBytes(StandardCharsets.UTF_8)))
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
        REGISTRATION_STATES.put(requestId, new RegistrationState(msisdn, options, currentRpOrigin(req)));

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
                credential.getResponse().getUserHandle().orElse(new ByteArray(state.msisdn.getBytes(StandardCharsets.UTF_8))),
                result.getKeyId().getId(),
                result.getPublicKeyCose(),
                result.getSignatureCount(),
                result.getKeyId().getTransports().orElse(Collections.emptySet())
        );

        CREDENTIALS_BY_MSISDN.put(state.msisdn, stored);

        writeJson(resp, HttpServletResponse.SC_OK, "{\"registered\":true}");
    }

    private void handleAuthenticateStart(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JSONObject body = readBody(req);
        String msisdn = body.optString("msisdn", "").trim();

        if (msisdn.isEmpty()) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Missing msisdn\"}");
            return;
        }

        if (!CREDENTIALS_BY_MSISDN.containsKey(msisdn)) {
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

        StoredCredential stored = CREDENTIALS_BY_MSISDN.get(state.msisdn);
        if (stored != null && result.getCredential().isPresent()) {
            StoredCredential updated = new StoredCredential(
                    stored.msisdn,
                    stored.userHandle,
                    stored.credentialId,
                    stored.publicKeyCose,
                    result.getSignatureCount(),
                    stored.transports
            );
            CREDENTIALS_BY_MSISDN.put(state.msisdn, updated);
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
        String forwardedProto = req.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && "https".equalsIgnoreCase(forwardedProto)) {
            return true;
        }
        return req.isSecure() || "https".equalsIgnoreCase(req.getScheme());
    }

    private String currentRpOrigin(HttpServletRequest req) {
        String forwardedProto = req.getHeader("X-Forwarded-Proto");
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

    private JSONObject readBody(HttpServletRequest req) throws IOException {
        String payload = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (payload.trim().isEmpty()) {
            return new JSONObject();
        }
        return JSONObject.fromObject(payload);
    }

    private void applyCommonHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
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

    private static class InMemoryCredentialRepository implements CredentialRepository {
        @Override
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
            StoredCredential stored = CREDENTIALS_BY_MSISDN.get(username);
            if (stored == null) {
                return Collections.emptySet();
            }

            Set<PublicKeyCredentialDescriptor> ids = new HashSet<>();
            ids.add(PublicKeyCredentialDescriptor.builder()
                    .id(stored.credentialId)
                    .transports(Optional.ofNullable(stored.transports).orElse(Collections.emptySet()))
                    .build());
            return ids;
        }

        @Override
        public Optional<ByteArray> getUserHandleForUsername(String username) {
            StoredCredential stored = CREDENTIALS_BY_MSISDN.get(username);
            return stored == null ? Optional.empty() : Optional.of(stored.userHandle);
        }

        @Override
        public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
            for (StoredCredential stored : CREDENTIALS_BY_MSISDN.values()) {
                if (stored.userHandle.equals(userHandle)) {
                    return Optional.of(stored.msisdn);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
            for (StoredCredential stored : CREDENTIALS_BY_MSISDN.values()) {
                if (stored.credentialId.equals(credentialId) && stored.userHandle.equals(userHandle)) {
                    return Optional.of(toRegisteredCredential(stored));
                }
            }
            return Optional.empty();
        }

        @Override
        public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
            Set<RegisteredCredential> matches = new HashSet<>();
            for (StoredCredential stored : CREDENTIALS_BY_MSISDN.values()) {
                if (stored.credentialId.equals(credentialId)) {
                    matches.add(toRegisteredCredential(stored));
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
        private final Set<com.yubico.webauthn.data.AuthenticatorTransport> transports;

        private StoredCredential(String msisdn, ByteArray userHandle, ByteArray credentialId, ByteArray publicKeyCose, long signatureCount,
                                 Set<com.yubico.webauthn.data.AuthenticatorTransport> transports) {
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

        private RegistrationState(String msisdn, PublicKeyCredentialCreationOptions options, String origin) {
            this.msisdn = msisdn;
            this.options = options;
            this.origin = origin;
        }
    }

    private static class AssertionState {
        private final String msisdn;
        private final AssertionRequest request;
        private final String origin;

        private AssertionState(String msisdn, AssertionRequest request, String origin) {
            this.msisdn = msisdn;
            this.request = request;
            this.origin = origin;
        }
    }
}

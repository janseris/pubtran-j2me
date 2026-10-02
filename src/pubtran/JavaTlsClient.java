//#ifdef JAVA_TLS
package pubtran;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.crypto.tls.Certificate;
import org.bouncycastle.crypto.tls.CertificateRequest;
import org.bouncycastle.crypto.tls.CipherSuite;
import org.bouncycastle.crypto.tls.DefaultTlsClient;
import org.bouncycastle.crypto.tls.ProtocolVersion;
import org.bouncycastle.crypto.tls.ServerNameList;
import org.bouncycastle.crypto.tls.TlsAuthentication;
import org.bouncycastle.crypto.tls.TlsCredentials;
import org.bouncycastle.crypto.tls.TlsExtensionsUtils;
import org.bouncycastle.crypto.tls.TlsSession;

/**
 * BouncyCastle TLS 1.2 client for the Java TLS path (JavaTls):
 *  - sends SNI, which the phone's native TLS (the EKA1 patch) doesn't do for Java apps;
 *  - offers only ECDHE and plain-RSA suites: DHE would need a 2048-bit modular
 *    exponentiation in Java, which takes far too long on the Nokia 9300;
 *  - accepts the server certificate without checking the chain - the same as the native
 *    EKA1 patch (built with NO_VERIFY), and it saves the slow chain signature checks.
 *    The server's ServerKeyExchange signature is still verified with the leaf key;
 *  - resumes the previous TLS session to the same host when the server allows it
 *    (an abbreviated handshake without the elliptic-curve maths);
 *  - records protocol, cipher suite and the leaf certificate into a TlsInfo for the log.
 */
public class JavaTlsClient extends DefaultTlsClient {
    /** Last resumable session per host name (TlsSession). */
    private static final Hashtable sessions = new Hashtable();

    private final String host;
    public final TlsInfo info = new TlsInfo();
    private byte[] offeredSessionId;
    /** True when the server accepted the offered session (abbreviated handshake). */
    public boolean resumed;

    public JavaTlsClient(String host) {
        this.host = host;
    }

    public int[] getCipherSuites() {
        return new int[] {
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        };
    }

    public Hashtable getClientExtensions() throws IOException {
        Hashtable ext = TlsExtensionsUtils.ensureExtensionsInitialised(super.getClientExtensions());
        Vector names = new Vector();
        names.addElement(new SniServerName(host));
        TlsExtensionsUtils.addServerNameExtension(ext, new ServerNameList(names));
        return ext;
    }

    public TlsSession getSessionToResume() {
        TlsSession s = (TlsSession) sessions.get(host);
        if (s != null && s.isResumable()) {
            offeredSessionId = s.getSessionID();
            return s;
        }
        return null;
    }

    public void notifySessionID(byte[] sessionID) {
        super.notifySessionID(sessionID);
        resumed = offeredSessionId != null && sessionID != null
            && sessionID.length > 0 && equal(offeredSessionId, sessionID);
    }

    public void notifyHandshakeComplete() throws IOException {
        super.notifyHandshakeComplete();
        try {
            TlsSession s = context.getResumableSession();
            if (s != null && s.isResumable()) sessions.put(host, s);
        }
        catch (Throwable e) {}
    }

    /** Forget the stored session for a host (after a failed resumption). */
    public static void forgetSession(String host) {
        sessions.remove(host);
    }

    public void notifyServerVersion(ProtocolVersion serverVersion) throws IOException {
        super.notifyServerVersion(serverVersion);
        info.protocol = serverVersion.toString();
    }

    public void notifySelectedCipherSuite(int suite) {
        super.notifySelectedCipherSuite(suite);
        info.cipherSuite = suiteName(suite);
    }

    public TlsAuthentication getAuthentication() throws IOException {
        return new TlsAuthentication() {
            public void notifyServerCertificate(Certificate chain) throws IOException {
                captureCertificate(chain);
            }

            public TlsCredentials getClientCredentials(CertificateRequest request) throws IOException {
                return null;
            }
        };
    }

    private void captureCertificate(Certificate chain) {
        try {
            if (chain == null || chain.isEmpty()) {
                info.note = "Server neposlal certifikát.";
                return;
            }
            org.bouncycastle.asn1.x509.Certificate leaf = chain.getCertificateAt(0);
            info.certSubject = leaf.getSubject().toString();
            info.certIssuer = leaf.getIssuer().toString();
            AlgorithmIdentifier alg = leaf.getSignatureAlgorithm();
            if (alg != null && alg.getAlgorithm() != null) {
                info.certSigAlgorithm = TlsInfo.sigAlgName(alg.getAlgorithm().getId());
            }
            info.certType = "X.509";
            info.certVersion = String.valueOf(leaf.getVersionNumber());
            info.certSerial = leaf.getSerialNumber().getValue().toString(16).toUpperCase();
            info.certValidity = leaf.getStartDate().getTime() + "\n  - " + leaf.getEndDate().getTime();
            info.note = "Řetězec: " + chain.getLength() + " certifikát(y), neověřován (jako nativní EKA1 TLS).";
        }
        catch (Throwable e) {
            info.note = "Certifikát nelze přečíst: " + e;
        }
    }

    static String suiteName(int s) {
        switch (s) {
            case CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256: return "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256";
            case CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256: return "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256";
            case CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA: return "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA";
            case CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256: return "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256";
            case CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256: return "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256";
            case CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA: return "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA";
            case CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256: return "TLS_RSA_WITH_AES_128_GCM_SHA256";
            case CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256: return "TLS_RSA_WITH_AES_128_CBC_SHA256";
            case CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA: return "TLS_RSA_WITH_AES_128_CBC_SHA";
        }
        return "0x" + Integer.toHexString(s).toUpperCase();
    }

    private static boolean equal(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }
}
//#endif

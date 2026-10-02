package pubtran;

import java.util.Date;
import javax.microedition.io.SecurityInfo;
import javax.microedition.pki.Certificate;

/**
 * What TLS/certificate info could be captured for one request - read from the native
 * TLS stack's SecurityInfo (HttpsConnection in NativeHttp, or SecureConnection in
 * TlsTestScreen), so RequestLog/LogDetailScreen can show it. Fields are left null (and note explains why) when something couldn't be read -
 * this is a diagnostic aid, not something the rest of the app depends on.
 */
public class TlsInfo {
    /** e.g. "TLS 1.2" */
    public String protocol;

    /** e.g. "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256" */
    public String cipherSuite;

    public String certSubject;
    public String certIssuer;
    public String certSigAlgorithm;

    /** Human-readable validity range, or null if not available. */
    public String certValidity;

    public String certSerial;
    public String certType;
    public String certVersion;

    /** Set instead of the fields above when info couldn't be captured at all. */
    public String note;

    /**
     * A rough, plain-language strength rating from the cipher suite name alone: does it
     * use forward secrecy (ECDHE/DHE), an AEAD cipher (GCM/CCM), and does it avoid
     * long-broken primitives (NULL/EXPORT/RC4/DES/anon/MD5). This is not real
     * cryptanalysis - just enough to answer "was this a strong connection" at a glance
     * in the log screen, which is what "how complicated the certificate was" comes down
     * to in practice: the cipher suite, not the certificate file itself, is what
     * determines how much math the handshake needed.
     */
    public static String classify(String suiteName) {
        if (suiteName == null || suiteName.length() == 0) {
            return "Unknown";
        }
        String s = suiteName.toUpperCase();
        if (s.indexOf("NULL") >= 0 || s.indexOf("EXPORT") >= 0 || s.indexOf("ANON") >= 0
                || s.indexOf("RC4") >= 0 || s.indexOf("_DES_") >= 0 || s.indexOf("MD5") >= 0) {
            return "Weak";
        }
        boolean forwardSecrecy = s.indexOf("ECDHE") >= 0 || s.indexOf("DHE") >= 0;
        boolean aead = s.indexOf("GCM") >= 0 || s.indexOf("CCM") >= 0 || s.indexOf("CHACHA20") >= 0;
        if (s.indexOf("3DES") >= 0) {
            return forwardSecrecy ? "Moderate (3DES, but forward secrecy)" : "Weak-moderate (3DES)";
        }
        if (forwardSecrecy && aead) {
            return "Strong (forward secrecy + AEAD)";
        }
        if (forwardSecrecy) {
            return "Strong (forward secrecy)";
        }
        if (aead) {
            return "Moderate (AEAD, no forward secrecy)";
        }
        return "Moderate";
    }

    /**
     * Maps a handful of common X.509 signature algorithm OIDs to their usual names.
     * Falls back to the raw OID for anything not in the table, rather than guessing.
     */
    public static String sigAlgName(String oid) {
        if (oid == null) return "?";
        if (oid.equals("1.2.840.113549.1.1.4")) return "md5WithRSAEncryption";
        if (oid.equals("1.2.840.113549.1.1.5")) return "sha1WithRSAEncryption";
        if (oid.equals("1.2.840.113549.1.1.11")) return "sha256WithRSAEncryption";
        if (oid.equals("1.2.840.113549.1.1.12")) return "sha384WithRSAEncryption";
        if (oid.equals("1.2.840.113549.1.1.13")) return "sha512WithRSAEncryption";
        if (oid.equals("1.2.840.10045.4.1")) return "ecdsa-with-SHA1";
        if (oid.equals("1.2.840.10045.4.3.2")) return "ecdsa-with-SHA256";
        if (oid.equals("1.2.840.10045.4.3.3")) return "ecdsa-with-SHA384";
        if (oid.equals("1.2.840.10045.4.3.4")) return "ecdsa-with-SHA512";
        return oid;
    }

    /** Reads everything SecurityInfo offers. Never throws. */
    public static TlsInfo from(SecurityInfo si) {
        TlsInfo info = new TlsInfo();
        if (si == null) {
            info.note = "SecurityInfo není k dispozici.";
            return info;
        }
        // Every getter separately: on some implementations (e.g. emulators) one of them
        // throws, and that shouldn't hide the fields that can be read.
        StringBuffer failed = new StringBuffer();
        String pn = null, pv = null;
        try { pn = si.getProtocolName(); } catch (Throwable e) { fail(failed, "protokol", e); }
        try { pv = si.getProtocolVersion(); } catch (Throwable e) { fail(failed, "verze", e); }
        if (pn != null || pv != null) info.protocol = (pn == null ? "?" : pn) + " " + (pv == null ? "" : pv);
        try { info.cipherSuite = si.getCipherSuite(); } catch (Throwable e) { fail(failed, "šifra", e); }

        Certificate cert = null;
        try { cert = si.getServerCertificate(); } catch (Throwable e) { fail(failed, "certifikát", e); }
        if (cert != null) {
            try { info.certSubject = cert.getSubject(); } catch (Throwable e) { fail(failed, "subjekt", e); }
            try { info.certIssuer = cert.getIssuer(); } catch (Throwable e) { fail(failed, "vydavatel", e); }
            try { info.certSigAlgorithm = cert.getSigAlgName(); } catch (Throwable e) { fail(failed, "podpis", e); }
            try { info.certSerial = cert.getSerialNumber(); } catch (Throwable e) { fail(failed, "sériové č.", e); }
            try { info.certType = cert.getType(); } catch (Throwable e) { fail(failed, "typ", e); }
            try { info.certVersion = cert.getVersion(); } catch (Throwable e) { fail(failed, "verze cert.", e); }
            try {
                info.certValidity = new Date(cert.getNotBefore()) + "\n  - " + new Date(cert.getNotAfter());
            }
            catch (Throwable e) { fail(failed, "platnost", e); }
        }
        else {
            if (failed.length() > 0) failed.append("; ");
            failed.append("certifikát (getServerCertificate() vrátil null)");
        }
        if (failed.length() > 0) {
            info.note = "Nelze přečíst: " + failed;
            if (info.protocol == null && info.cipherSuite == null && info.certSubject == null) {
                String plat = System.getProperty("microedition.platform");
                info.note += "\n\nTato Java implementace ("
                    + (plat == null ? "?" : plat)
                    + ") SecurityInfo neposkytuje - typické pro KEmulator; nativní TLS "
                    + "(SSLADAPTOR.dll) na Nokia 9300 by ho mělo vracet.";
            }
        }
        return info;
    }

    private static void fail(StringBuffer sb, String what, Throwable e) {
        if (sb.length() > 0) sb.append("; ");
        sb.append(what).append(" (").append(e.toString()).append(")");
    }

    /** Full multi-line breakdown for the log detail screen. */
    public String toDetailText() {
        if (note != null && protocol == null && cipherSuite == null) {
            return note;
        }
        StringBuffer sb = new StringBuffer();
        sb.append("Protokol: ").append(protocol == null ? "?" : protocol).append("\n");
        sb.append("Šifra: ").append(cipherSuite == null ? "?" : cipherSuite).append("\n");
        sb.append("Síla: ").append(classify(cipherSuite)).append("\n\n");
        sb.append("Certifikát pro:\n").append(certSubject == null ? "?" : certSubject).append("\n\n");
        sb.append("Vydal:\n").append(certIssuer == null ? "?" : certIssuer).append("\n\n");
        sb.append("Podpis: ").append(certSigAlgorithm == null ? "?" : certSigAlgorithm);
        if (certType != null) sb.append("\nTyp: ").append(certType).append(certVersion != null ? " v" + certVersion : "");
        if (certSerial != null) sb.append("\nSériové č.: ").append(certSerial);
        if (certValidity != null) sb.append("\nPlatnost: ").append(certValidity);
        if (note != null) sb.append("\n\n").append(note);
        return sb.toString();
    }
}

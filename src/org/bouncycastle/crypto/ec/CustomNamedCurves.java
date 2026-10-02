//#ifdef JAVA_TLS
package org.bouncycastle.crypto.ec;

import java.util.Enumeration;
import java.util.Vector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9ECPoint;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.custom.sec.SecP256R1Curve;
import org.bouncycastle.math.ec.custom.sec.SecP384R1Curve;
import org.bouncycastle.util.encoders.Hex;

/**
 * pubtran-j2me: replaces BouncyCastle's CustomNamedCurves (bundled from
 * lib/bouncycastle.jar, overridden at build time by this class) with just the two curves
 * the Java TLS client offers (JavaTlsClient: secp256r1 and secp384r1). The original
 * registers 32 curves in its static initializer, which keeps ~190 elliptic-curve classes
 * in the JAR (and loads them on the first handshake). Same constants as the original.
 */
public class CustomNamedCurves {
    private static X9ECParameters p256, p384;

    public static synchronized X9ECParameters getByName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase();
        if (n.equals("secp256r1") || n.equals("p-256")) {
            if (p256 == null) {
                ECCurve c = new SecP256R1Curve();
                X9ECPoint g = new X9ECPoint(c, Hex.decode("04"
                    + "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296"
                    + "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"));
                p256 = new X9ECParameters(c, g, c.getOrder(), c.getCofactor(),
                    Hex.decode("C49D360886E704936A6678E1139D26B7819F7E90"));
            }
            return p256;
        }
        if (n.equals("secp384r1") || n.equals("p-384")) {
            if (p384 == null) {
                ECCurve c = new SecP384R1Curve();
                X9ECPoint g = new X9ECPoint(c, Hex.decode("04"
                    + "AA87CA22BE8B05378EB1C71EF320AD746E1D3B628BA79B9859F741E082542A385502F25DBF55296C3A545E3872760AB7"
                    + "3617DE4A96262C6F5D9E98BF9292DC29F8F41DBD289A147CE9DA3113B5F0B8C00A60B1CE1D7E819D7A431D7C90EA0E5F"));
                p384 = new X9ECParameters(c, g, c.getOrder(), c.getCofactor(),
                    Hex.decode("A335926AA319A27A1D00896A6773A4827ACDAC73"));
            }
            return p384;
        }
        return null;
    }

    /** Curve OIDs as they appear in certificates' EC public keys. */
    static final String OID_P256 = "1.2.840.10045.3.1.7";
    static final String OID_P384 = "1.3.132.0.34";

    public static X9ECParameters getByOID(ASN1ObjectIdentifier oid) {
        String name = getName(oid);
        return name == null ? null : getByName(name);
    }

    public static ASN1ObjectIdentifier getOID(String name) {
        if (name == null) return null;
        String n = name.toLowerCase();
        if (n.equals("secp256r1") || n.equals("p-256")) return new ASN1ObjectIdentifier(OID_P256);
        if (n.equals("secp384r1") || n.equals("p-384")) return new ASN1ObjectIdentifier(OID_P384);
        return null;
    }

    public static String getName(ASN1ObjectIdentifier oid) {
        if (oid == null) return null;
        String id = oid.getId();
        if (OID_P256.equals(id)) return "secp256r1";
        if (OID_P384.equals(id)) return "secp384r1";
        return null;
    }

    public static Enumeration getNames() {
        Vector v = new Vector();
        v.addElement("secp256r1");
        v.addElement("secp384r1");
        return v.elements();
    }
}
//#endif

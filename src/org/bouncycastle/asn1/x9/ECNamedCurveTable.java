//#ifdef JAVA_TLS
package org.bouncycastle.asn1.x9;

import java.util.Enumeration;
import java.util.Vector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.crypto.ec.CustomNamedCurves;

/**
 * pubtran-j2me: replaces BouncyCastle's ECNamedCurveTable (overridden at build time) -
 * the original pulls in every curve table (X9, SEC, NIST, Brainpool, ANSSI, GM, ...).
 * The Java TLS client only needs secp256r1/secp384r1, which CustomNamedCurves provides,
 * so this table just forwards to it.
 */
public class ECNamedCurveTable {
    public static X9ECParameters getByName(String name) {
        return CustomNamedCurves.getByName(name);
    }

    public static ASN1ObjectIdentifier getOID(String name) {
        return CustomNamedCurves.getOID(name);
    }

    public static String getName(ASN1ObjectIdentifier oid) {
        return CustomNamedCurves.getName(oid);
    }

    public static X9ECParameters getByOID(ASN1ObjectIdentifier oid) {
        return CustomNamedCurves.getByOID(oid);
    }

    public static Enumeration getNames() {
        return CustomNamedCurves.getNames();
    }
}
//#endif
